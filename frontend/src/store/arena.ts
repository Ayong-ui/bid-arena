import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import { anonymousId, createAnonIdCache } from '../anonymous'
import { ApiError, type ApiOk } from '../api/client'
import type { AgentProxySummary, AgentTokenSummary, AuctionResult, AuctionSnapshot, AuctionStatus, Bid, CreateAgentProxyRequest, CreateAuctionRequest, CreateMyAgentTokenRequest, LedgerEntry, LoginRequest, User, Wallet } from '../api/types'
import { createServerClock, formatRemaining } from '../realtime/clock'
import { numberField, stringField, type AuctionEventEnvelope } from '../realtime/events'
import { AuctionFeed, type SnapshotPayload } from '../realtime/feed'
import { createBrowserSocket, socketUrl, type FeedState } from '../realtime/socket'
import { arenaDeps } from './deps'
import { DEMO_ACCOUNTS, describe, isUnauthenticated, liveFromPayload, newRequestId, toLive, toSnapshotPayload, type LiveAuction } from './model'

export const useArenaStore = defineStore('arena', () => {
  const deps = arenaDeps()

  // ── 会话与身份 ────────────────────────────────────────────────────────────
  const session = ref(deps.session.read())
  const currentUser = computed<User | null>(() => session.value?.user ?? null)
  const isAdmin = computed(() => currentUser.value?.role === 'ADMIN')
  const loggedIn = computed(() => session.value !== null)
  const myAnonId = ref<string | null>(null)

  // ── 数据 ──────────────────────────────────────────────────────────────────
  const auctions = ref<AuctionSnapshot[]>([])
  const current = ref<LiveAuction | null>(null)
  const bids = ref<Bid[]>([])
  const wallet = ref<Wallet | null>(null)
  const ledger = ref<LedgerEntry[]>([])
  const settlement = ref<AuctionResult | null>(null)
  const adminLedger = ref<LedgerEntry[]>([])
  const adminLedgerAuctionId = ref<string | null>(null)
  const adminLedgerLoading = ref(false)
  /**
   * AI 授权（D-34）。`issuedAgentToken` 是唯一会出现明文的地方，而且只存在到用户
   * 主动关掉那张卡片：服务端不会也不能再给出第二次。
   */
  const myAgentTokens = ref<AgentTokenSummary[]>([])
  const myAgentTokensLoading = ref(false)
  const allAgentTokens = ref<AgentTokenSummary[]>([])
  const allAgentTokensLoading = ref(false)
  const issuedAgentToken = ref<{ token: string; tokenId: string; name: string; expiresAt: string } | null>(null)
  /**
   * 托管 AI 代理（D-36）。与上面那套 Token 是两条并列的路：
   * 这条由**服务端替用户跑**（普通人不写代码就能用），Token 那条是用户自己接程序。
   */
  const myAgentProxies = ref<AgentProxySummary[]>([])
  const myAgentProxiesLoading = ref(false)
  const allAgentProxies = ref<AgentProxySummary[]>([])
  const allAgentProxiesLoading = ref(false)
  const joinedIds = ref<string[]>([])
  const anonById = shallowRef<Record<string, string>>({})

  // ── 实时 ──────────────────────────────────────────────────────────────────
  const feedState = ref<FeedState>('idle')
  const feedDetail = ref<{ code?: string; message?: string; attempt?: number }>({})
  let feed: AuctionFeed | null = null

  // ── 界面状态 ──────────────────────────────────────────────────────────────
  const bidInFlight = ref(false)
  const listLoading = ref(false)
  const notice = ref<{ kind: 'info' | 'error'; text: string } | null>(null)
  const serverNow = ref(deps.now ? deps.now() : Date.now())
  const clock = createServerClock()
  let anonCache = createAnonIdCache()

  /** 幂等键复用：网络失败重试时必须用**同一个** requestId，否则可能出价两次。 */
  let pendingBid: { auctionId: string; amount: number; requestId: string } | null = null
  /**
   * 上一次看到的代理状态。用来把“刚刚触顶 / 刚刚结束”变成**一次**提醒：
   * 服务端不为代理单开私有推送，提醒就靠这个页面轮询后的前后对比（D-36）。
   */
  let proxyStatusSeen = new Map<string, string>()

  const runningAuctions = computed(() => auctions.value.filter((item) => item.status === 'RUNNING'))
  const otherAuctions = computed(() => auctions.value.filter((item) => item.status !== 'RUNNING'))
  /**
   * 可以挂托管代理的场次：还没开拍的和正在进行的。
   * 已结束/已取消的不列——服务端也会拒（`INVALID_STATE`），但让用户点进去才被拒是坏体验。
   */
  const proxyCandidates = computed(() =>
    auctions.value.filter((item) => item.status === 'DRAFT' || item.status === 'RUNNING'))

  /** 这场是不是已经有我的代理（一人一场只有一个位置，服务端会 409）。 */
  function hasProxyFor(auctionId: string): boolean {
    return myAgentProxies.value.some((proxy) => proxy.auctionId === auctionId && proxy.status !== 'REVOKED')
  }
  const joined = computed(() => (current.value ? joinedIds.value.includes(current.value.id) : false))
  const isMyLead = computed(() => !!current.value?.leaderAnon && current.value.leaderAnon === myAnonId.value)
  const nextBid = computed(() => (current.value ? current.value.currentPrice + current.value.minIncrement : 0))
  const remainingMs = computed(() => {
    // 读一下 serverNow：倒计时才会随心跳重算（clock 内部不是响应式的）。
    const at = serverNow.value
    const endsAt = current.value?.endsAt
    if (!endsAt) return null
    const end = Date.parse(endsAt)
    return Number.isNaN(end) ? null : end - at
  })
  const remainingLabel = computed(() => (current.value?.endsAt ? formatRemaining(remainingMs.value) : '--:--'))
  /**
   * 是否处于尾段“博弈时间”（截止前 `finalGameWindowSeconds` 秒内）。
   *
   * <p>纯展示：服务端在出价事务里已经无条件拒掉 Agent，这里既不能、也不该拦住任何人的按钮——
   * 真人在这段时间**仍然可以**出价，提示的作用只是告诉真人“Agent 已退场，现在是你和其他人的博弈”。
   * 时间基准用服务端 `endsAt` + 校准过的时钟，与倒计时同源，因此不会出现“倒计时还在走但提示先消失”。
   */
  const inFinalGameWindow = computed(() => {
    const remaining = remainingMs.value
    const window = current.value?.finalGameWindowSeconds
    return current.value?.status === 'RUNNING' && remaining !== null && window !== undefined
        && remaining > 0 && remaining <= window * 1000
  })
  /**
   * 博弈时间窗口（秒）。**只从服务端快照读**，前端不写死（D-27）：
   * 当前打开的场次优先，否则取列表里任一场——所有场次下发的是同一个值。
   */
  const finalGameWindowSeconds = computed(() =>
    current.value?.finalGameWindowSeconds ?? auctions.value[0]?.finalGameWindowSeconds)
  const canBid = computed(() => current.value?.status === 'RUNNING' && joined.value && !bidInFlight.value && loggedIn.value)
  /** 倒计时读秒用的文案：连接状态直接决定用户该不该相信这个数字。 */
  const feedLabel = computed(() => {
    switch (feedState.value) {
      case 'live':
        return '实时同步中'
      case 'connecting':
        return '连接中…'
      case 'resyncing':
        return '正在恢复快照…'
      case 'retrying':
        return feedDetail.value.code === 'UNAUTHENTICATED' ? '实时票已过期，正在重连' : '重连中…'
      case 'closed':
        return '已断开'
      default:
        return '未连接'
    }
  })

  function applyServerTime(serverTime: string | undefined): void {
    if (!serverTime) return
    clock.sync(serverTime)
    serverNow.value = clock.now()
  }

  function setNotice(kind: 'info' | 'error', text: string): void {
    notice.value = { kind, text }
  }

  function clearNotice(): void {
    notice.value = null
  }

  /** 心跳：只推进“现在几点了”，不做任何状态推断（状态只来自服务端）。 */
  function tick(): void {
    serverNow.value = clock.now()
  }

  // ── 会话 ──────────────────────────────────────────────────────────────────
  async function login(credentials: LoginRequest): Promise<boolean> {
    try {
      const ok = await deps.api.login(credentials)
      session.value = { accessToken: ok.data.accessToken, expiresAt: ok.data.expiresAt, user: ok.data.user }
      deps.session.write(session.value)
      myAnonId.value = await anonymousId(ok.data.user.id)
      await afterLogin()
      return true
    } catch (error) {
      if (isUnauthenticated(error)) setNotice('error', '邮箱或密码不正确')
      else setNotice('error', describe(error))
      return false
    }
  }

  function logout(): void {
    closeAuction()
    deps.session.clear()
    session.value = null
    myAnonId.value = null
    auctions.value = []
    wallet.value = null
    ledger.value = []
    anonCache = createAnonIdCache()
    anonById.value = {}
    pendingBid = null
    // 明文 Token 绝不能跨会话留在内存里：换个人登录时它依然是一枚可用的凭证。
    issuedAgentToken.value = null
    myAgentTokens.value = []
    allAgentTokens.value = []
    myAgentProxies.value = []
    allAgentProxies.value = []
    proxyStatusSeen = new Map()
  }

  /** 令牌过期：清会话并说明原因（不静默跳走，用户得知道为什么）。 */
  function handleUnauthenticated(): void {
    logout()
    setNotice('error', '登录已过期，请重新登录')
  }

  async function afterLogin(): Promise<void> {
    await Promise.all([refreshAuctions(), refreshWallet()])
  }

  /** 刷新页面时用存储里的会话恢复；令牌已被判定过期时当作未登录。 */
  async function restore(): Promise<void> {
    const stored = deps.session.read()
    if (!stored) return
    myAnonId.value = await anonymousId(stored.user.id)
    await afterLogin()
  }

  // ── 读模型 ────────────────────────────────────────────────────────────────
  async function refreshAuctions(): Promise<void> {
    listLoading.value = true
    try {
      const page = await deps.api.auctions({ size: 50 })
      auctions.value = page.data.items
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    } finally {
      listLoading.value = false
    }
  }

  async function refreshWallet(): Promise<void> {
    try {
      const [me, entries] = await Promise.all([deps.api.wallet(), deps.api.ledger(1, 20)])
      wallet.value = me.data
      ledger.value = entries.data.items
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    }
  }

  /**
   * 运营台：按场次拉全量流水（含每条的主体标识）。这是唯一能看到“别人流水”的读路径，
   * 服务端强制 ADMIN；普通用户调用会拿到 403（契约见 DECISIONS D-33）。
   */
  async function loadAuctionLedger(auctionId: string): Promise<void> {
    adminLedgerLoading.value = true
    try {
      const page = await deps.api.auctionLedger(auctionId, 1, 50)
      adminLedger.value = page.data.items
      adminLedgerAuctionId.value = auctionId
    } catch (error) {
      adminLedger.value = []
      adminLedgerAuctionId.value = auctionId
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    } finally {
      adminLedgerLoading.value = false
    }
  }

  // ── AI 授权（D-34） ─────────────────────────────────────────────────────

  /** 我授权的 AI 凭证。只返回自己的，明文永远不在列表里。 */
  async function loadMyAgentTokens(): Promise<void> {
    myAgentTokensLoading.value = true
    try {
      myAgentTokens.value = (await deps.api.myAgentTokens(1, 50)).data.items
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    } finally {
      myAgentTokensLoading.value = false
    }
  }

  /**
   * 为自己签发一枚。服务端把归属钉死为当前用户（请求体里根本没有 agentUserId），
   * 因此前端也不需要、更不应该传任何“代表谁”的字段。
   */
  async function issueMyAgentToken(input: CreateMyAgentTokenRequest): Promise<boolean> {
    try {
      const ok = await deps.api.issueMyAgentToken(input)
      issuedAgentToken.value = {
        token: ok.data.token ?? '',
        tokenId: ok.data.tokenId ?? '',
        name: input.name ?? '未命名授权',
        expiresAt: ok.data.expiresAt ?? '',
      }
      await loadMyAgentTokens()
      setNotice('info', '授权已创建，请立刻复制这串 Token（关闭后不再显示）')
      return true
    } catch (error) {
      if (isUnauthenticated(error)) {
        handleUnauthenticated()
        return false
      }
      setNotice('error', describe(error))
      return false
    }
  }

  async function revokeMyAgentToken(tokenId: string): Promise<void> {
    try {
      await deps.api.revokeMyAgentToken(tokenId)
      if (issuedAgentToken.value?.tokenId === tokenId) issuedAgentToken.value = null
      await loadMyAgentTokens()
      setNotice('info', '授权已吊销，持这串 Token 的程序会立即失效')
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    }
  }

  function dismissIssuedAgentToken(): void {
    issuedAgentToken.value = null
  }

  /** 运营总览：全部已签发的凭证（仅 ADMIN）。 */
  async function loadAllAgentTokens(): Promise<void> {
    allAgentTokensLoading.value = true
    try {
      allAgentTokens.value = (await deps.api.agentTokens(1, 50)).data.items
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    } finally {
      allAgentTokensLoading.value = false
    }
  }

  // ── 托管 AI 代理（D-36） ──────────────────────────────────────────────────

  /**
   * 我托管的代理（服务端替用户跑）。
   *
   * 它同时是**提醒通道**：每次拉到新状态就与上次对比，只在“刚刚触顶 / 刚刚结束”时说一句。
   * 为什么不做成常驻的警告文案：提醒说三遍就变成背景噪音，用户要的是“变的那一刻”。
   */
  async function loadMyAgentProxies(): Promise<void> {
    myAgentProxiesLoading.value = true
    try {
      const items = (await deps.api.myAgentProxies(1, 50)).data.items
      myAgentProxies.value = items
      for (const proxy of items) {
        const previous = proxyStatusSeen.get(proxy.proxyId)
        const title = proxy.auctionTitle ?? proxy.auctionId
        if (previous && previous !== proxy.status) {
          if (proxy.status === 'BUDGET_REACHED') {
            setNotice(
              'info',
              `AI 代理「${title}」已到预算上限 ◎${proxy.budgetLimit}，已停止加价：想继续就要自己出价`,
            )
          } else if (proxy.status === 'FINISHED') {
            setNotice(
              'info',
              proxy.won
                ? `AI 代理替你拍下了「${title}」：成交价 ◎${proxy.finalPrice ?? proxy.currentPrice}`
                : `「${title}」已结束，AI 代理没有拍下`,
            )
          }
        }
        proxyStatusSeen.set(proxy.proxyId, proxy.status)
      }
      // 服务端可能已删掉的条目（不可能，但缓存不该只增不减）。
      const ids = new Set(items.map((proxy) => proxy.proxyId))
      for (const id of [...proxyStatusSeen.keys()]) if (!ids.has(id)) proxyStatusSeen.delete(id)
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    } finally {
      myAgentProxiesLoading.value = false
    }
  }

  /**
   * 创建托管代理。
   *
   * 服务端把归属钉死为当前用户（请求体里没有 `agentUserId`，也没有任何策略参数）：
   * “追多少价”是规则不是配置，前端不传、也就没有“传错”的可能。
   */
  async function createAgentProxy(input: CreateAgentProxyRequest): Promise<boolean> {
    try {
      const created = (await deps.api.createAgentProxy(input)).data
      await Promise.all([loadMyAgentProxies(), refreshWallet()])
      setNotice(
        'info',
        created.auctionStatus === 'DRAFT'
          ? `AI 代理已就位：「${created.auctionTitle ?? created.auctionId}」开拍后它会自动进场`
          : `AI 代理已进场：「${created.auctionTitle ?? created.auctionId}」下一手 ◎${created.nextBidAmount}`,
      )
      return true
    } catch (error) {
      if (isUnauthenticated(error)) {
        handleUnauthenticated()
        return false
      }
      setNotice('error', describe(error))
      return false
    }
  }

  async function revokeAgentProxy(proxyId: string): Promise<void> {
    try {
      await deps.api.revokeAgentProxy(proxyId)
      proxyStatusSeen.delete(proxyId)
      await loadMyAgentProxies()
      setNotice('info', '代理已撤销，不会再替你出价（已经成交的结果不受影响）')
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    }
  }

  /** 运营总览：全部托管代理（仅 ADMIN）。只读——没有代建/改预算的入口。 */
  async function loadAllAgentProxies(): Promise<void> {
    allAgentProxiesLoading.value = true
    try {
      allAgentProxies.value = (await deps.api.agentProxies(1, 50)).data.items
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    } finally {
      allAgentProxiesLoading.value = false
    }
  }

  async function refreshBids(auctionId: string): Promise<void> {
    try {
      const page = await deps.api.bids(auctionId, 1, 50)
      if (current.value?.id !== auctionId) return
      bids.value = page.data.items
      await ensureAnon(page.data.items.map((item) => item.userId))
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      // 出价记录拉失败不清空已有列表：保留旧数据比空列表更接近真相。
    }
  }

  async function refreshResult(auctionId: string): Promise<void> {
    try {
      settlement.value = (await deps.api.result(auctionId)).data
      if (settlement.value.settledAt) applyServerTime(settlement.value.settledAt)
    } catch (error) {
      // 未结算时契约里就是 404：这不是错误，只是“还没有结果”。
      if (error instanceof ApiError && error.code === 'NOT_FOUND') {
        settlement.value = null
        return
      }
      if (isUnauthenticated(error)) handleUnauthenticated()
    }
  }

  /** 把出现过的 user_id 解析成匿名标识，供模板同步查询。 */
  async function ensureAnon(userIds: Iterable<string | null>): Promise<void> {
    const ids = [...userIds].filter((id): id is string => !!id)
    await anonCache.ensure(ids)
    const next = { ...anonById.value }
    for (const id of ids) {
      const anon = anonCache.get(id)
      if (anon) next[id] = anon
    }
    anonById.value = next
  }

  function anonOf(userId: string | null | undefined): string | null {
    return userId ? (anonById.value[userId] ?? null) : null
  }

  // ── 命令 ──────────────────────────────────────────────────────────────────
  async function openAuction(auctionId: string): Promise<void> {
    closeAuction()
    try {
      const snapshot = (await deps.api.auction(auctionId)).data
      applyServerTime(snapshot.serverTime)
      current.value = toLive(snapshot, snapshot.description ?? '', snapshot.leader ? await anonymousId(snapshot.leader) : null)
      await Promise.all([refreshBids(auctionId), refreshResult(auctionId)])
      startFeed(auctionId)
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    }
  }

  function closeAuction(): void {
    feed?.stop()
    feed = null
    feedState.value = 'idle'
    feedDetail.value = {}
    current.value = null
    bids.value = []
    settlement.value = null
  }

  function startFeed(auctionId: string): void {
    feed = new AuctionFeed(
      {
        fetchTicket: async () => (await deps.api.wsTicket()).data,
        fetchSnapshot: async () => {
          const snapshot = (await deps.api.auction(auctionId)).data
          applyServerTime(snapshot.serverTime)
          const anon = snapshot.leader ? await anonymousId(snapshot.leader) : null
          return toSnapshotPayload(snapshot, anon)
        },
        createSocket: deps.createSocket ?? createBrowserSocket,
        // 票里的路径是模板，auctionId 由这里补上：feed 只关心"怎么连"，不关心连的是哪一场。
        url: (ticket) => socketUrl(ticket, auctionId),
        schedule: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
        cancelSchedule: (handle) => globalThis.clearTimeout(handle as number),
      },
      {
        onSnapshot: (snapshot) => applySnapshot(snapshot),
        onEvent: (event) => applyEvent(auctionId, event),
        onState: (state, detail) => {
          feedState.value = state
          feedDetail.value = detail
        },
      },
    )
    feed.start()
  }

  async function joinCurrent(): Promise<void> {
    const auction = current.value
    if (!auction) return
    try {
      await deps.api.joinAuction(auction.id)
      markJoined(auction.id)
      setNotice('info', '已加入本场拍卖')
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    }
  }

  function markJoined(auctionId: string): void {
    if (!joinedIds.value.includes(auctionId)) joinedIds.value = [...joinedIds.value, auctionId]
  }

  /**
   * 出价。
   *
   * 三个刻意的选择：
   * - **同一金额的重试复用 requestId**：网络抖动下的重试必须是同一次命令，否则服务端会当成两次出价；
   * - **被拒后不复用**：`BID_TOO_LOW` 说明这次尝试已经定案，用户改金额就是一次新命令；
   * - **`NOT_JOINED` 自动加入后重试一次**：用户点“出价”就是在表达要参与，
   *   让他先去点另一个按钮只是把一次点击变成两次（服务端的加入本身是幂等的）。
   */
  async function placeBid(amount: number): Promise<boolean> {
    const auction = current.value
    if (!auction) return false
    if (!Number.isFinite(amount) || amount <= 0) {
      setNotice('error', '请填写出价金额')
      return false
    }
    bidInFlight.value = true
    try {
      return await submitBid(auction.id, amount, reuseRequestId(auction.id, amount), true)
    } finally {
      bidInFlight.value = false
    }
  }

  async function submitBid(auctionId: string, amount: number, requestId: string, allowJoinRetry: boolean): Promise<boolean> {
    try {
      const ok = await deps.api.placeBid(auctionId, { requestId, amount })
      applyServerTime(ok.data.serverTime)
      if (current.value?.id === auctionId) {
        current.value.currentPrice = ok.data.price
        current.value.seq = ok.data.seq
        current.value.leaderAnon = myAnonId.value
      }
      pendingBid = null
      markJoined(auctionId)
      setNotice('info', ok.code === 'IDEMPOTENCY_REPLAY' ? '这次出价已提交过，结果是同一个' : '出价成功，已成为当前领先者')
      await Promise.all([refreshWallet(), refreshBids(auctionId)])
      return true
    } catch (error) {
      if (isUnauthenticated(error)) {
        handleUnauthenticated()
        return false
      }
      if (allowJoinRetry && error instanceof ApiError && error.code === 'NOT_JOINED') {
        try {
          await deps.api.joinAuction(auctionId)
          markJoined(auctionId)
          return await submitBid(auctionId, amount, requestId, false)
        } catch (joinError) {
          setNotice('error', describe(joinError))
          return false
        }
      }
      if (error instanceof ApiError && error.code === 'NETWORK') {
        // 网络失败：保留同一个 requestId，让“重试”成为一次安全的重复提交。
        setNotice('error', `${describe(error)}（重试会沿用同一次出价）`)
        return false
      }
      pendingBid = null
      setNotice('error', describe(error))
      return false
    }
  }

  function reuseRequestId(auctionId: string, amount: number): string {
    if (pendingBid && pendingBid.auctionId === auctionId && pendingBid.amount === amount) return pendingBid.requestId
    pendingBid = { auctionId, amount, requestId: newRequestId() }
    return pendingBid.requestId
  }

  async function createAuction(input: CreateAuctionRequest): Promise<boolean> {
    try {
      const created = (await deps.api.createAuction(input)).data
      applyServerTime(created.serverTime)
      await refreshAuctions()
      setNotice('info', '拍卖已创建，点击开始即可开放竞价')
      return true
    } catch (error) {
      if (isUnauthenticated(error)) {
        handleUnauthenticated()
        return false
      }
      setNotice('error', describe(error))
      return false
    }
  }

  async function startAuction(auctionId: string): Promise<void> {
    await adminAction(() => deps.api.startAuction(auctionId), auctionId, '拍卖已开始')
  }

  async function cancelAuction(auctionId: string): Promise<void> {
    await adminAction(() => deps.api.cancelAuction(auctionId), auctionId, '拍卖已取消')
  }

  async function adminAction(
    run: () => Promise<ApiOk<AuctionSnapshot>>,
    auctionId: string,
    successText: string,
  ): Promise<void> {
    try {
      const ok = await run()
      applyServerTime(ok.data.serverTime)
      if (current.value?.id === auctionId) {
        const anon = ok.data.leader ? await anonymousId(ok.data.leader) : null
        current.value = toLive(ok.data, current.value.description, anon)
      }
      await refreshAuctions()
      setNotice('info', successText)
    } catch (error) {
      if (isUnauthenticated(error)) return handleUnauthenticated()
      setNotice('error', describe(error))
    }
  }

  // ── 事件应用 ──────────────────────────────────────────────────────────────
  function applySnapshot(snapshot: SnapshotPayload): void {
    applyServerTime(snapshot.serverTime)
    if (current.value?.id !== snapshot.id) return
    current.value = liveFromPayload(snapshot, current.value.description)
    if (snapshot.status !== 'RUNNING') void refreshResult(snapshot.id)
    void refreshBids(snapshot.id)
  }

  function applyEvent(auctionId: string, event: AuctionEventEnvelope): void {
    if (current.value?.id !== auctionId) return
    const target = current.value
    applyServerTime(event.serverTime)
    const payload = event.payload
    switch (event.type) {
      case 'PARTICIPANT_JOINED': {
        const count = numberField(payload, 'participantCount')
        if (count !== undefined) target.participantCount = count
        if (stringField(payload, 'participant') === myAnonId.value) markJoined(auctionId)
        break
      }
      case 'BID_ACCEPTED': {
        target.currentPrice = numberField(payload, 'price') ?? target.currentPrice
        target.leaderAnon = stringField(payload, 'leader') ?? target.leaderAnon
        target.endsAt = stringField(payload, 'endsAt') ?? target.endsAt
        const extensions = numberField(payload, 'extensionCount')
        if (extensions !== undefined) target.extensionCount = extensions
        void refreshBids(auctionId)
        void refreshWallet()
        break
      }
      case 'AUCTION_EXTENDED': {
        target.endsAt = stringField(payload, 'endsAt') ?? target.endsAt
        const extensions = numberField(payload, 'extensionCount')
        if (extensions !== undefined) target.extensionCount = extensions
        break
      }
      case 'BID_REJECTED': {
        // 这个事件只发给请求者本人，因此收到就说明是自己那笔被拒了。
        const code = stringField(payload, 'code')
        const reason = stringField(payload, 'reason')
        setNotice('error', code ? `${code}：${reason ?? '出价被拒绝'}` : (reason ?? '出价被拒绝'))
        break
      }
      case 'AUCTION_FINISHED': {
        const status = stringField(payload, 'status')
        if (status) target.status = status as AuctionStatus
        const winner = stringField(payload, 'winner')
        if (winner) target.leaderAnon = winner
        void refreshResult(auctionId)
        void refreshWallet()
        void refreshAuctions()
        break
      }
      default:
        // CONNECTION_STATE 由 feed 处理；AUCTION_SNAPSHOT 走 applySnapshot。
        break
    }
  }

  return {
    // 身份
    session,
    currentUser,
    isAdmin,
    loggedIn,
    demoAccounts: DEMO_ACCOUNTS,
    myAnonId,
    // 数据
    auctions,
    runningAuctions,
    otherAuctions,
    current,
    bids,
    wallet,
    ledger,
    settlement,
    adminLedger,
    adminLedgerAuctionId,
    adminLedgerLoading,
    myAgentTokens,
    myAgentTokensLoading,
    allAgentTokens,
    allAgentTokensLoading,
    issuedAgentToken,
    myAgentProxies,
    myAgentProxiesLoading,
    allAgentProxies,
    allAgentProxiesLoading,
    proxyCandidates,
    hasProxyFor,
    joined,
    joinedIds,
    isMyLead,
    nextBid,
    remainingMs,
    remainingLabel,
    inFinalGameWindow,
    finalGameWindowSeconds,
    canBid,
    serverNow,
    // 实时
    feedState,
    feedLabel,
    feedDetail,
    // 界面
    bidInFlight,
    listLoading,
    notice,
    // 命令
    login,
    logout,
    restore,
    openAuction,
    closeAuction,
    joinCurrent,
    placeBid,
    createAuction,
    startAuction,
    cancelAuction,
    refreshAuctions,
    refreshWallet,
    loadAuctionLedger,
    loadMyAgentTokens,
    issueMyAgentToken,
    revokeMyAgentToken,
    dismissIssuedAgentToken,
    loadAllAgentTokens,
    loadMyAgentProxies,
    createAgentProxy,
    revokeAgentProxy,
    loadAllAgentProxies,
    clearNotice,
    setNotice,
    tick,
    anonOf,
    ensureAnon,
  }
})
