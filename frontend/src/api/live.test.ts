import { describe, expect, it } from 'vitest'
import { expectApiFailure } from '../testing/apiFailure'
import { createApiClient } from './client'
import { createAuctionApi } from './endpoints'

/**
 * 真实后端联调（默认跳过）。
 *
 * 其余单测用的是假 fetch：它们证明“我们按契约解析”，但证明不了“契约与真实服务端一致”。
 * 这条测试补上那一半：它打的是真跑起来的 `Application`（含鉴权过滤器、封套与 Flyway 迁移后的真库）。
 *
 * 运行方式（先启动后端，见 README 快速启动）：
 *   BID_ARENA_LIVE=1 BID_ARENA_LIVE_API=http://localhost:8080/api/v1 \
 *   BID_ARENA_DEMO_ADMIN_EMAIL=... BID_ARENA_DEMO_ADMIN_PASSWORD=... \
 *   BID_ARENA_DEMO_BIDDER_EMAIL=... BID_ARENA_DEMO_BIDDER_PASSWORD=... \
 *   npx vitest run src/api/live.test.ts
 *
 * 它会在**开发库**里创建一场拍卖并出价，结束时取消（冻解随之释放），
 * 因此不会给演示数据留下跑不完的拍卖，只多几条流水。
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

describe.skipIf(!live)('真实后端联调：HTTP 契约与客户端一致', () => {
  it('登录 → 创建 → 开始 → 加入 → 出价 → 幂等重放 → 钱包 → 结果 → 取消', async () => {
    // 1. 登录：字段名必须与契约一致，否则这里立刻是 undefined
    const anonymousApi = createAuctionApi(clientFor())
    const login = await anonymousApi.login({ email: adminEmail, password: adminPassword })
    expect(login.code).toBe('OK')
    expect(login.data.accessToken.length).toBeGreaterThan(20)
    expect(login.data.user.role).toBe('ADMIN')
    expect(Number.isNaN(Date.parse(login.data.expiresAt))).toBe(false)

    const adminApi = createAuctionApi(clientFor(login.data.accessToken))
    expect((await adminApi.currentUser()).data.id).toBe(login.data.user.id)

    // 2. 管理员创建并开始一场拍卖。创建放在 try **外面**：清理用的 auctionId 只能是常量，
    //    否则内层一不小心用 const 遮蔽外部变量，finally 就静默地什么都没做（踩过，见 DBG-21）。
    const created = await adminApi.createAuction({
      title: `联调拍品 ${new Date().toISOString()}`,
      description: '由前端联调脚本创建，结束时会被取消',
      startPrice: 100,
      minIncrement: 10,
      durationSeconds: 300,
    })
    expect(created.data.status).toBe('DRAFT')
    const auctionId = created.data.id

    try {
      const started = await adminApi.startAuction(auctionId)
      expect(started.data.status).toBe('RUNNING')
      expect(started.data.endsAt).toBeTruthy()
      const seqAfterStart = started.data.seq
      expect(seqAfterStart).toBeGreaterThanOrEqual(1)

      // 3. 竞拍者登录、加入、出价
      const bidderLogin = await anonymousApi.login({ email: bidderEmail, password: bidderPassword })
      const bidderApi = createAuctionApi(clientFor(bidderLogin.data.accessToken))
      const walletBefore = await bidderApi.wallet()
      expect(walletBefore.data.availableBalance).toBe(walletBefore.data.totalBalance - walletBefore.data.frozenAmount)

      await bidderApi.joinAuction(auctionId)
      const firstAmount = started.data.currentPrice + started.data.minIncrement
      const firstRequestId = `req-live-${Date.now()}-1`
      const first = await bidderApi.placeBid(auctionId, { requestId: firstRequestId, amount: firstAmount })
      expect(first.code).toBe('OK')
      expect(first.data.accepted).toBe(true)
      expect(first.data.price).toBe(firstAmount)
      const seqAfterFirstBid = first.data.seq
      expect(seqAfterFirstBid).toBeGreaterThan(seqAfterStart)

      // 4. 同一 requestId 重放：仍 200，但 code 变成重放，价格与 seq 都不前进
      const replay = await bidderApi.placeBid(auctionId, { requestId: firstRequestId, amount: firstAmount })
      expect(replay.code).toBe('IDEMPOTENCY_REPLAY')
      expect(replay.data.price).toBe(firstAmount)
      expect(replay.data.seq).toBe(seqAfterFirstBid)

      // 5. 同一人再加价：只冻结差额，可用额与冻结算得通
      const secondAmount = firstAmount + 10
      const secondRequestId = `req-live-${Date.now()}-2`
      const second = await bidderApi.placeBid(auctionId, { requestId: secondRequestId, amount: secondAmount })
      expect(second.data.accepted).toBe(true)
      expect(second.data.seq).toBeGreaterThan(seqAfterFirstBid)

      const detail = await bidderApi.auction(auctionId)
      expect(detail.data.currentPrice).toBe(secondAmount)
      expect(detail.data.leader).toBe(bidderLogin.data.user.id)
      const walletAfter = await bidderApi.wallet()
      // 断言**增量**而不是绝对值：同一个演示账号可能还有别的拍卖在冻着钱，
      // 写死绝对值会让这条测试在“另一场拍卖正在进行”时莫名其妙地红。
      expect(walletAfter.data.frozenAmount - walletBefore.data.frozenAmount).toBe(secondAmount)
      expect(walletAfter.data.availableBalance).toBe(walletBefore.data.availableBalance - secondAmount)
      expect(walletAfter.data.availableBalance).toBeGreaterThanOrEqual(0)

      // 6. 出价记录：重放不产生第二条记录（幂等的可观测证据）
      const bids = await bidderApi.bids(auctionId)
      const mine = bids.data.items.filter((item) => item.userId === bidderLogin.data.user.id)
      expect(mine.length).toBe(2)
      expect(mine.map((item) => item.requestId).sort()).toEqual([firstRequestId, secondRequestId].sort())
      expect(mine.every((item) => typeof item.serverTime === 'string')).toBe(true)

      // 7. 未结算的拍卖取结果：契约里是 404，客户端要能把它当成“还没有结果”而不是崩溃
      const unsettled = await expectApiFailure(bidderApi.result(auctionId))
      expect(unsettled.code).toBe('NOT_FOUND')

      // 8. 列表页数据源：能按状态过滤查到这场
      const running = await bidderApi.auctions({ status: 'RUNNING' })
      expect(running.data.items.some((item) => item.id === auctionId)).toBe(true)

      // 9. 流水里有本场的冻结（钱包页数据源）
      const ledger = await bidderApi.ledger()
      expect(ledger.data.items.some((entry) => entry.auctionId === auctionId && entry.type === 'FREEZE')).toBe(true)

      // 10. 取消：冻结全部释放（正常路径显式断言，异常路径由 finally 兜底）
      const cancelled = await adminApi.cancelAuction(auctionId)
      expect(cancelled.data.status).toBe('CANCELLED')
      expect((await bidderApi.wallet()).data.frozenAmount).toBe(walletBefore.data.frozenAmount)

      console.log(
        `[live] auction=${auctionId} seq ${seqAfterStart}→${seqAfterFirstBid}→${second.data.seq} ` +
          `price=${secondAmount} 冻结已释放 available=${(await bidderApi.wallet()).data.availableBalance}`,
      )
    } finally {
      // 无论断言是否通过都把这场拍卖收尾：漏掉它会在开发库里留下一直在跑的拍卖与冻结资金。
      // 已经取消时服务端返回 409，属于预期内的收尾，忽略即可。
      await adminApi.cancelAuction(auctionId).catch(() => undefined)
    }
  }, 90_000)

  it('未登录与错误口令的失败形态都是契约封套', async () => {
    const anonymousApi = createAuctionApi(clientFor())
    const unauthorized = await expectApiFailure(anonymousApi.currentUser())
    expect(unauthorized.code).toBe('UNAUTHENTICATED')
    expect(unauthorized.userMessage).toBe('登录已过期，请重新登录')

    const badLogin = await expectApiFailure(
      anonymousApi.login({ email: adminEmail, password: 'definitely-wrong-password' }),
    )
    expect(badLogin.code).toBe('UNAUTHENTICATED')
  }, 30_000)
})
