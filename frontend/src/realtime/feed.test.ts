import { describe, expect, it } from 'vitest'
import type { WsTicket } from '../api/types'
import { AuctionFeed, type FeedDeps, type FeedHandlers, type FeedStateDetail, type SnapshotPayload } from './feed'
import type { FeedState, SocketHandlers, SocketLike } from './socket'

/**
 * 序号与快照恢复的测试。
 *
 * 这些用例覆盖的是**契约里最容易出错的部分**：一次提交会发多个同 `seq` 的事件，
 * 断线期间的版本变化只有快照能补，重连必须换票。
 * 用假 socket 把这些帧顺序摆出来，比对着真服务端碰运气可靠。
 */

const TICKET: WsTicket = {
  ticket: 't1',
  expiresAt: '2026-01-01T00:00:00.000Z',
  wsPath: '/ws/auctions/{auctionId}',
  wsPort: 18080,
}

interface FakeSocket extends SocketLike {
  url: string
  handlers: SocketHandlers
  closed: boolean
}

interface Harness {
  feed: AuctionFeed
  sockets: FakeSocket[]
  events: string[]
  snapshots: { snapshot: SnapshotPayload; source: 'socket' | 'http' }[]
  states: { state: FeedState; detail: FeedStateDetail }[]
  timers: { callback: () => void; delayMs: number; cancelled: boolean }[]
  /** 推一帧事件（会自动按 ws 信封格式序列化）。 */
  push(type: string, seq: number, payload?: Record<string, unknown>): void
  /** 推一帧原始文本（用于测试坏帧）。 */
  pushRaw(raw: string): void
  fireTimers(): void
  latest(): FakeSocket
  ticket(): WsTicket | undefined
}

function snapshotEvent(seq: number, payload: Record<string, unknown> = {}): string {
  return JSON.stringify({
    type: 'AUCTION_SNAPSHOT',
    auctionId: 'auc-1',
    seq,
    serverTime: '2026-01-01T00:00:00.000Z',
    payload: {
      id: 'auc-1',
      title: '相机',
      status: 'RUNNING',
      startPrice: 100,
      minIncrement: 10,
      currentPrice: 120,
      extensionCount: 0,
      participantCount: 1,
      seq,
      serverTime: '2026-01-01T00:00:00.000Z',
      ...payload,
    },
  })
}

function makeHarness(overrides: Partial<FeedDeps> = {}, handlers: Partial<FeedHandlers> = {}): Harness {
  const sockets: FakeSocket[] = []
  const events: string[] = []
  const snapshots: Harness['snapshots'] = []
  const states: Harness['states'] = []
  const timers: Harness['timers'] = []
  const tickets: WsTicket[] = []

  const deps: FeedDeps = {
    fetchTicket: async () => {
      const next = { ...TICKET, ticket: `t${tickets.length + 1}` }
      tickets.push(next)
      return next
    },
    fetchSnapshot: async () => snapshotPayload(0),
    createSocket: (url, socketHandlers) => {
      const socket: FakeSocket = {
        url,
        handlers: socketHandlers,
        closed: false,
        close() {
          socket.closed = true
          socketHandlers.onClose(1000, 'client closed')
        },
      }
      sockets.push(socket)
      return socket
    },
    url: (ticket) => `ws://localhost:${ticket.wsPort}${ticket.wsPath}?ticket=${ticket.ticket}`,
    schedule: (callback, delayMs) => {
      const timer = { callback, delayMs, cancelled: false }
      timers.push(timer)
      return timer
    },
    cancelSchedule: (handle) => {
      ;(handle as { cancelled: boolean }).cancelled = true
    },
    ...overrides,
  }

  const feed = new AuctionFeed(deps, {
    onSnapshot: (snapshot, source) => snapshots.push({ snapshot, source }),
    onEvent: (event) => events.push(`${event.type}@${event.seq}`),
    onState: (state, detail) => states.push({ state, detail }),
    ...handlers,
  })

  const latest = (): FakeSocket => {
    const socket = sockets[sockets.length - 1]
    if (!socket) throw new Error('还没有建立连接')
    return socket
  }

  return {
    feed,
    sockets,
    events,
    snapshots,
    states,
    timers,
    push: (type, seq, payload = {}) =>
      latest().handlers.onMessage(
        JSON.stringify({ type, auctionId: 'auc-1', seq, serverTime: '2026-01-01T00:00:00.000Z', payload }),
      ),
    pushRaw: (raw) => latest().handlers.onMessage(raw),
    fireTimers: () => {
      for (const timer of timers.splice(0)) if (!timer.cancelled) timer.callback()
    },
    latest,
    ticket: () => tickets[tickets.length - 1],
  }
}

function snapshotPayload(seq: number, overrides: Partial<SnapshotPayload> = {}): SnapshotPayload {
  return {
    id: 'auc-1',
    title: '相机',
    status: 'RUNNING',
    startPrice: 100,
    minIncrement: 10,
    currentPrice: 120 + seq,
    leaderAnon: 'anon-aaaaaaaa',
    endsAt: '2026-01-01T00:05:00.000Z',
    extensionCount: 0,
    participantCount: 1,
    seq,
    finalGameWindowSeconds: 20,
    serverTime: '2026-01-01T00:00:00.000Z',
    ...overrides,
  }
}

/** 让 `await` 之后的微任务先跑完（取票、建连、快照请求都是 promise）。 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('AuctionFeed', () => {
  it('一次提交的多个事件共享同一个 seq，必须按 (seq, type) 去重', async () => {
    const h = makeHarness()
    h.feed.start()
    await flush()
    h.pushRaw(snapshotEvent(3, { currentPrice: 130 }))
    expect(h.feed.lastSeq).toBe(3)

    // 最后 5 秒内的出价会同时产生 BID_ACCEPTED 与 AUCTION_EXTENDED，两者 seq 相同。
    h.push('BID_ACCEPTED', 4, { price: 140, leader: 'anon-bbbbbbbb', extensionCount: 1 })
    h.push('AUCTION_EXTENDED', 4, { endsAt: '2026-01-01T00:05:05.000Z', extensionCount: 1 })
    expect(h.events).toEqual(['BID_ACCEPTED@4', 'AUCTION_EXTENDED@4'])

    // 重连/重放时同样两帧会再来一次，这一次必须被丢掉——按 seq 去重会误杀 AUCTION_EXTENDED。
    h.push('BID_ACCEPTED', 4, { price: 140, leader: 'anon-bbbbbbbb', extensionCount: 1 })
    h.push('AUCTION_EXTENDED', 4, { endsAt: '2026-01-01T00:05:05.000Z', extensionCount: 1 })
    expect(h.events).toEqual(['BID_ACCEPTED@4', 'AUCTION_EXTENDED@4'])
    expect(h.feed.lastSeq).toBe(4)
    expect(h.feed.feedState).toBe('live')
  })

  it('发现缺口时拉 HTTP 快照，丢弃比快照旧的事件、补上比它新的', async () => {
    let snapshotCalls = 0
    const h = makeHarness({
      fetchSnapshot: async () => {
        snapshotCalls += 1
        return snapshotPayload(6, { currentPrice: 200 })
      },
    })
    h.feed.start()
    await flush()
    h.pushRaw(snapshotEvent(3))
    expect(h.feed.lastSeq).toBe(3)

    // seq 5 丢了，直接来了 6：不能"跳着应用"，否则本地会缺一次出价。
    h.push('BID_ACCEPTED', 6, { price: 200 })
    await flush()
    expect(snapshotCalls).toBe(1)
    // 第一份是连接时服务端推的快照（seq 3），第二份才是缺口恢复拉的。
    expect(h.snapshots.map((item) => item.source)).toEqual(['socket', 'http'])
    expect(h.snapshots[1].snapshot.seq).toBe(6)
    // 快照已经包含 seq 6 的状态，缓存里那帧 6 不该再叠一次。
    expect(h.events).toEqual([])
    expect(h.feed.lastSeq).toBe(6)

    // 恢复期间到达的更新事件（seq 7）要在快照之后补上。
    h.push('PARTICIPANT_JOINED', 7, { participant: 'anon-cccccccc', participantCount: 2 })
    expect(h.events).toEqual(['PARTICIPANT_JOINED@7'])
  })

  it('缺口恢复期间到达的更新事件按序补放，不会因为快照晚到而丢失', async () => {
    // 放进对象里而不是普通变量：TS 的控制流分析看不到回调里的赋值，
    // 会把它窄化成 null 并拒绝调用（运行时其实没问题，但类型检查也得过）。
    const pending: { resolve?: (snapshot: SnapshotPayload) => void } = {}
    const h = makeHarness({
      fetchSnapshot: () =>
        new Promise<SnapshotPayload>((resolve) => {
          pending.resolve = resolve
        }),
    })
    h.feed.start()
    await flush()
    h.pushRaw(snapshotEvent(3))
    h.push('BID_ACCEPTED', 5, { price: 150 })
    await flush()
    expect(h.feed.feedState).toBe('resyncing')

    // 快照还没回来，新的出价先到了：它比快照新，必须活下来。
    h.push('BID_ACCEPTED', 6, { price: 160 })
    expect(h.events).toEqual([])

    pending.resolve?.(snapshotPayload(4))
    await flush()
    expect(h.feed.lastSeq).toBe(6)
    expect(h.events).toEqual(['BID_ACCEPTED@5', 'BID_ACCEPTED@6'])
  })

  it('比基线旧的事件直接丢弃：事件自带全量字段，快照随时能兜底', async () => {
    const h = makeHarness()
    h.feed.start()
    await flush()
    h.pushRaw(snapshotEvent(9))
    h.push('BID_ACCEPTED', 8, { price: 90 })
    h.push('PARTICIPANT_JOINED', 7, { participantCount: 3 })
    expect(h.events).toEqual([])
    expect(h.feed.lastSeq).toBe(9)
    // 丢弃不等于"缺口"：不该因此去拉快照（只有连接时那一份）。
    expect(h.snapshots).toHaveLength(1)
  })

  it('还没有基线时先缓存事件并主动拉一次快照（快照帧可能丢）', async () => {
    // 连接后 AUCTION_SNAPSHOT 帧丢失，而增量先到了：此时不能凭 seq 直接应用。
    const h = makeHarness({ fetchSnapshot: async () => snapshotPayload(4, { currentPrice: 140 }) })
    h.feed.start()
    await flush()
    h.push('BID_ACCEPTED', 4, { price: 140 })
    await flush()
    expect(h.snapshots.map((item) => item.source)).toEqual(['http'])
    expect(h.snapshots[0].snapshot.seq).toBe(4)
    // 快照已经是 seq 4，缓存里那帧 4 被覆盖掉，避免把同一次出价算两遍。
    expect(h.events).toEqual([])
    expect(h.feed.lastSeq).toBe(4)
    expect(h.feed.feedState).toBe('live')
  })

  it('快照连续落后于推送时，有限次重试后丢弃缓存并如实切回 live', async () => {
    // 服务端快照始终停在 seq 0，而事件已经推到 seq 4：这是服务端不一致，不该无限重试。
    const h = makeHarness({ fetchSnapshot: async () => snapshotPayload(0) })
    h.feed.start()
    await flush()
    h.push('BID_ACCEPTED', 4, { price: 140 })
    for (let i = 0; i < 6; i += 1) {
      await flush()
      h.fireTimers()
    }
    await flush()
    expect(h.events).toEqual([])
    expect(h.feed.feedState).toBe('live')
    expect(h.states[h.states.length - 1].detail.message).toBe('快照落后于推送，已跳过过期事件')
    // 尝试次数有上限：不会一直打接口。
    expect(h.snapshots.length).toBeLessThanOrEqual(6)
  })

  it('重连要换一张新票，并重置基线（断线期间的版本只能靠快照补）', async () => {
    const h = makeHarness()
    h.feed.start()
    await flush()
    const firstTicket = h.ticket()?.ticket
    h.pushRaw(snapshotEvent(9))
    expect(h.feed.lastSeq).toBe(9)

    h.latest().handlers.onClose(1006, 'abnormal')
    expect(h.feed.feedState).toBe('retrying')
    // 关闭原因与"第 N 次重连"必须同时可见：前者说明为什么断，后者说明还要等多久。
    expect(h.states[h.states.length - 1].detail).toMatchObject({ code: '1006', attempt: 1 })
    expect(h.timers).toHaveLength(1)
    expect(h.timers[0].delayMs).toBe(1_000)

    h.fireTimers()
    await flush()
    expect(h.sockets).toHaveLength(2)
    expect(h.ticket()?.ticket).not.toBe(firstTicket)
    // 新连接上先来一帧"旧基线"的事件：此时还没有新基线，只能缓存 + 拉快照。
    h.push('BID_ACCEPTED', 5, { price: 150 })
    await flush()
    h.pushRaw(snapshotEvent(11))
    expect(h.feed.lastSeq).toBe(11)
  })

  it('退避重连逐次加长，并在上限处封顶', async () => {
    const h = makeHarness()
    h.feed.start()
    await flush()
    const delays: number[] = []
    for (let i = 0; i < 7; i += 1) {
      h.latest().handlers.onClose(1006, 'boom')
      delays.push(h.timers[h.timers.length - 1].delayMs)
      h.fireTimers()
      await flush()
    }
    expect(delays).toEqual([1_000, 2_000, 4_000, 8_000, 10_000, 10_000, 10_000])
  })

  it('拉快照失败时保留连接、退避重试，并保留失败原因供界面展示', async () => {
    let calls = 0
    const h = makeHarness({
      fetchSnapshot: async () => {
        calls += 1
        if (calls === 1) throw new Error('502')
        return snapshotPayload(6)
      },
    })
    h.feed.start()
    await flush()
    h.push('BID_ACCEPTED', 4, { price: 140 })
    await flush()
    expect(calls).toBe(1)
    expect(h.feed.feedState).toBe('resyncing')
    expect(h.states[h.states.length - 1].detail.message).toBe('快照恢复失败，正在重试')
    // 连接还在，不该被当成“断开”去重新取票。
    expect(h.sockets).toHaveLength(1)
    expect(h.timers[h.timers.length - 1].delayMs).toBe(1_000)

    h.fireTimers()
    await flush()
    expect(calls).toBe(2)
    expect(h.feed.lastSeq).toBe(6)
    expect(h.feed.feedState).toBe('live')
  })

  it('服务端拒绝握手时把原因码透出给界面，而不是只说"连接断开"', async () => {
    const h = makeHarness()
    h.feed.start()
    await flush()
    h.latest().handlers.onMessage(
      JSON.stringify({
        type: 'CONNECTION_STATE',
        auctionId: 'auc-1',
        seq: 0,
        serverTime: '2026-01-01T00:00:00.000Z',
        payload: { connected: false, code: 'UNAUTHENTICATED', message: '入场券无效' },
      }),
    )
    expect(h.feed.feedState).toBe('retrying')
    expect(h.states[h.states.length - 1].detail).toMatchObject({ code: 'UNAUTHENTICATED', message: '入场券无效' })
  })

  it('坏帧与未知事件类型被忽略，不影响后续帧', async () => {
    const h = makeHarness()
    h.feed.start()
    await flush()
    h.pushRaw('{ 这不是 json')
    h.pushRaw(JSON.stringify({ type: 'SOMETHING_NEW', auctionId: 'auc-1', seq: 2, serverTime: 'now', payload: {} }))
    h.pushRaw(snapshotEvent(3))
    expect(h.feed.lastSeq).toBe(3)
    h.push('BID_ACCEPTED', 4, { price: 140 })
    expect(h.events).toEqual(['BID_ACCEPTED@4'])
  })

  it('stop() 之后不再重连，也不会再触发快照请求', async () => {
    const h = makeHarness()
    h.feed.start()
    await flush()
    const before = h.sockets.length
    h.feed.stop()
    expect(h.feed.feedState).toBe('closed')
    h.latest().handlers.onClose(1006, 'late')
    h.push('BID_ACCEPTED', 40, { price: 999 })
    h.fireTimers()
    await flush()
    expect(h.sockets).toHaveLength(before)
    expect(h.events).toEqual([])
    expect(h.feed.feedState).toBe('closed')
  })

  it('取票失败会重试，而不是把订阅停在半路', async () => {
    let attempts = 0
    const h = makeHarness({
      fetchTicket: async () => {
        attempts += 1
        if (attempts === 1) throw new Error('网络不可用')
        return { ...TICKET, ticket: `t${attempts}` }
      },
    })
    h.feed.start()
    await flush()
    expect(h.feed.feedState).toBe('retrying')
    expect(h.sockets).toHaveLength(0)
    h.fireTimers()
    await flush()
    expect(h.sockets).toHaveLength(1)
  })

  it('统计缺口恢复与重连次数，便于排障', async () => {
    const h = makeHarness({ fetchSnapshot: async () => snapshotPayload(9) })
    h.feed.start()
    await flush()
    h.pushRaw(snapshotEvent(3))
    h.push('BID_ACCEPTED', 9, { price: 300 })
    await flush()
    h.latest().handlers.onClose(1006, 'boom')
    h.fireTimers()
    await flush()
    expect(h.feed.stats()).toEqual({ resyncs: 1, reconnects: 2 })
  })
})
