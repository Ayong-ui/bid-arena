import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError, type ApiOk } from '../api/client'
import type { AuctionApi } from '../api/endpoints'
import { createMemoryStorage, createSessionStorage, type SessionStorage } from '../api/session'
import type {
  ApiCode,
  AuctionPage,
  AuctionResult,
  AuctionSnapshot,
  AuthData,
  BidPage,
  BidResult,
  LedgerPage,
  Participant,
  User,
  Wallet,
  WsTicket,
} from '../api/types'
import type { SocketHandlers, SocketLike } from '../realtime/socket'
import { setArenaDeps } from './deps'
import { useArenaStore } from './index'

/**
 * 商店的行为测试。
 *
 * 这里测的不是"界面长什么样"，而是**几条会真花钱的规则**：
 * 重试必须复用幂等键、401 必须清会话、结算前的结果查询 404 不是错误、倒计时必须用服务端时间。
 * 这些规则一旦违反，症状会是"用户以为没出价，其实出了两次"。
 */

const SERVER_TIME = '2026-01-01T00:00:00.000Z'
const SERVER_NOW = Date.parse(SERVER_TIME)
/** 实测过一次的时钟偏差：开发机比虚拟机快约 2 分 40 秒。 */
const SKEW_MS = 160_000

function ok<T>(data: T, code: ApiCode = 'OK'): ApiOk<T> {
  return { code, message: 'ok', requestId: 'req-1', data }
}

const USER: User = { id: 'usr_bidder_a', name: '竞拍者 A', role: 'BIDDER' }
const ADMIN: User = { id: 'usr_admin', name: '管理员', role: 'ADMIN' }

const SNAPSHOT: AuctionSnapshot = {
  id: 'auc-1',
  title: '胶片相机',
  description: '成色很好',
  status: 'RUNNING',
  startPrice: 100,
  minIncrement: 10,
  currentPrice: 120,
  leader: USER.id,
  endsAt: '2026-01-01T00:05:00.000Z',
  extensionCount: 0,
  participantCount: 1,
  seq: 3,
  serverTime: SERVER_TIME,
}

const WALLET: Wallet = { totalBalance: 1000, frozenAmount: 120, availableBalance: 880 }
const PAGE: AuctionPage = { items: [SNAPSHOT], page: 1, size: 50, total: 1 }
const LEDGER: LedgerPage = { items: [], page: 1, size: 20, total: 0 }
const BIDS: BidPage = { items: [], page: 1, size: 50, total: 0 }
const BID_RESULT: BidResult = {
  accepted: true,
  idempotent: false,
  price: 130,
  leader: 'anon-2952873c',
  extensions: 0,
  seq: 4,
  serverTime: SERVER_TIME,
}
const TICKET: WsTicket = {
  ticket: 't1',
  expiresAt: '2030-01-01T00:01:00.000Z',
  wsPath: '/ws/auctions/{auctionId}',
  wsPort: 18080,
}

interface FakeWorld {
  api: AuctionApi
  session: SessionStorage
  now: () => number
  /** 记录真正提交到服务端的出价（幂等键 + 金额）。 */
  bids: { requestId: string; amount: number }[]
  joined: string[]
  sockets: { url: string; handlers: SocketHandlers }[]
  snapshotCalls: () => number
  ticketCalls: () => number
  push(type: string, seq: number, payload?: Record<string, unknown>): void
  /** 模拟"连接建立后服务端推来的第一帧"：先给基线，后续事件才会被应用。 */
  connect(seq?: number): void
}

/**
 * 造一个"世界"：假 HTTP、假 socket、内存存储。
 *
 * `overrides` 拿到的是已经建好的 world，因此用例可以一边改造成员一边记录调用
 * （例如让第一次出价失败、第二次成功，并把两次的幂等键都记下来）。
 */
function makeWorld(overrides?: (world: FakeWorld) => Partial<AuctionApi>, now: () => number = () => SERVER_NOW): FakeWorld {
  const bids: FakeWorld['bids'] = []
  const joined: string[] = []
  const sockets: FakeWorld['sockets'] = []
  let snapshotCalls = 0
  let ticketCalls = 0

  const world: FakeWorld = {
    api: {} as AuctionApi,
    session: createSessionStorage(createMemoryStorage()),
    now,
    bids,
    joined,
    sockets,
    snapshotCalls: () => snapshotCalls,
    ticketCalls: () => ticketCalls,
    push: (type, seq, payload = {}) => {
      const last = sockets[sockets.length - 1]
      if (!last) throw new Error('还没有建立 socket')
      last.handlers.onMessage(JSON.stringify({ type, auctionId: 'auc-1', seq, serverTime: SERVER_TIME, payload }))
    },
    connect: (seq = SNAPSHOT.seq) => {
      world.push('CONNECTION_STATE', 0, { connected: true, snapshotSeq: seq })
      world.push('AUCTION_SNAPSHOT', seq, {
        id: 'auc-1',
        title: SNAPSHOT.title,
        status: SNAPSHOT.status,
        startPrice: SNAPSHOT.startPrice,
        minIncrement: SNAPSHOT.minIncrement,
        currentPrice: SNAPSHOT.currentPrice,
        extensionCount: 0,
        participantCount: 1,
        seq,
        serverTime: SERVER_TIME,
      })
    },
  }

  const base: AuctionApi = {
    login: async () => ok<AuthData>({ accessToken: 'token-1', expiresAt: '2030-01-01T01:00:00.000Z', user: USER }),
    currentUser: async () => ok(USER),
    wallet: async () => ok(WALLET),
    ledger: async () => ok(LEDGER),
    auctions: async () => ok(PAGE),
    auction: async () => {
      snapshotCalls += 1
      return ok(SNAPSHOT)
    },
    joinAuction: async (auctionId: string) => {
      joined.push(auctionId)
      return ok<Participant>({ auctionId, userId: USER.id, joinedAt: SERVER_TIME })
    },
    bids: async () => ok(BIDS),
    placeBid: async (_auctionId: string, body) => {
      bids.push({ requestId: body.requestId, amount: body.amount })
      return ok(BID_RESULT)
    },
    // 未结算时契约里就是 404。
    result: async () => {
      throw new ApiError({ code: 'NOT_FOUND', httpStatus: 404, serverMessage: '还没有结果' })
    },
    createAuction: async () => ok({ ...SNAPSHOT, status: 'DRAFT' as const }),
    startAuction: async () => ok(SNAPSHOT),
    cancelAuction: async () => ok({ ...SNAPSHOT, status: 'CANCELLED' as const }),
    wsTicket: async () => {
      ticketCalls += 1
      return ok<WsTicket>({ ...TICKET, ticket: `t${ticketCalls}` })
    },
  }
  world.api = { ...base, ...(overrides?.(world) ?? {}) }
  installDeps(world)
  return world
}

/** 把 world 接到商店上。**传引用**：用例随后改 `world.api.xxx` 时商店能立刻看到。 */
function installDeps(world: FakeWorld): void {
  setArenaDeps({
    api: world.api,
    session: world.session,
    now: world.now,
    createSocket: (url, handlers) => {
      const socket: SocketLike = { close: () => handlers.onClose(1000, 'closed') }
      world.sockets.push({ url, handlers })
      return socket
    },
  })
}

/**
 * 等异步链路跑完。
 *
 * 不能只 `setTimeout(0)`：缺口恢复那段要真的 await 一次 HTTP 取快照 + 一次 `crypto.subtle` 摘要，
 * 后者在线程池里跑，可能比一个宏任务更慢。等太短会让测试变成"偶尔红"。
 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 20))

async function signedIn(world: FakeWorld, user: User = USER) {
  world.api.login = async () => ok<AuthData>({ accessToken: 'token-1', expiresAt: '2030-01-01T01:00:00.000Z', user })
  installDeps(world)
  const store = useArenaStore()
  expect(await store.login({ email: `${user.id}@example.com`, password: 'Test123456!' })).toBe(true)
  return store
}

beforeEach(() => {
  setActivePinia(createPinia())
  setArenaDeps(null)
})

describe('商店：登录与身份', () => {
  it('登录后把令牌写进存储、拉列表与钱包，并算出自己的匿名标识', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    expect(store.loggedIn).toBe(true)
    expect(store.currentUser?.id).toBe(USER.id)
    expect(world.session.read()?.accessToken).toBe('token-1')
    expect(store.auctions).toHaveLength(1)
    expect(store.wallet?.availableBalance).toBe(880)
    // 与服务端同一算法：usr_bidder_a → anon-2952873c（vectors 固定在 anonymous.test.ts）。
    expect(store.myAnonId).toBe('anon-2952873c')
    expect(store.isAdmin).toBe(false)
  })

  it('管理员角色来自服务端字段，不靠前端猜', async () => {
    const world = makeWorld()
    const store = await signedIn(world, ADMIN)
    expect(store.isAdmin).toBe(true)
  })

  it('登录失败按错误码提示，且不会留下半个会话', async () => {
    const world = makeWorld()
    world.api.login = async () => {
      throw new ApiError({ code: 'UNAUTHENTICATED', httpStatus: 401, serverMessage: '邮箱或密码不正确' })
    }
    installDeps(world)
    const store = useArenaStore()
    expect(await store.login({ email: 'x@example.com', password: 'bad' })).toBe(false)
    expect(store.loggedIn).toBe(false)
    expect(world.session.read()).toBeNull()
    expect(store.notice?.text).toContain('邮箱或密码不正确')
  })

  it('刷新页面用存储里的会话恢复', async () => {
    const world = makeWorld()
    await signedIn(world)
    // 模拟刷新：新的 pinia、新的 store 实例，但存储里还有令牌。
    setActivePinia(createPinia())
    const restored = useArenaStore()
    await restored.restore()
    expect(restored.loggedIn).toBe(true)
    expect(restored.currentUser?.name).toBe(USER.name)
    expect(restored.wallet?.totalBalance).toBe(1000)
  })

  it('接口返回 401 时清掉会话并说明原因', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    world.api.auctions = async () => {
      throw new ApiError({ code: 'UNAUTHENTICATED', httpStatus: 401 })
    }
    await store.refreshAuctions()
    expect(store.loggedIn).toBe(false)
    expect(world.session.read()).toBeNull()
    expect(store.notice?.text).toContain('登录已过期')
  })
})

describe('商店：实时订阅', () => {
  it('打开详情用 HTTP 快照建立基线，随后由事件驱动价格、领先者与延时次数', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    expect(world.snapshotCalls()).toBe(1)
    expect(world.ticketCalls()).toBe(1)
    // 路径模板必须被替换：漏掉这一步的表现是握手 404（DBG-23）。
    expect(world.sockets[0].url).toBe('ws://localhost:18080/ws/auctions/auc-1?ticket=t1')
    expect(store.current?.description).toBe('成色很好')
    expect(store.current?.leaderAnon).toBe('anon-2952873c')

    world.connect()
    expect(store.feedState).toBe('live')

    world.push('BID_ACCEPTED', 4, {
      price: 150,
      leader: 'anon-bbbbbbbb',
      endsAt: '2026-01-01T00:05:05.000Z',
      extensionCount: 1,
    })
    expect(store.current?.currentPrice).toBe(150)
    expect(store.current?.leaderAnon).toBe('anon-bbbbbbbb')
    expect(store.isMyLead).toBe(false)
    expect(store.current?.endsAt).toBe('2026-01-01T00:05:05.000Z')

    // 同一次提交的 AUCTION_EXTENDED 与上面共享 seq，必须也被应用。
    world.push('AUCTION_EXTENDED', 4, { endsAt: '2026-01-01T00:05:10.000Z', extensionCount: 2 })
    expect(store.current?.extensionCount).toBe(2)
    expect(store.current?.endsAt).toBe('2026-01-01T00:05:10.000Z')

    // 重复帧被去重：不再有机会把价格写回旧值。
    world.push('BID_ACCEPTED', 4, { price: 999, leader: 'anon-cccccccc' })
    expect(store.current?.currentPrice).toBe(150)
  })

  it('倒计时用服务端时间校准：本机时钟差了几个小时也不会把进行中的拍卖算成已结束', async () => {
    // 本机真实时间与 SERVER_TIME 的差距（开发机与虚拟机时钟不同步是常态，实测见过 2 分 40 秒）。
    expect(Math.abs(Date.now() - SERVER_NOW)).toBeGreaterThan(SKEW_MS)
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    // endsAt 距服务端现在 5 分钟。若用本机时间，这里会算出负数、界面显示 00:00。
    expect(store.remainingMs).toBe(300_000)
    expect(store.remainingLabel).toBe('05:00')
  })

  it('拍卖结束事件触发结算结果与钱包刷新', async () => {
    const result: AuctionResult = {
      auctionId: 'auc-1',
      status: 'FINISHED',
      winner: USER.id,
      finalPrice: 150,
      reason: 'TIMEOUT',
      settledAt: SERVER_TIME,
    }
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    world.connect()
    world.api.result = async () => ok(result)
    world.push('AUCTION_FINISHED', 4, { status: 'FINISHED', winner: 'anon-2952873c', finalPrice: 150 })
    await flush()
    expect(store.current?.status).toBe('FINISHED')
    expect(store.settlement?.finalPrice).toBe(150)
  })

  it('未结算时的 404 不是错误：结果为空但界面不报错', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    expect(store.settlement).toBeNull()
    expect(store.notice).toBeNull()
  })

  it('自己那笔出价被拒时，按事件里的错误码提示', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    world.connect()
    // 被拒的出价不推进版本号：这个事件的 seq 就是当前 seq。
    world.push('BID_REJECTED', 3, { code: 'BID_TOO_LOW', reason: '低于当前价' })
    await flush()
    expect(store.notice?.kind).toBe('error')
    expect(store.notice?.text).toContain('BID_TOO_LOW')
  })

  it('关闭详情会断开订阅，之后到达的事件不会再改状态', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    const handlers = world.sockets[0].handlers
    store.closeAuction()
    expect(store.current).toBeNull()
    expect(store.feedState).toBe('idle')
    handlers.onClose(1006, 'late')
    expect(store.feedState).toBe('idle')
  })
})

describe('商店：出价', () => {
  it('出价成功后价格取自服务端结果，并刷新钱包与出价记录', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    expect(store.joined).toBe(false)
    expect(store.nextBid).toBe(130)
    expect(store.canBid).toBe(false)

    expect(await store.placeBid(130)).toBe(true)
    expect(world.bids).toHaveLength(1)
    expect(world.bids[0].amount).toBe(130)
    expect(world.bids[0].requestId).toMatch(/^web-/)
    expect(store.current?.currentPrice).toBe(130)
    expect(store.isMyLead).toBe(true)
    expect(store.joined).toBe(true)
    expect(store.canBid).toBe(true)
    expect(store.notice?.text).toContain('领先')
  })

  it('网络失败重试复用同一个幂等键；改了金额才换新键', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    let failing = true
    // 记录**每一次**提交尝试（含失败那次）：只统计成功的出价数验证不了
    // “重试复用了同一个幂等键”——失败那次的键没有任何地方会留下痕迹。
    const attempts: string[] = []
    world.api.placeBid = async (_auctionId, body) => {
      attempts.push(body.requestId)
      if (failing) throw new ApiError({ code: 'NETWORK', serverMessage: '请求超时' })
      world.bids.push({ requestId: body.requestId, amount: body.amount })
      return ok(BID_RESULT)
    }

    expect(await store.placeBid(130)).toBe(false)
    expect(store.notice?.text).toContain('沿用同一次出价')
    failing = false
    expect(await store.placeBid(130)).toBe(true)
    expect(world.bids).toHaveLength(1)
    // 重试必须沿用失败那一次的幂等键，否则服务端会当成两次出价。
    expect(attempts).toHaveLength(2)
    expect(attempts[1]).toBe(attempts[0])
    expect(attempts[1]).toBe(world.bids[0].requestId)

    // 换金额 = 新命令 = 新幂等键（旧键已被服务端记成一次成功出价）。
    expect(await store.placeBid(140)).toBe(true)
    expect(world.bids[1].requestId).not.toBe(world.bids[0].requestId)
  })

  it('服务端说没加入时自动加入，并用同一个幂等键重试一次', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    let attempts = 0
    world.api.placeBid = async (_auctionId, body) => {
      attempts += 1
      if (attempts === 1) throw new ApiError({ code: 'NOT_JOINED', httpStatus: 409, serverMessage: '尚未加入' })
      world.bids.push({ requestId: body.requestId, amount: body.amount })
      return ok(BID_RESULT)
    }

    expect(await store.placeBid(130)).toBe(true)
    expect(world.joined).toEqual(['auc-1'])
    expect(attempts).toBe(2)
    // 重试用的是同一个键：否则服务端会看到两次出价。
    expect(world.bids).toHaveLength(1)
    expect(store.joined).toBe(true)
  })

  it('BID_TOO_LOW 之后不复用幂等键，并把服务端给的下限提示出来', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    const seen: string[] = []
    let first = true
    world.api.placeBid = async (_auctionId, body) => {
      seen.push(body.requestId)
      if (first) {
        first = false
        throw new ApiError({
          code: 'BID_TOO_LOW',
          httpStatus: 409,
          serverMessage: '出价过低',
          details: { minimum: 200 },
        })
      }
      world.bids.push({ requestId: body.requestId, amount: body.amount })
      return ok(BID_RESULT)
    }

    expect(await store.placeBid(130)).toBe(false)
    expect(store.notice?.text).toContain('200')
    expect(await store.placeBid(200)).toBe(true)
    expect(seen[0]).not.toBe(seen[1])
  })

  it('加入本场拍卖是显式动作：join 之前 canBid 为 false', async () => {
    const world = makeWorld()
    const store = await signedIn(world)
    await store.openAuction('auc-1')
    await store.joinCurrent()
    expect(world.joined).toEqual(['auc-1'])
    expect(store.joined).toBe(true)
    expect(store.canBid).toBe(true)
  })
})

describe('商店：运营台', () => {
  it('创建拍品后刷新列表并给出可操作提示', async () => {
    const world = makeWorld()
    const store = await signedIn(world, ADMIN)
    let created: unknown = null
    world.api.createAuction = async (body) => {
      created = body
      return ok({ ...SNAPSHOT, status: 'DRAFT' as const })
    }
    expect(await store.createAuction({ title: '新拍品', startPrice: 50, minIncrement: 5, durationSeconds: 60 })).toBe(true)
    expect(created).toMatchObject({ title: '新拍品', durationSeconds: 60 })
    expect(store.notice?.text).toContain('已创建')
  })

  it('开始拍卖后列表里的状态跟着服务端更新', async () => {
    const world = makeWorld()
    const store = await signedIn(world, ADMIN)
    world.api.auctions = async () => ok({ ...PAGE, items: [{ ...SNAPSHOT, status: 'DRAFT' as const }] })
    await store.refreshAuctions()
    expect(store.auctions[0].status).toBe('DRAFT')

    world.api.startAuction = async () => ok(SNAPSHOT)
    world.api.auctions = async () => ok(PAGE)
    await store.startAuction('auc-1')
    expect(store.auctions[0].status).toBe('RUNNING')
    expect(store.notice?.text).toContain('已开始')
  })
})
