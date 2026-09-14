<script setup lang="ts">
/**
 * 拍卖间前端。
 *
 * 与 Mock 版最大的区别：**界面上每一个数字都来自服务端**。
 * 价格、钱包、参与人数、剩余时间都来自 HTTP 快照或 WS 事件，
 * 本地只保留"用户正在输入什么"这类纯输入状态。
 * 这条边界是刻意的：Mock 版里前端自己算钱和倒计时，一旦和服务端不一致，
 * 用户看到的就变成了一个不存在的事实。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useArenaStore, type LiveAuction } from './store'
import type { AuctionStatus } from './api/types'
import { formatRemaining } from './realtime/clock'

const store = useArenaStore()

const view = ref<'auctions' | 'wallet' | 'admin' | 'agent'>('auctions')
const loginOpen = ref(false)
const email = ref('')
const password = ref('')
const loginBusy = ref(false)
const bidAmount = ref<number | null>(null)
const newTitle = ref('')
const newDescription = ref('')
const newStartPrice = ref(100)
const newMinIncrement = ref(50)
const newDuration = ref(60)
const newStartsAt = ref('')
let timer: number | undefined
let proxyTimer: number | undefined
let copyTimer: number | undefined

const apiBase = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

onMounted(async () => {
  await store.restore()
  timer = window.setInterval(() => store.tick(), 1_000)
  // AI 代理的进展靠轮询可见（服务端不为它单开私有推送，D-36）：
  // 只有停在 AI 页时才拉，别在拍卖大厅里每 3 秒白跑一个请求。
  proxyTimer = window.setInterval(() => {
    if (view.value === 'agent' && store.loggedIn) void store.loadMyAgentProxies()
  }, 3_000)
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
  if (proxyTimer) window.clearInterval(proxyTimer)
  if (copyTimer) window.clearTimeout(copyTimer)
  store.closeAuction()
})

const heading = computed(() => {
  if (view.value === 'wallet') return '我的资金'
  if (view.value === 'admin') return '运营台'
  if (view.value === 'agent') return '我的 AI 代理'
  return store.current ? '竞价详情' : '拍卖大厅'
})
const roleLabel = computed(() => (store.currentUser?.role === 'ADMIN' ? '管理员' : '竞拍者'))
const initial = computed(() => (store.currentUser?.name ?? '?').slice(0, 1).toUpperCase())
const serverClock = computed(() => new Date(store.serverNow).toLocaleTimeString('zh-CN'))
const statusLabels: Record<AuctionStatus, string> = {
  DRAFT: '草稿',
  RUNNING: '进行中',
  SETTLING: '结算中',
  FINISHED: '已结束',
  CANCELLED: '已取消',
}

function money(value: number | null | undefined): string {
  return (value ?? 0).toLocaleString('zh-CN')
}

function statusLabel(status: AuctionStatus): string {
  return statusLabels[status] ?? status
}

function shortId(id: string | null | undefined): string {
  if (!id) return '—'
  return id.length > 12 ? `${id.slice(0, 11)}…` : id
}

/**
 * 预告开拍还剩多久。
 *
 * 用 `store.serverNow`（校准过的服务端时间）而不是 `Date.now()`：
 * 开发机与服务器差几分钟时，本地时钟会把这个预告算成“已经到点”，
 * 而服务端其实还没到。
 */
function startHint(value: string | null | undefined): string | null {
  if (!value) return null
  const at = Date.parse(value)
  if (Number.isNaN(at)) return null
  const diff = at - store.serverNow
  return diff > 0 ? `预告 ${formatRemaining(diff)} 后开拍` : '预告时间已到，等待开拍'
}

function displayName(text: string | null): string {
  if (!text) return '—'
  return text === store.myAnonId ? '你' : text
}

function bidderLabel(userId: string): string {
  if (userId === store.currentUser?.id) return '你'
  const anon = store.anonOf(userId)
  return anon ? displayName(anon) : shortId(userId)
}

function openLogin(): void {
  loginOpen.value = true
  store.clearNotice()
}

function fillDemo(account: { email: string; password: string }): void {
  email.value = account.email
  password.value = account.password
}

async function submitLogin(): Promise<void> {
  if (!email.value || !password.value) {
    notify('请填写邮箱和密码')
    return
  }
  loginBusy.value = true
  const ok = await store.login({ email: email.value.trim(), password: password.value })
  loginBusy.value = false
  if (ok) {
    loginOpen.value = false
    password.value = ''
    view.value = 'auctions'
  }
}

async function open(auction: LiveAuction | { id: string }): Promise<void> {
  bidAmount.value = null
  await store.openAuction(auction.id)
  if (store.current) bidAmount.value = store.nextBid
}

function back(): void {
  store.closeAuction()
  bidAmount.value = null
}

function bump(multiplier: number): void {
  if (!store.current) return
  bidAmount.value = store.current.currentPrice + store.current.minIncrement * multiplier
}

async function submitBid(): Promise<void> {
  if (bidAmount.value === null) return
  const ok = await store.placeBid(bidAmount.value)
  // 出价被拒时金额原地保留：用户想改的数字通常只是差一点点。
  if (ok && store.current) bidAmount.value = store.nextBid
}

async function submitCreate(): Promise<void> {
  if (!newTitle.value.trim()) {
    notify('请填写拍品名称')
    return
  }
  // `datetime-local` 给的是不带时区的本地时间字面量，而契约只收带时区的时刻：
  // 这里用浏览器把它转成 ISO（带 Z/偏移），歧义在进网前就被消掉。
  const startsAt = newStartsAt.value ? new Date(newStartsAt.value) : null
  if (startsAt && Number.isNaN(startsAt.getTime())) {
    notify('预告开拍时间不合法')
    return
  }
  const ok = await store.createAuction({
    title: newTitle.value.trim(),
    description: newDescription.value.trim() || undefined,
    startPrice: newStartPrice.value,
    minIncrement: newMinIncrement.value,
    durationSeconds: newDuration.value,
    startsAt: startsAt ? startsAt.toISOString() : null,
  })
  if (ok) {
    newTitle.value = ''
    newDescription.value = ''
    newStartsAt.value = ''
  }
}

function notify(text: string): void {
  store.setNotice('error', text)
}

/** 结算由服务端的到点任务完成，前端不做任何"猜测已结束"。 */
function settledLabel(code: string): string {
  return { FREEZE: '冻结', RELEASE: '释放', SETTLE: '成交扣款' }[code] ?? code
}

// ── 我的 AI 代理（D-34） ────────────────────────────────────────────────────
const agentFormOpen = ref(false)
const agentName = ref('')
const agentScopes = ref<Array<'auction:read' | 'auction:bid'>>(['auction:read', 'auction:bid'])
const agentAuctionIds = ref<string[]>([])
const agentExpiresAt = ref(defaultAgentExpiry())
const agentRateLimit = ref<number | null>(null)
const agentCopied = ref(false)

const agentStatusLabels: Record<string, string> = { ACTIVE: '生效中', EXPIRED: '已过期', REVOKED: '已吊销' }

/** `datetime-local` 要的是本地时间字面量；提交前再转成 ISO 时刻（服务端只认 `Instant`）。 */
function localDateTime(date: Date): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function defaultAgentExpiry(): string {
  return localDateTime(new Date(Date.now() + 7 * 24 * 3600 * 1000))
}

function agentStatusLabel(status: string): string {
  return agentStatusLabels[status] ?? status
}

function showAgent(): void {
  view.value = 'agent'
  void store.loadMyAgentTokens()
  void store.loadMyAgentProxies()
  if (store.isAdmin) {
    void store.loadAllAgentTokens()
    void store.loadAllAgentProxies()
  }
}

function toggleAgentScope(scope: 'auction:read' | 'auction:bid'): void {
  agentScopes.value = agentScopes.value.includes(scope)
    ? agentScopes.value.filter((item) => item !== scope)
    : [...agentScopes.value, scope]
}

function toggleAgentAuction(auctionId: string): void {
  agentAuctionIds.value = agentAuctionIds.value.includes(auctionId)
    ? agentAuctionIds.value.filter((item) => item !== auctionId)
    : [...agentAuctionIds.value, auctionId]
}

async function submitAgentToken(): Promise<void> {
  if (!agentName.value.trim()) return notify('请给这份授权起个名字')
  if (!agentScopes.value.length) return notify('至少要勾选一项权限')
  const expires = new Date(agentExpiresAt.value)
  if (Number.isNaN(expires.getTime())) return notify('请选择有效期')
  const ok = await store.issueMyAgentToken({
    name: agentName.value.trim(),
    // 空数组与“省略”在服务端都表示默认拒绍；显式传空数组，语义更直白。
    auctionIds: agentAuctionIds.value,
    scopes: agentScopes.value,
    expiresAt: expires.toISOString(),
    // 契约里带 default 的字段在生成类型里是必填，所以这里显式给出与默认值一致的数字。
    rateLimitPerMinute: typeof agentRateLimit.value === 'number' && agentRateLimit.value > 0 ? agentRateLimit.value : 60,
  })
  if (ok) {
    agentFormOpen.value = false
    agentName.value = ''
    agentRateLimit.value = null
    agentAuctionIds.value = []
  }
}

async function copyAgentToken(): Promise<void> {
  const issued = store.issuedAgentToken
  if (!issued) return
  try {
    await navigator.clipboard.writeText(issued.token)
    agentCopied.value = true
    if (copyTimer) window.clearTimeout(copyTimer)
    copyTimer = window.setTimeout(() => (agentCopied.value = false), 1500)
  } catch {
    notify('复制失败，请手动选中这串 Token')
  }
}

// ── 托管 AI 代理（D-36） ─────────────────────────────────────────────────
// 这一段是给**不写代码的人**用的：选一场拍卖、填一个预算，剩下的事服务端做。
// 上面的 Token 那套是“自己接程序”的路，保留在“高级”里。
const proxyAuctionId = ref('')
const proxyBudget = ref<number | null>(null)
const proxyBusy = ref(false)
const advancedOpen = ref(false)

const proxyStatusLabels: Record<string, string> = {
  PENDING: '待进场',
  BIDDING: '出价中',
  BUDGET_REACHED: '已达预算上限',
  FINISHED: '已结束',
  REVOKED: '已撤销',
}

// 与 `.agent-status` 的修饰类保持一致：可见状态就是颜色。
const proxyStatusClass: Record<string, string> = {
  PENDING: 'pending',
  BIDDING: 'active',
  BUDGET_REACHED: 'expired',
  FINISHED: 'finished',
  REVOKED: 'revoked',
}

function proxyStatusLabel(status: string): string {
  return proxyStatusLabels[status] ?? status
}

function proxyStatusMark(status: string): string {
  return proxyStatusClass[status] ?? 'pending'
}

/**
 * 提交创建。
 *
 * 本地只做“填了没有”的检查：预算是否超过可用余额、这场能不能挂
 * 都交给服务端判定（它是唯一知道余额与场次状态的权威）。
 */
async function submitProxy(): Promise<void> {
  if (!proxyAuctionId.value) {
    notify('请选择一场拍卖')
    return
  }
  const budget = proxyBudget.value
  if (typeof budget !== 'number' || !Number.isFinite(budget) || budget <= 0) {
    notify('请填写一个大于 0 的预算上限')
    return
  }
  proxyBusy.value = true
  const ok = await store.createAgentProxy({ auctionId: proxyAuctionId.value, budgetLimit: Math.floor(budget) })
  proxyBusy.value = false
  if (ok) {
    proxyAuctionId.value = ''
    proxyBudget.value = null
  }
}

/** 选场次时把预算默认成“现在领先需要的那口价”，用户只需要改大不改小。 */
function pickProxyAuction(): void {
  const picked = store.proxyCandidates.find((item) => item.id === proxyAuctionId.value)
  if (picked && proxyBudget.value === null) proxyBudget.value = picked.currentPrice + picked.minIncrement
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">BA</span>
        <span><b>BID ARENA</b><small>实时拍卖间</small></span>
      </div>
      <nav>
        <button :class="{ active: view === 'auctions' }" @click="view = 'auctions'">🏛 拍卖大厅</button>
        <button :class="{ active: view === 'wallet' }" @click="view = 'wallet'">◎ 我的资金</button>
        <button v-if="store.isAdmin" :class="{ active: view === 'admin' }" @click="view = 'admin'">⚙ 运营台</button>
        <button :class="{ active: view === 'agent' }" @click="showAgent">🤖 我的 AI 代理</button>
      </nav>
      <div class="sidebar-note">
        <span :class="['live-dot', store.feedState === 'live' ? '' : 'off']"></span>{{ store.feedLabel }}
        <br /><small>服务器时间 {{ serverClock }}</small>
        <br /><small>命令入口：HTTP / Agent，WebSocket 只读</small>
      </div>
    </aside>

    <main class="workspace">
      <header class="topbar">
        <div><span class="breadcrumb">拍卖间</span> / <strong>{{ heading }}</strong></div>
        <div class="top-actions">
          <span class="mock-badge">LIVE API · {{ apiBase }}</span>
          <button v-if="!store.loggedIn" class="outline-button" @click="openLogin">登录</button>
          <div v-else class="user-button">
            <span class="avatar">{{ initial }}</span>
            {{ store.currentUser?.name }} · {{ roleLabel }}
            <button class="small-button" @click="store.logout()">退出</button>
          </div>
        </div>
      </header>

      <div class="content">
        <!-- ── 未登录 ─────────────────────────────────────────────── -->
        <section v-if="!store.loggedIn" class="empty-wide">
          <p class="kicker">AUTH REQUIRED</p>
          <h2>请先登录</h2>
          <p class="muted">
            所有接口都需要 JWT（<code>Authorization: Bearer …</code>）。
            接口文档见 <code>docs/openapi.yaml</code>，演示账号见 README。
          </p>
          <button class="primary-button" @click="openLogin">登录 / 选择演示账号</button>
        </section>

        <!-- ── 拍卖大厅 ───────────────────────────────────────────── -->
        <template v-else-if="view === 'auctions'">
          <section v-if="!store.current" class="auction-list">
            <div class="page-heading">
              <div>
                <p class="kicker">AUCTION HALL</p>
                <h1>拍卖大厅</h1>
                <p class="muted">数据来自 <code>GET /auctions</code>；进入详情后由 WebSocket 推送增量。</p>
              </div>
              <div class="wallet-chip">
                <span>可用余额</span>
                <b>◎ {{ money(store.wallet?.availableBalance) }}</b>
              </div>
            </div>

            <div class="section-heading">
              <h2>进行中</h2>
              <span>{{ store.runningAuctions.length }} 场</span>
            </div>
            <div class="auction-grid">
              <article v-for="auction in store.runningAuctions" :key="auction.id" class="auction-card" @click="open(auction)">
                <div class="card-cover olive"><span class="cover-tag">LIVE</span><span class="cover-icon">🔨</span></div>
                <div class="card-body">
                  <div class="card-status">
                    <span class="status-pill running"><i></i>{{ statusLabel(auction.status) }}</span>
                    <span class="time-left">{{ auction.participantCount }} 人参与</span>
                  </div>
                  <h3>{{ auction.title }}</h3>
                  <p>{{ auction.description || '—' }}</p>
                  <div class="card-price">
                    <span>当前价</span><strong>◎ {{ money(auction.currentPrice) }}</strong>
                  </div>
                  <div class="card-foot">
                    <span>领先 {{ displayName(auction.leader ? shortId(auction.leader) : null) }}</span>
                    <span class="icon-button">→</span>
                  </div>
                </div>
              </article>
              <p v-if="!store.runningAuctions.length" class="empty-wide">
                {{ store.listLoading ? '加载中…' : '暂无进行中的拍卖' }}
              </p>
            </div>

            <div class="lower">
              <div class="section-heading">
                <h2>草稿 / 已结束</h2>
                <span>{{ store.otherAuctions.length }} 场</span>
              </div>
              <div class="compact-list">
                <button v-for="auction in store.otherAuctions" :key="auction.id" @click="open(auction)">
                  <span class="compact-icon">📦</span>
                  <span class="compact-title">
                    <b>{{ auction.title }}</b>
                    <small>
                      {{ statusLabel(auction.status) }} · 版本 seq {{ auction.seq }}
                      <template v-if="startHint(auction.startsAt)"> · {{ startHint(auction.startsAt) }}</template>
                    </small>
                  </span>
                  <strong>◎ {{ money(auction.currentPrice) }}</strong>
                </button>
                <p v-if="!store.otherAuctions.length" class="empty">暂无其他拍卖</p>
              </div>
            </div>
          </section>

          <!-- ── 详情 ─────────────────────────────────────────────── -->
          <section v-else class="auction-detail">
            <button class="back-link" @click="back">← 返回拍卖大厅</button>
            <div class="detail-grid">
              <section class="hero-panel">
                <div class="hero-top">
                  <span :class="['status-pill', store.current.status.toLowerCase()]">
                    <i></i>{{ statusLabel(store.current.status) }}
                  </span>
                  <span class="seq">seq {{ store.current.seq }} · {{ store.feedLabel }}</span>
                </div>
                <h2>{{ store.current.title }}</h2>
                <p class="muted">{{ store.current.description || '—' }}</p>
                <div class="hero-price">
                  <span>当前价</span>
                  <strong>◎ {{ money(store.current.currentPrice) }}</strong>
                  <div class="leader">
                    领先者<b>{{ store.isMyLead ? '你' : displayName(store.current.leaderAnon) }}</b>
                  </div>
                </div>
                <div class="countdown">
                  剩余 <b>{{ store.remainingLabel }}</b>
                  <small>
                    起拍 ◎ {{ money(store.current.startPrice) }} · 最小加价 ◎ {{ money(store.current.minIncrement) }} ·
                    已延时 {{ store.current.extensionCount }} 次 · {{ store.current.participantCount }} 人参与
                  </small>
                </div>
                <!-- 预告开拍：开拍是服务端到点任务做的，这里不提供“手动倒计时结束即开始”的按钮（D-35）。 -->
                <div v-if="store.current.status === 'DRAFT' && store.current.startsAt" class="start-banner">
                  <b>预告开拍</b>
                  <span>
                    {{ new Date(store.current.startsAt).toLocaleString('zh-CN') }}
                    （{{ startHint(store.current.startsAt) }}），到时由服务端自动开放竞价。
                  </span>
                </div>
                <div v-if="store.inFinalGameWindow" class="final-game-banner" role="status">
                  <b>博弈时间</b>
                  <span>
                    最后 {{ store.current.finalGameWindowSeconds }} 秒：AI 代理已禁止出价，真人仍可继续叫价，请把握机会。
                  </span>
                </div>
              </section>

              <section class="bid-panel">
                <div class="panel-label">
                  出价
                  <span v-if="store.joined" class="joined">已加入</span>
                </div>
                <label for="bid-amount">金额（最低 ◎ {{ money(store.nextBid) }}）</label>
                <div class="bid-input">
                  <span>◎</span>
                  <input id="bid-amount" v-model.number="bidAmount" type="number" :min="store.nextBid" inputmode="numeric" />
                </div>
                <p class="hint">金额必须 ≥ 当前价 + 最小加价；同一金额重试会复用同一个幂等键。</p>
                <p v-if="store.inFinalGameWindow" class="hint final-game-hint">
                  博弈时间内 Agent 已退场，只有真人能出价；延时不会让本场离开博弈时间。
                </p>
                <div class="bid-actions">
                  <button class="small-button" @click="bump(1)">+ 最小加价</button>
                  <button class="small-button" @click="bump(2)">+ 两倍</button>
                </div>
                <button
                  v-if="!store.joined && store.current.status === 'RUNNING'"
                  class="primary-button full"
                  @click="store.joinCurrent()"
                >
                  加入本场拍卖
                </button>
                <button v-else class="primary-button full" :disabled="!store.canBid" @click="submitBid">
                  {{ store.bidInFlight ? '提交中…' : store.current.status === 'RUNNING' ? '提交出价' : '本场已结束' }}
                </button>
                <div class="connection">
                  {{ store.feedLabel }}
                  <template v-if="store.feedDetail.attempt">（第 {{ store.feedDetail.attempt }} 次重连）</template>
                </div>
              </section>
            </div>

            <div class="detail-columns">
              <section class="table-panel">
                <div class="panel-heading">
                  <h3>出价记录</h3>
                  <span>{{ store.bids.length }} 条 · 匿名标识</span>
                </div>
                <div v-for="(bid, index) in store.bids" :key="bid.id" class="bid-row">
                  <span class="bid-avatar">{{ index === 0 ? '①' : '·' }}</span>
                  <div>
                    <b>{{ bidderLabel(bid.userId) }}</b>
                    <small>{{ new Date(bid.serverTime).toLocaleTimeString('zh-CN') }} · seq {{ bid.seq }}</small>
                  </div>
                  <strong>◎ {{ money(bid.amount) }}</strong>
                </div>
                <p v-if="!store.bids.length" class="empty">还没有人出价</p>
              </section>

              <section class="table-panel">
                <div class="panel-heading"><h3>结算</h3></div>
                <template v-if="store.settlement">
                  <div class="summary-row"><span>结果</span><b>{{ statusLabel(store.settlement.status) }}</b></div>
                  <div class="summary-row"><span>原因</span><b>{{ store.settlement.reason }}</b></div>
                  <div class="summary-row"><span>成交价</span><b>◎ {{ money(store.settlement.finalPrice) }}</b></div>
                  <div class="result-box">
                    中标者
                    <b>
                      {{ store.settlement.winner ? displayName(store.anonOf(store.settlement.winner ?? null)) : '无人中标' }}
                      <span
                        v-if="store.settlement.winnerType"
                        class="actor-badge"
                        :class="store.settlement.winnerType.toLowerCase()"
                      >{{ store.settlement.winnerType === 'AGENT' ? 'AI 代理' : '真人' }}</span>
                    </b>
                  </div>
                </template>
                <p v-else class="empty">
                  {{ store.current.status === 'RUNNING' ? '竞价结束后由服务端到点结算' : '结果查询中（未结算时接口返回 404）' }}
                </p>
              </section>
            </div>
          </section>
        </template>

        <!-- ── 我的资金 ───────────────────────────────────────────── -->
        <template v-else-if="view === 'wallet'">
          <div class="page-heading">
            <div>
              <p class="kicker">WALLET</p>
              <h1>我的资金</h1>
              <p class="muted">来自 <code>GET /wallets/me</code> 与 <code>GET /wallets/me/ledger</code>，前端不做任何加减。</p>
            </div>
          </div>
          <div class="balance-grid">
            <div><span>总额</span><b>◎ {{ money(store.wallet?.totalBalance) }}</b></div>
            <div><span>冻结（在拍）</span><b class="orange">◎ {{ money(store.wallet?.frozenAmount) }}</b></div>
            <div><span>可用</span><b class="green">◎ {{ money(store.wallet?.availableBalance) }}</b></div>
          </div>
          <section class="table-panel ledger">
            <div class="panel-heading">
              <h3>资金流水</h3>
              <button class="small-button" @click="store.refreshWallet()">刷新</button>
            </div>
            <div v-for="entry in store.ledger" :key="entry.id" class="ledger-row">
              <span :class="['ledger-icon', entry.type.toLowerCase()]">{{ entry.type.slice(0, 1) }}</span>
              <div>
                <b>{{ settledLabel(entry.type) }}</b>
                <small>{{ new Date(entry.createdAt).toLocaleString('zh-CN') }} · {{ shortId(entry.auctionId) }}</small>
              </div>
              <strong>◎ {{ money(entry.amount) }}</strong>
              <span class="actor-badge" :class="entry.actorType.toLowerCase()">
                {{ entry.actorType === 'AGENT' ? 'AI' : '真人' }}
              </span>
            </div>
            <p v-if="!store.ledger.length" class="empty">暂无流水</p>
          </section>
        </template>

        <!-- ── 运营台 ─────────────────────────────────────────────── -->
        <template v-else-if="view === 'admin'">
          <div class="page-heading">
            <div>
              <p class="kicker">OPERATIONS</p>
              <h1>运营台</h1>
              <p class="muted">创建 / 开始 / 取消走 <code>/admin/auctions</code>；结算由服务端到点任务执行。</p>
            </div>
          </div>
          <div class="admin-layout">
            <form class="form-panel" @submit.prevent="submitCreate">
              <div class="panel-label">新建拍品</div>
              <label>名称<input v-model="newTitle" type="text" maxlength="120" /></label>
              <label>描述<input v-model="newDescription" type="text" maxlength="2000" /></label>
              <label>起拍价<input v-model.number="newStartPrice" type="number" min="1" /></label>
              <label>最小加价<input v-model.number="newMinIncrement" type="number" min="1" /></label>
              <label>时长（秒）<input v-model.number="newDuration" type="number" min="10" max="86400" /></label>
              <!-- 预告开拍可选：服务端会到点自动开拍（D-35）；不填就等运营手动点「开始」。 -->
              <label>预告开拍（可选）<input v-model="newStartsAt" type="datetime-local" /></label>
              <p class="hint">填了预告时间，到点由服务端自动开拍；不填就等这里手动点「开始」。</p>
              <button class="primary-button full" type="submit">创建拍品</button>
            </form>
            <section class="table-panel">
              <div class="panel-heading"><h3>拍品管理</h3><span>共 {{ store.auctions.length }} 件</span></div>
              <div v-for="auction in store.auctions" :key="auction.id" class="admin-row">
                <div>
                  <b>{{ auction.title }}</b>
                  <small>
                    {{ statusLabel(auction.status) }} · seq {{ auction.seq }} · ◎ {{ money(auction.currentPrice) }}
                    <template v-if="startHint(auction.startsAt)"> · {{ startHint(auction.startsAt) }}</template>
                  </small>
                </div>
                <button v-if="auction.status === 'DRAFT'" class="small-button" @click="store.startAuction(auction.id)">开始</button>
                <button
                  v-if="auction.status === 'DRAFT' || auction.status === 'RUNNING'"
                  class="small-button danger"
                  @click="store.cancelAuction(auction.id)"
                >
                  取消
                </button>
                <button class="small-button" @click="store.loadAuctionLedger(auction.id)">流水</button>
              </div>
              <p v-if="!store.auctions.length" class="empty">暂无拍品</p>
            </section>
          </div>
          <section class="table-panel admin-ledger">
            <div class="panel-heading">
              <h3>场次流水（含成交主体）</h3>
              <span v-if="store.adminLedgerAuctionId">
                {{ shortId(store.adminLedgerAuctionId) }} · {{ store.adminLedger.length }} 条
                <template v-if="store.adminLedgerLoading"> · 加载中…</template>
              </span>
            </div>
            <p v-if="!store.adminLedgerAuctionId" class="empty">
              在上方拍品点「流水」：这里按场次列出每一笔资金动作及其主体（AI / 真人）。仅管理员可见。
            </p>
            <template v-else>
              <div v-for="entry in store.adminLedger" :key="entry.id" class="ledger-row">
                <span :class="['ledger-icon', entry.type.toLowerCase()]">{{ entry.type.slice(0, 1) }}</span>
                <div>
                  <b>{{ settledLabel(entry.type) }}</b>
                  <small>{{ new Date(entry.createdAt).toLocaleString('zh-CN') }} · {{ shortId(entry.auctionId) }}</small>
                </div>
                <span class="actor-badge" :class="entry.actorType.toLowerCase()">
                  {{ entry.actorType === 'AGENT' ? 'AI 代理' : '真人' }}
                </span>
                <strong>◎ {{ money(entry.amount) }}</strong>
              </div>
              <p v-if="!store.adminLedger.length" class="empty">该场暂无流水</p>
            </template>
          </section>
        </template>

        <!-- ── 我的 AI 代理 ───────────────────────────────────────── -->
        <template v-else>
          <div class="page-heading">
            <div>
              <p class="kicker">MY AI PROXY</p>
              <h1>我的 AI 代理</h1>
              <p class="muted">
                选一场<b>正在进行或还没开始</b>的拍卖，服务端的 AI 就替你去竞拍：在预算内按最小加价跟价，
                到点自动进场，随时可以撤销。花的仍然是<b>你自己钱包里的钱</b>——不用写任何代码。
              </p>
            </div>
            <div class="agent-rule-chip">
              <span>⚔ 博弈时间</span>
              <small>每场结束前 {{ store.finalGameWindowSeconds ?? '—' }} 秒，AI 一律禁止出价（真人不受影响）</small>
            </div>
          </div>

          <!-- 明文只出现这一次 -->
          <section v-if="store.issuedAgentToken" class="token-reveal">
            <div class="token-reveal-head">
              <b>「{{ store.issuedAgentToken.name }}」已创建</b>
              <button class="small-button" @click="store.dismissIssuedAgentToken()">我已保存，关闭</button>
            </div>
            <p class="muted">这是明文 Token <b>唯一一次</b>出现。关掉后服务端只剩下摘要，谁也取不回来。</p>
            <div class="token-line">
              <code>{{ store.issuedAgentToken.token }}</code>
              <button class="small-button" @click="copyAgentToken">{{ agentCopied ? '已复制' : '复制' }}</button>
            </div>
            <small class="muted">
              有效期至 {{ new Date(store.issuedAgentToken.expiresAt).toLocaleString('zh-CN') }} ·
              tokenId {{ shortId(store.issuedAgentToken.tokenId) }}
            </small>
          </section>

          <!-- 创建 + 在管：这是主路径（D-36） -->
          <div class="agent-layout">
            <section class="table-panel">
              <div class="panel-heading">
                <h3>创建 AI 代理</h3>
                <span>{{ store.myAgentProxies.length }} 个在管</span>
              </div>
              <form class="agent-form" @submit.prevent="submitProxy">
                <label>
                  拍卖场次
                  <select v-model="proxyAuctionId" @change="pickProxyAuction">
                    <option value="">请选择一场拍卖…</option>
                    <option
                      v-for="auction in store.proxyCandidates"
                      :key="auction.id"
                      :value="auction.id"
                      :disabled="store.hasProxyFor(auction.id)"
                    >
                      {{ auction.title }} · {{ statusLabel(auction.status) }} · 当前价 ◎ {{ money(auction.currentPrice) }}{{ store.hasProxyFor(auction.id) ? '（已挂代理）' : '' }}
                    </option>
                  </select>
                </label>
                <label>预算上限（◎）<input v-model.number="proxyBudget" type="number" min="1" step="1" /></label>
                <p class="hint">
                  可用余额 ◎ {{ money(store.wallet?.availableBalance) }}。AI 只在预算内按最小加价跟价，触顶就停手并提醒你一次；
                  创建时<b>不冻结</b>资金，只有真正出价才动钱。
                </p>
                <button class="primary-button full" type="submit" :disabled="proxyBusy">
                  {{ proxyBusy ? '创建中…' : '创建 AI 代理' }}
                </button>
              </form>
              <p v-if="!store.proxyCandidates.length" class="hint">
                现在没有可挂代理的场次：只有<b>草稿</b>或<b>进行中</b>的拍卖才能创建。
              </p>

              <div class="panel-heading proxy-list-head">
                <h3>在管的代理</h3>
                <span>{{ store.myAgentProxies.length }} 个</span>
              </div>
              <div v-for="proxy in store.myAgentProxies" :key="proxy.proxyId" class="proxy-row">
                <span class="compact-icon">🤖</span>
                <div class="proxy-main">
                  <b>{{ proxy.auctionTitle ?? shortId(proxy.auctionId) }}</b>
                  <small>
                    {{ statusLabel(proxy.auctionStatus) }} · 预算 ◎ {{ money(proxy.budgetLimit) }} · 已出价 {{ proxy.bidCount }} 次<template
                      v-if="proxy.lastBidAmount !== null && proxy.lastBidAmount !== undefined"
                    >（最近 ◎ {{ money(proxy.lastBidAmount) }}）</template>
                  </small>
                  <small v-if="proxy.status === 'PENDING'">还没开拍：时间一到它会自动进场。</small>
                  <small v-else-if="proxy.status === 'BIDDING'">
                    {{ proxy.leading ? '我的 AI 正领先。' : `对手加价后它的下一手是 ◎ ${money(proxy.nextBidAmount)}。` }}
                    当前价 ◎ {{ money(proxy.currentPrice) }}。
                  </small>
                  <small v-else-if="proxy.status === 'BUDGET_REACHED'">
                    已到预算上限并停手（当前价 ◎ {{ money(proxy.currentPrice) }}）：想继续就得自己出价。
                  </small>
                  <small v-else-if="proxy.status === 'FINISHED'">
                    {{ proxy.won ? `拍下了，成交价 ◎ ${money(proxy.finalPrice)}` : '没有拍下' }}。
                  </small>
                  <small v-else>已由你撤销。</small>
                </div>
                <span class="agent-status" :class="proxyStatusMark(proxy.status)">{{ proxyStatusLabel(proxy.status) }}</span>
                <button
                  v-if="proxy.status !== 'FINISHED' && proxy.status !== 'REVOKED'"
                  class="small-button danger"
                  @click="store.revokeAgentProxy(proxy.proxyId)"
                >
                  撤销
                </button>
              </div>
              <p v-if="!store.myAgentProxies.length" class="empty">
                {{ store.myAgentProxiesLoading ? '加载中…' : '还没有 AI 代理。选一场拍卖、填个预算，剩下的交给它。' }}
              </p>
            </section>

            <section class="table-panel">
              <div class="panel-heading"><h3>它会怎么动</h3></div>
              <ol class="agent-steps">
                <li>创建时<b>不冻结</b>任何钱，只校验预算不超过可用余额。</li>
                <li>拍卖一开拍（或已经在进行中）就以<b>最小加价</b>跟价；自己领先时不重复抬价。</li>
                <li>出价到达<b>预算上限</b>就停手，页面上提醒你一次，此后不再动。</li>
                <li>结束前 {{ store.finalGameWindowSeconds ?? '—' }} 秒进入<b>博弈时间</b>，AI 一律禁止出价，真人可以继续叫价。</li>
                <li>拍卖结束自动结算，输赢与成交价会显示在左边那张卡片上。</li>
              </ol>
              <p class="hint">隐私：只有你自己看得到这些代理；大厅里不会暴露谁在用 AI。</p>
            </section>
          </div>

          <!-- 高级：自己接程序（D-34）。默认收起——它只对写代码的用户有意义。 -->
          <section class="table-panel advanced">
            <div class="panel-heading">
              <h3>高级：自己写程序接入（Agent Token）</h3>
              <button class="small-button" @click="advancedOpen = !advancedOpen">{{ advancedOpen ? '收起' : '展开' }}</button>
            </div>
            <p class="hint">
              不写代码就忽略这一段：上面的托管代理已经够用。这一段是给“自制机器人”的用户：发一枚独立凭证，
              让<b>你自己跑的程序</b>拿它访问 Agent 专用端口。
            </p>
            <template v-if="advancedOpen">
          <div class="agent-layout">
            <section class="table-panel">
              <div class="panel-heading">
                <h3>我的授权</h3>
                <span class="agent-head-actions">
                  {{ store.myAgentTokens.length }} 枚
                  <button class="small-button" @click="agentFormOpen = !agentFormOpen">
                    {{ agentFormOpen ? '收起' : '＋ 新建授权' }}
                  </button>
                </span>
              </div>

              <form v-if="agentFormOpen" class="agent-form" @submit.prevent="submitAgentToken">
                <label>给它起个名字<input v-model="agentName" type="text" maxlength="80" placeholder="例如：我的抄底机器人" /></label>
                <div class="agent-field">
                  <span class="agent-field-label">它能做什么</span>
                  <label class="check">
                    <input type="checkbox" :checked="agentScopes.includes('auction:read')" @change="toggleAgentScope('auction:read')" />
                    读取拍卖快照
                  </label>
                  <label class="check">
                    <input type="checkbox" :checked="agentScopes.includes('auction:bid')" @change="toggleAgentScope('auction:bid')" />
                    代替我出价
                  </label>
                </div>
                <div class="agent-field">
                  <span class="agent-field-label">只能在这些场次里活动</span>
                  <div class="auction-picks">
                    <label v-for="auction in store.auctions" :key="auction.id" class="check">
                      <input
                        type="checkbox"
                        :checked="agentAuctionIds.includes(auction.id)"
                        @change="toggleAgentAuction(auction.id)"
                      />
                      {{ auction.title }} <small class="muted">{{ statusLabel(auction.status) }}</small>
                    </label>
                    <p v-if="!store.auctions.length" class="empty">暂无拍品可授权。</p>
                  </div>
                  <p class="hint">一个都不选 = 这份授权对任何场次都不可用（默认拒绝，不是“全部允许”）。</p>
                </div>
                <div class="agent-inline">
                  <label>有效期至<input v-model="agentExpiresAt" type="datetime-local" /></label>
                  <label>每分钟最多请求<input v-model.number="agentRateLimit" type="number" min="1" max="6000" placeholder="默认 60" /></label>
                </div>
                <button class="primary-button full" type="submit">创建授权</button>
              </form>

              <div v-for="token in store.myAgentTokens" :key="token.tokenId" class="agent-token-row">
                <div class="agent-token-main">
                  <b>{{ token.name }}</b>
                  <small>
                    {{ token.auctionIds.length }} 场 · {{ token.scopes.join(' / ') }} · 每分钟 {{ token.rateLimitPerMinute }} 次 ·
                    至 {{ new Date(token.expiresAt).toLocaleString('zh-CN') }}
                  </small>
                </div>
                <span class="agent-status" :class="token.status.toLowerCase()">{{ agentStatusLabel(token.status) }}</span>
                <button v-if="token.status === 'ACTIVE'" class="small-button danger" @click="store.revokeMyAgentToken(token.tokenId)">
                  吊销
                </button>
              </div>
              <p v-if="!store.myAgentTokens.length" class="empty">
                {{ store.myAgentTokensLoading ? '加载中…' : '还没有授权。点「＋ 新建授权」给你的程序一把钥匙。' }}
              </p>
            </section>

            <section class="table-panel">
              <div class="panel-heading"><h3>怎么把它交给你的程序</h3></div>
              <ol class="agent-steps">
                <li>把上面那串 Token 存进程序的环境变量 <code>AUCTION_AGENT_TOKEN</code>，不要写进代码仓库。</li>
                <li>你的程序访问 <b>Agent 专用地址</b> <code>:8090</code>，带上 <code>Authorization: Bearer &lt;Token&gt;</code>，而不是你的登录令牌。</li>
                <li>未带凭证 401、越权 403、超过频率 429；被吊销后立即失效。</li>
                <li>想照着跑一遍：<code>python tools/agent_sim.py --agent-only --auction-id &lt;场次 ID&gt;</code>（该脚本只读环境变量，不会让你在终端粘贴 Token）。</li>
              </ol>
              <p class="hint">
                隐私边界：你只能看到自己的授权；哪一笔成交是 AI、哪一笔是真人，只在自己的
                <b>资金流水</b>里以徽章显示。
              </p>
            </section>
          </div>
            </template>
          </section>

          <!-- 运营总览 -->
          <section v-if="store.isAdmin" class="table-panel">
            <div class="panel-heading">
              <h3>全部托管代理（运营总览）</h3>
              <span>{{ store.allAgentProxies.length }} 个 · 仅管理员可见 · 只读</span>
            </div>
            <div v-for="proxy in store.allAgentProxies" :key="proxy.proxyId" class="agent-token-row">
              <div class="agent-token-main">
                <b>{{ proxy.auctionTitle ?? shortId(proxy.auctionId) }}</b>
                <small>
                  归属 {{ shortId(proxy.ownerUserId) }} · {{ statusLabel(proxy.auctionStatus) }} ·
                  预算 ◎ {{ money(proxy.budgetLimit) }} · 已出价 {{ proxy.bidCount }} 次
                </small>
              </div>
              <span class="agent-status" :class="proxyStatusMark(proxy.status)">{{ proxyStatusLabel(proxy.status) }}</span>
            </div>
            <p v-if="!store.allAgentProxies.length" class="empty">
              {{ store.allAgentProxiesLoading ? '加载中…' : '当前没有任何在管的托管代理。' }}
            </p>
          </section>
          <section v-if="store.isAdmin" class="table-panel">
            <div class="panel-heading">
              <h3>全部授权（运营总览）</h3>
              <span>{{ store.allAgentTokens.length }} 枚 · 仅管理员可见 · 不含明文</span>
            </div>
            <div v-for="token in store.allAgentTokens" :key="token.tokenId" class="agent-token-row">
              <div class="agent-token-main">
                <b>{{ token.name }}</b>
                <small>{{ token.agentUserId }} · {{ token.auctionIds.length }} 场 · {{ token.scopes.join(' / ') }}</small>
              </div>
              <span class="agent-status" :class="token.status.toLowerCase()">{{ agentStatusLabel(token.status) }}</span>
            </div>
            <p v-if="!store.allAgentTokens.length" class="empty">
              {{ store.allAgentTokensLoading ? '加载中…' : '当前没有任何 Agent 授权。' }}
            </p>
          </section>
        </template>
      </div>
    </main>
  </div>

  <div v-if="loginOpen && !store.loggedIn" class="modal-backdrop" @click.self="loginOpen = false">
    <form class="login-modal" @submit.prevent="submitLogin">
      <button class="close" type="button" @click="loginOpen = false">×</button>
      <h2>登录</h2>
      <p class="muted">JWT 由服务端签发，前端只保存令牌与过期时间。</p>
      <label>邮箱<input v-model="email" type="email" autocomplete="username" /></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" /></label>
      <button class="primary-button full" type="submit" :disabled="loginBusy">
        {{ loginBusy ? '登录中…' : '登录' }}
      </button>
      <p class="hint">演示账号（点击填入）</p>
      <button
        v-for="account in store.demoAccounts"
        :key="account.email"
        class="persona"
        type="button"
        @click="fillDemo(account)"
      >
        <span class="avatar">{{ account.label.slice(-1) }}</span>
        <span><b>{{ account.label }}</b><small>{{ account.email }}</small></span>
        <span>填入</span>
      </button>
    </form>
  </div>

  <div v-if="store.notice" class="toast" @click="store.clearNotice()">{{ store.notice.text }}</div>
</template>
