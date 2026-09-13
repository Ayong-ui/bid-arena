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
let timer: number | undefined

const apiBase = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

onMounted(async () => {
  await store.restore()
  timer = window.setInterval(() => store.tick(), 1_000)
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
  store.closeAuction()
})

const heading = computed(() => {
  if (view.value === 'wallet') return '我的资金'
  if (view.value === 'admin') return '运营台'
  if (view.value === 'agent') return '智能体接入'
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
  const ok = await store.createAuction({
    title: newTitle.value.trim(),
    description: newDescription.value.trim() || undefined,
    startPrice: newStartPrice.value,
    minIncrement: newMinIncrement.value,
    durationSeconds: newDuration.value,
  })
  if (ok) {
    newTitle.value = ''
    newDescription.value = ''
  }
}

function notify(text: string): void {
  store.setNotice('error', text)
}

/** 结算由服务端的到点任务完成，前端不做任何"猜测已结束"。 */
function settledLabel(code: string): string {
  return { FREEZE: '冻结', RELEASE: '释放', SETTLE: '成交扣款' }[code] ?? code
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
        <button :class="{ active: view === 'agent' }" @click="view = 'agent'">🐍 智能体接入</button>
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
                    <small>{{ statusLabel(auction.status) }} · 版本 seq {{ auction.seq }}</small>
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
                    <b>{{ store.settlement.winner ? displayName(store.anonOf(store.settlement.winner ?? null)) : '无人中标' }}</b>
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
              <button class="primary-button full" type="submit">创建拍品</button>
            </form>
            <section class="table-panel">
              <div class="panel-heading"><h3>拍品管理</h3><span>共 {{ store.auctions.length }} 件</span></div>
              <div v-for="auction in store.auctions" :key="auction.id" class="admin-row">
                <div>
                  <b>{{ auction.title }}</b>
                  <small>{{ statusLabel(auction.status) }} · seq {{ auction.seq }} · ◎ {{ money(auction.currentPrice) }}</small>
                </div>
                <button v-if="auction.status === 'DRAFT'" class="small-button" @click="store.startAuction(auction.id)">开始</button>
                <button
                  v-if="auction.status === 'DRAFT' || auction.status === 'RUNNING'"
                  class="small-button danger"
                  @click="store.cancelAuction(auction.id)"
                >
                  取消
                </button>
              </div>
              <p v-if="!store.auctions.length" class="empty">暂无拍品</p>
            </section>
          </div>
        </template>

        <!-- ── 智能体 ─────────────────────────────────────────────── -->
        <template v-else>
          <div class="page-heading">
            <div>
              <p class="kicker">AGENT API</p>
              <h1>智能体接入</h1>
              <p class="muted">
                本页面的数据<b>不来自</b> Agent 接口：Agent API（<code>:8090</code>、Agent Token、限流）属于后续里程碑，
                当前尚未实现，因此这里不会显示任何伪造的调用记录。
              </p>
            </div>
          </div>
          <section class="table-panel">
            <div class="panel-heading"><h3>契约中已定义、但尚未实现的接口</h3><span>P5 交付</span></div>
            <div v-for="item in [
              'GET /agent/auctions —— 列出可参与的拍卖（agent:read）',
              'GET /agent/auctions/{auctionId} —— 读取快照（agent:read）',
              'GET /agent/auctions/{auctionId}/bids —— 读取出价记录（agent:read）',
              'POST /agent/auctions/{auctionId}/bids —— 由智能体出价（agent:bid）',
              'GET /agent/wallet —— 智能体钱包（agent:read）',
            ]" :key="item" class="admin-row">
              <div><b>{{ item }}</b><small>当前返回 404：路由尚未挂载</small></div>
            </div>
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
