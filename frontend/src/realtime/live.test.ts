import { describe, expect, it } from 'vitest'
import { anonymousId } from '../anonymous'
import { createApiClient } from '../api/client'
import { createAuctionApi, type AuctionApi } from '../api/endpoints'
import { expectApiFailure } from '../testing/apiFailure'
import { parseEvent, type AuctionEventEnvelope } from './events'
import { createBrowserSocket, socketUrl } from './socket'

/**
 * 真实 WebSocket 联调（默认跳过）。
 *
 * 假的 socket 能证明"按 seq 去重、缺口补快照"这套算法对，
 * 但证明不了**服务端真的按契约推这些帧**：事件类型名、payload 字段、可见性边界（不带原始 user_id）、
 * seq 的推进方式，全都只能对着真服务端验。这条测试就是那一半。
 *
 * 运行方式（先启动后端，见 README 快速启动）：
 *   BID_ARENA_LIVE=1 BID_ARENA_LIVE_API=http://localhost:8080/api/v1 \
 *   BID_ARENA_DEMO_ADMIN_EMAIL=... BID_ARENA_DEMO_ADMIN_PASSWORD=... \
 *   BID_ARENA_DEMO_BIDDER_EMAIL=... BID_ARENA_DEMO_BIDDER_PASSWORD=... \
 *   npx vitest run src/realtime/live.test.ts
 */
const live = process.env['BID_ARENA_LIVE'] === '1'
const baseUrl = process.env['BID_ARENA_LIVE_API'] ?? 'http://localhost:8080/api/v1'
const adminEmail = process.env['BID_ARENA_DEMO_ADMIN_EMAIL'] ?? ''
const adminPassword = process.env['BID_ARENA_DEMO_ADMIN_PASSWORD'] ?? ''
const bidderEmail = process.env['BID_ARENA_DEMO_BIDDER_EMAIL'] ?? ''
const bidderPassword = process.env['BID_ARENA_DEMO_BIDDER_PASSWORD'] ?? ''

function clientFor(token: string | null = null) {
  return createApiClient({ baseUrl, token: () => token, timeoutMs: 15_000 })
}

/** 轮询等待某个条件成立；超时抛出带上下文的错误（否则只会看到一句"测试超时"）。 */
async function waitFor<T>(describe: string, probe: () => T | undefined, timeoutMs = 10_000): Promise<T> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const value = probe()
    if (value !== undefined) return value
    if (Date.now() > deadline) throw new Error(`等待超时：${describe}`)
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
}

describe.skipIf(!live)('真实后端联调：WebSocket 事件流', () => {
  it('出价 → 收到 BID_ACCEPTED；被拒 → 收到 BID_REJECTED；帧里不含原始 user_id', async () => {
    const anonymousApi = createAuctionApi(clientFor())
    const adminLogin = await anonymousApi.login({ email: adminEmail, password: adminPassword })
    const adminApi: AuctionApi = createAuctionApi(clientFor(adminLogin.data.accessToken))
    const bidderLogin = await anonymousApi.login({ email: bidderEmail, password: bidderPassword })
    const bidderApi = createAuctionApi(clientFor(bidderLogin.data.accessToken))

    // 创建放在 try 外面：清理用的 auctionId 必须是常量（见 DBG-21）。
    const created = await adminApi.createAuction({
      title: `实时联调 ${new Date().toISOString()}`,
      description: '由 WS 联调脚本创建，结束时会被取消',
      startPrice: 100,
      minIncrement: 10,
      durationSeconds: 300,
    })
    const auctionId = created.data.id
    let socket: { close(): void } | null = null

    try {
      const started = await adminApi.startAuction(auctionId)
      await bidderApi.joinAuction(auctionId)
      // 加入本身也是一次状态变更（会推进 seq），所以基线要跟"加入之后"的 HTTP 快照对齐。
      const beforeSocket = (await bidderApi.auction(auctionId)).data
      expect(beforeSocket.seq).toBeGreaterThan(started.data.seq)

      // 1. 取一次性票并连接。票是单次使用的，所以只在这里取一次。
      const ticket = (await bidderApi.wsTicket()).data
      expect(ticket.wsPort).toBeGreaterThan(0)
      const events: AuctionEventEnvelope[] = []
      const rawFrames: string[] = []
      const connected = { value: false, error: '' }
      socket = createBrowserSocket(socketUrl(ticket, auctionId), {
        onOpen: () => {
          connected.value = true
        },
        onMessage: (data) => {
          rawFrames.push(data)
          const event = parseEvent(data)
          if (event) events.push(event)
        },
        onClose: (code, reason) => {
          connected.error = `close ${code} ${reason}`
        },
        onError: () => {
          connected.error = 'socket error'
        },
      })

      // 2. 连接建立后服务端先给权威快照：基线必须与 HTTP 快照的 seq 一致。
      const snapshot = await waitFor('连接建立后的 AUCTION_SNAPSHOT 帧', () =>
        events.find((event) => event.type === 'AUCTION_SNAPSHOT'),
      )
      expect(snapshot.auctionId).toBe(auctionId)
      expect(snapshot.payload['seq']).toBe(beforeSocket.seq)
      expect(snapshot.payload['currentPrice']).toBe(beforeSocket.currentPrice)
      expect(snapshot.payload['participantCount']).toBeGreaterThanOrEqual(1)
      // 客户端发往 WS 的消息一律被忽略，因此这一层连 send 都没有——这里只断言能收到帧。
      expect(connected.value).toBe(true)
      expect(connected.error).toBe('')

      // 3. 通过 HTTP 出价，事件应当从 WS 推回来（命令入口只有一个，但事件是广播的）。
      const amount = beforeSocket.currentPrice + beforeSocket.minIncrement
      const placed = await bidderApi.placeBid(auctionId, { requestId: `req-ws-${Date.now()}`, amount })
      expect(placed.data.accepted).toBe(true)
      const accepted = await waitFor('BID_ACCEPTED 帧', () =>
        events.find((event) => event.type === 'BID_ACCEPTED' && event.payload['price'] === amount),
      )
      expect(accepted.seq).toBe(placed.data.seq)
      expect(accepted.payload['leader']).toBe(await anonymousId(bidderLogin.data.user.id))
      expect(accepted.payload['endsAt']).toBeTruthy()

      // 4. 被拒的出价也要有自己的事件（scope = 请求者），错误码与 HTTP 一致。
      const rejected = await expectApiFailure(
        bidderApi.placeBid(auctionId, { requestId: `req-ws-${Date.now()}-low`, amount: 1 }),
      )
      expect(rejected.code).toBe('BID_TOO_LOW')
      const rejection = await waitFor('BID_REJECTED 帧', () =>
        events.find((event) => event.type === 'BID_REJECTED'),
      )
      expect(rejection.auctionId).toBe(auctionId)
      expect(rejection.payload['code']).toBe('BID_TOO_LOW')
      // 被拒不是状态变更：它带的是当前 seq，不会把客户端推进到不存在的版本。
      expect(rejection.seq).toBe(placed.data.seq)

      // 5. 可见性边界：WS 帧里绝不能出现原始 user_id（HTTP 快照里才有）。
      const frames = rawFrames.join('\n')
      expect(frames).not.toContain(bidderLogin.data.user.id)
      expect(frames).not.toContain(adminLogin.data.user.id)
      expect(frames).toContain('anon-')

      // 6. seq 单调：同一次提交的多个事件共享 seq，因此只允许不递减。
      const seqs = events.filter((event) => event.type !== 'CONNECTION_STATE').map((event) => event.seq)
      expect(seqs.every((seq, index) => index === 0 || seq >= seqs[index - 1])).toBe(true)

      console.log(`[live-ws] auction=${auctionId} 帧数=${events.length} 事件=${events.map((e) => e.type).join(',')}`)
    } finally {
      socket?.close()
      await adminApi.cancelAuction(auctionId).catch(() => undefined)
    }
  }, 90_000)
})
