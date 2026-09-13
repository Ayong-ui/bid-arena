import type { AuctionStatus } from '../api/types'
import type { WsTicket } from '../api/types'
import {
  booleanField,
  eventKey,
  numberField,
  parseEvent,
  stringField,
  type AuctionEventEnvelope,
  type AuctionEventType,
} from './events'
import type { FeedState, SocketFactory, SocketLike } from './socket'

/** `AUCTION_SNAPSHOT` 事件与 HTTP 快照同构，只是不带 `description`（事件不承载文案）。 */
export interface SnapshotPayload {
  id: string
  title: string
  status: AuctionStatus
  startPrice: number
  minIncrement: number
  currentPrice: number
  leaderAnon: string | null
  endsAt: string | null
  extensionCount: number
  participantCount: number
  seq: number
  /**
   * 尾段“博弈时间”长度（秒），由服务端下发（`AUCTION_FINAL_GAME_WINDOW_SECONDS`）。
   * 前端只用它把“剩余时间 ≤ 窗口”渲染成提示，不做任何出价判定。
   */
  finalGameWindowSeconds: number
  serverTime: string
}

export interface FeedDeps {
  /** 取一次性票（`POST /auth/ws-tickets`）。每次重连都要重新取：票用后即废。 */
  fetchTicket(): Promise<WsTicket>
  /** 拉权威快照（`GET /auctions/{id}`）——缺口恢复的唯一手段（契约 §6）。 */
  fetchSnapshot(): Promise<SnapshotPayload>
  createSocket: SocketFactory
  url(ticket: WsTicket): string
  /** 重连等待，可注入以便测试。返回一个可取消的句柄。 */
  schedule(callback: () => void, delayMs: number): unknown
  cancelSchedule(handle: unknown): void
  /** 重连退避序列，单位毫秒。 */
  backoffMs?: readonly number[]
}

export interface FeedHandlers {
  /** 权威快照：消费方应以它**覆盖**本地状态，并据此重置基线。 */
  onSnapshot(snapshot: SnapshotPayload, source: 'socket' | 'http'): void
  /** 已经去重、且相对当前基线连续的事件。 */
  onEvent(event: AuctionEventEnvelope): void
  onState(state: FeedState, detail: FeedStateDetail): void
  onError?(error: unknown): void
}

export interface FeedStateDetail {
  /** 服务端给出的原因码（握手失败时来自 `CONNECTION_STATE`）。 */
  code?: string
  message?: string
  /** 第几次重连尝试，从 1 开始。 */
  attempt?: number
}

const DEFAULT_BACKOFF = [1_000, 2_000, 4_000, 8_000, 10_000] as const

/**
 * 连续多少次“快照仍然落后于缓存事件”就放弃补放。
 *
 * 正常情况下一次快照就能把缺口补上（数据库里的 seq 一定 ≥ 已经推出去的事件）。
 * 永远补不上说明服务端快照与推送不一致，这时再重试只是打接口；
 * 宁可丢掉几帧过期事件、如实告诉用户，也不能把界面永远卡在“正在恢复”。
 */
const MAX_RESYNC_STREAK = 5

/**
 * 单场拍卖的实时订阅：负责**连接、去重、缺口恢复**，不负责解读业务。
 *
 * 三条来自契约的硬规则，全部落在这里：
 *
 * 1. 去重键是 `(auctionId, seq, type)`，不是 `seq`——一次提交会发多个同 `seq` 的事件
 *    （最后 5 秒内的出价同时发 `BID_ACCEPTED` 与 `AUCTION_EXTENDED`）；
 * 2. 发现缺口（`seq > lastSeq + 1`）**不猜测、不等待**，立刻 `GET` 权威快照，
 *    然后丢弃 `seq <= snapshot.seq` 的缓存事件、按序应用剩下的；
 * 3. 重连后重新取票、重新拿快照——服务端不存事件回溯缓冲，历史只有数据库里有。
 *
 * 为什么把状态机放在这里而不是组件里：组件会被卸载、会被重复挂载，
 * 而"当前基线是哪一版"必须是单一来源，否则重连之后两处状态会各说各话。
 */
export class AuctionFeed {
  private state: FeedState = 'idle'
  private readonly applied = new Set<string>()
  private buffer: AuctionEventEnvelope[] = []
  private socket: SocketLike | null = null
  private retryHandle: unknown = null
  private resyncRetryHandle: unknown = null
  private resyncFailures = 0
  private attempt = 0
  private closed = false
  private resyncInFlight = false
  /** 权威基线：`-1` 表示还没有基线（此时除快照外的事件一律先缓存）。 */
  private baseline = -1
  private resyncCount = 0
  private resyncStreak = 0
  private reconnectCount = 0
  private detail: FeedStateDetail = {}
  private readonly backoff: readonly number[]

  constructor(
    private readonly deps: FeedDeps,
    private readonly handlers: FeedHandlers,
  ) {
    this.backoff = deps.backoffMs ?? DEFAULT_BACKOFF
  }

  get lastSeq(): number {
    return this.baseline
  }

  get feedState(): FeedState {
    return this.state
  }

  /** 观测数据：缺口恢复次数与重连次数（排障与测试用）。 */
  stats(): { resyncs: number; reconnects: number } {
    return { resyncs: this.resyncCount, reconnects: this.reconnectCount }
  }

  start(): void {
    this.closed = false
    this.attempt = 0
    this.setState('connecting', {})
    void this.openConnection()
  }

  stop(): void {
    this.closed = true
    this.cancelRetry()
    this.cancelResyncRetry()
    this.socket?.close()
    this.socket = null
    this.setState('closed', {})
  }

  private setState(state: FeedState, patch: FeedStateDetail): void {
    // 状态没变就把新字段并进旧详情：`retrying` 带的原因码不能被随后的一次
    // “第 N 次重连”覆盖掉，否则界面就分不清“票过期”和“网络抖动”了。
    if (state === this.state) this.detail = { ...this.detail, ...patch }
    else this.detail = { ...patch }
    this.state = state
    this.handlers.onState(state, this.detail)
  }

  private async openConnection(): Promise<void> {
    this.reconnectCount += 1
    try {
      const ticket = await this.deps.fetchTicket()
      if (this.closed) return
      this.socket = this.deps.createSocket(this.deps.url(ticket), {
        onOpen: () => {
          // 打开只是握手成功：真正“可用”要等第一帧快照把基线立起来。
        },
        onMessage: (data) => {
          const event = parseEvent(data)
          if (event) this.handle(event)
        },
        onClose: (code, reason) => {
          this.socket = null
          if (this.closed) return
          this.setState('retrying', { code: String(code), message: reason })
          this.scheduleRetry()
        },
        onError: (error) => this.handlers.onError?.(error),
      })
    } catch (error) {
      if (this.closed) return
      this.handlers.onError?.(error)
      this.setState('retrying', { message: '取实时票失败' })
      this.scheduleRetry()
    }
  }

  private scheduleRetry(): void {
    const delay = this.backoff[Math.min(this.attempt, this.backoff.length - 1)] ?? 10_000
    this.attempt += 1
    this.setState('retrying', { attempt: this.attempt })
    this.retryHandle = this.deps.schedule(() => {
      this.retryHandle = null
      // 重连必须重新建立基线：断线期间的版本变化只有快照能告诉我们。
      this.baseline = -1
      this.applied.clear()
      this.buffer = []
      this.resyncFailures = 0
      this.setState('connecting', {})
      void this.openConnection()
    }, delay)
  }

  private cancelRetry(): void {
    if (this.retryHandle !== null) {
      this.deps.cancelSchedule(this.retryHandle)
      this.retryHandle = null
    }
  }

  private handle(event: AuctionEventEnvelope): void {
    if (event.type === 'CONNECTION_STATE') {
      this.handleConnectionState(event)
      return
    }
    if (event.type === 'AUCTION_SNAPSHOT') {
      const snapshot = snapshotFromEvent(event)
      if (snapshot) this.applySnapshot(snapshot, 'socket')
      return
    }
    if (this.baseline < 0) {
      // 还没有基线：先留着重放，同时主动去拉一次快照（快照帧可能丢了）。
      this.buffer.push(event)
      this.requestResync()
      return
    }
    if (event.seq < this.baseline) {
      // 整批落后。丢弃它是**安全**的：每个事件都自带完成它所需的字段
      // （例如 BID_ACCEPTED 就带上了 endsAt/extensionCount），且随时能用快照兜底。
      return
    }
    if (event.seq === this.baseline) {
      if (this.applied.has(eventKey(event))) return // 重复到达的同一帧
      this.apply(event)
      return
    }
    if (event.seq > this.baseline + 1) {
      this.buffer.push(event)
      this.requestResync()
      return
    }
    this.apply(event)
  }

  private handleConnectionState(event: AuctionEventEnvelope): void {
    const connected = booleanField(event.payload, 'connected') ?? false
    if (!connected) {
      // 握手失败：服务端先发原因帧再关闭。原因码要带给界面，
      // 否则用户只会看到“连接断开”，分不清是票过期还是网络问题。
      this.setState('retrying', {
        code: stringField(event.payload, 'code'),
        message: stringField(event.payload, 'message'),
      })
      return
    }
    const snapshotSeq = numberField(event.payload, 'snapshotSeq')
    if (snapshotSeq !== undefined && snapshotSeq > this.baseline) this.baseline = snapshotSeq
    this.attempt = 0
    this.setState('live', {})
  }

  private apply(event: AuctionEventEnvelope): void {
    this.applied.add(eventKey(event))
    if (event.seq > this.baseline) this.baseline = event.seq
    this.handlers.onEvent(event)
  }

  private applySnapshot(snapshot: SnapshotPayload, source: 'socket' | 'http'): void {
    // 快照是权威基线：比它旧的缓存事件全部作废，比它新的按序补上。
    const carryOver = this.buffer.filter((event) => event.seq > snapshot.seq)
    this.buffer = []
    this.applied.clear()
    this.baseline = snapshot.seq
    this.handlers.onSnapshot(snapshot, source)
    for (const event of carryOver.sort((a, b) => a.seq - b.seq)) {
      if (event.type === 'AUCTION_SNAPSHOT') {
        const nested = snapshotFromEvent(event)
        if (nested) this.applySnapshot(nested, source)
        continue
      }
      this.handle(event)
    }
    // 补放之后还有洞，就只能继续拉快照；连续几次都补不上则不再纠缠。
    if (this.hasGap()) this.resyncStreak += 1
    else this.resyncStreak = 0
    this.setState(this.hasGap() ? 'resyncing' : this.state === 'retrying' ? 'retrying' : 'live', {})
    if (this.hasGap() && !this.resyncInFlight) this.requestResync()
  }

  /** 缓存里有没有“接不上基线”的事件。 */
  private hasGap(): boolean {
    return this.buffer.some((event) => event.seq > this.baseline + 1)
  }

  /** 缺口恢复：同一时刻只允许一次在飞的快照请求（避免旧响应覆盖新状态，契约 §6.4）。 */
  private requestResync(): void {
    if (this.resyncInFlight || this.closed) return
    if (this.resyncStreak >= MAX_RESYNC_STREAK) {
      // 连着几次快照都没能把洞补上：丢掉缓存、如实切回 live，而不是无限打接口。
      this.buffer = []
      this.resyncStreak = 0
      this.setState('live', { message: '快照落后于推送，已跳过过期事件' })
      return
    }
    this.resyncInFlight = true
    this.resyncCount += 1
    this.setState('resyncing', {})
    let applied = false
    void this.deps
      .fetchSnapshot()
      .then((snapshot) => {
        if (this.closed) return
        applied = true
        this.resyncFailures = 0
        this.cancelResyncRetry()
        this.applySnapshot(snapshot, 'http')
      })
      .catch((error: unknown) => {
        if (this.closed) return
        this.handlers.onError?.(error)
        // 快照拉不到就恢复不了基线，但**不要**因此把 socket 也扔掉：
        // 连接可能还好好的，只是这一次 HTTP 失败。按退避重试快照，
        // 期间到达的事件继续缓存，状态照实显示为“正在恢复”。
        this.resyncFailures += 1
        this.setState('resyncing', { message: '快照恢复失败，正在重试', attempt: this.resyncFailures })
        this.scheduleResyncRetry()
      })
      .finally(() => {
        this.resyncInFlight = false
        // HTTP 路径下 applySnapshot 里面的补拉会被“在飞”挡住，所以在这里再判定一次。
        // 只有**成功取到**快照才立刻再试：失败走带退避的 scheduleResyncRetry，
        // 否则一次网络抖动会变成连打接口。
        if (applied && !this.closed && this.hasGap()) this.requestResync()
      })
  }

  private scheduleResyncRetry(): void {
    if (this.resyncRetryHandle !== null) return
    const delay = this.backoff[Math.min(this.resyncFailures - 1, this.backoff.length - 1)] ?? 10_000
    this.resyncRetryHandle = this.deps.schedule(() => {
      this.resyncRetryHandle = null
      this.requestResync()
    }, delay)
  }

  private cancelResyncRetry(): void {
    if (this.resyncRetryHandle !== null) {
      this.deps.cancelSchedule(this.resyncRetryHandle)
      this.resyncRetryHandle = null
    }
  }
}

/** 把事件 payload 校验成快照。缺关键字段就当作没收到：宁可显示旧状态，不能显示半截状态。 */
export function snapshotFromEvent(event: AuctionEventEnvelope): SnapshotPayload | null {
  const payload = event.payload
  const id = stringField(payload, 'id')
  const title = stringField(payload, 'title')
  const status = stringField(payload, 'status')
  const seq = numberField(payload, 'seq')
  const serverTime = stringField(payload, 'serverTime')
  const currentPrice = numberField(payload, 'currentPrice')
  if (!id || !title || !status || seq === undefined || !serverTime || currentPrice === undefined) return null
  return {
    id,
    title,
    status: status as AuctionStatus,
    startPrice: numberField(payload, 'startPrice') ?? 0,
    minIncrement: numberField(payload, 'minIncrement') ?? 0,
    currentPrice,
    leaderAnon: stringField(payload, 'leader') ?? null,
    endsAt: stringField(payload, 'endsAt') ?? null,
    extensionCount: numberField(payload, 'extensionCount') ?? 0,
    participantCount: numberField(payload, 'participantCount') ?? 0,
    // 缺省 0 表示“没有博弈时间”：宁可什么都不提示，也不凭一个猜测的窗口去提示用户。
    finalGameWindowSeconds: numberField(payload, 'finalGameWindowSeconds') ?? 0,
    seq,
    serverTime,
  }
}

export type { AuctionEventEnvelope, AuctionEventType }
