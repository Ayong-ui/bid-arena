<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useAuctionStore, type Auction, type User } from "./store";
const store = useAuctionStore();
const view = ref<"auctions" | "wallet" | "admin" | "agent">("auctions");
const selectedId = ref<string | null>(null);
const bidAmount = ref<number | null>(null);
const toast = ref("");
const loginOpen = ref(false);
const newTitle = ref("");
const newDuration = ref(60);
const agentEnabled = ref(true);
const agentStrategy = ref("狙击加价");
const agentBidAmount = ref<number | null>(null);
const agentLogs = ref([{ time: "刚刚", action: "GET /agent/auctions/a1", result: "200 OK" }]);
let timer: number | undefined;
const selected = computed(() =>
  selectedId.value
    ? (store.auctions.find((a) => a.id === selectedId.value) ?? null)
    : null,
);
const running = computed(() =>
  store.auctions.filter((a) => a.status === "RUNNING"),
);
const drafts = computed(() =>
  store.auctions.filter((a) => a.status === "DRAFT"),
);
const finished = computed(() =>
  store.auctions.filter((a) => ["FINISHED", "CANCELLED"].includes(a.status)),
);
const nextBid = computed(() =>
  selected.value
    ? selected.value.currentPrice + selected.value.minIncrement
    : 0,
);
function notify(x: string) {
  toast.value = x;
  window.setTimeout(() => (toast.value = ""), 2400);
}
function money(x: number) {
  return x.toLocaleString("zh-CN");
}
function remaining(e: number | null) {
  if (!e) return "--";
  const s = Math.max(0, Math.ceil((e - store.now) / 1000));
  return `${Math.floor(s / 60)
    .toString()
    .padStart(2, "0")}:${(s % 60).toString().padStart(2, "0")}`;
}
function label(s: Auction["status"]) {
  return {
    DRAFT: "草稿",
    RUNNING: "进行中",
    FINISHED: "已结束",
    CANCELLED: "已取消",
  }[s];
}
function select(a: Auction) {
  selectedId.value = a.id;
  bidAmount.value = a.currentPrice + a.minIncrement;
}
function bid() {
  if (!selected.value || bidAmount.value == null) return;
  const r = store.placeBid(selected.value.id, bidAmount.value);
  notify(r.message);
  if (r.accepted) bidAmount.value = r.price + selected.value.minIncrement;
}
function join() {
  if (selected.value) notify(store.joinAuction(selected.value.id));
}
function create() {
  if (!newTitle.value.trim()) return notify("请填写拍品名称");
  const a = store.createAuction(newTitle.value.trim());
  newTitle.value = "";
  selectedId.value = a.id;
  notify("拍卖已创建，点击开始即可开放竞价");
}
function switchUser(u: User) {
  store.login(u);
  loginOpen.value = false;
  notify(`已切换为${u.name}`);
}
function runAgentBid() {
  if (!agentBidAmount.value) return notify("请填写 Agent 出价");
  const result = store.placeBid("a1", agentBidAmount.value);
  agentLogs.value.unshift({ time: "刚刚", action: "POST /agent/auctions/a1/bids", result: result.accepted ? "200 OK" : "409 " + result.message });
  notify(result.accepted ? "Agent 出价已提交" : result.message);
}
onMounted(() => (timer = window.setInterval(() => store.tick(), 1000)));
onUnmounted(() => window.clearInterval(timer));
</script>
<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">BA</span
        ><span><b>BID ARENA</b><small>拍卖间 MVP</small></span>
      </div>
      <nav>
        <button
          :class="{ active: view === 'auctions' }"
          @click="view = 'auctions'"
        >
          ◈　拍卖大厅</button
        ><button
          :class="{ active: view === 'wallet' }"
          @click="view = 'wallet'"
        >
          ▣　我的钱包</button
        ><button
          v-if="store.currentUser.role === 'ADMIN'"
          :class="{ active: view === 'admin' }"
          @click="view = 'admin'"
        >
          ⚙　管理控制台
        </button>
        <button
          :class="{ active: view === 'agent' }"
          @click="view = 'agent'"
        >
          ◎　竞拍 Agent
        </button>
      </nav>
      <div class="sidebar-note">
        <span class="live-dot"></span>本地 Mock 模式<br /><small
          >数据保存在浏览器中</small
        >
      </div>
    </aside>
    <main class="workspace">
      <header class="topbar">
        <div>
          <span class="breadcrumb">BID ARENA / </span
          ><strong>{{
            view === "auctions"
              ? "拍卖大厅"
              : view === "wallet"
                ? "我的钱包"
              : view === "agent"
                ? "竞拍 Agent"
                : "管理控制台"
          }}</strong>
        </div>
        <div class="top-actions">
          <span class="mock-badge">LOCAL MVP</span
          ><button class="user-button" @click="loginOpen = true">
            <span class="avatar">{{ store.currentUser.name[0] }}</span
            >{{ store.currentUser.name }}⌄
          </button>
        </div>
      </header>
      <section v-if="view === 'auctions'" class="content">
        <div class="page-heading">
          <div>
            <p class="kicker">LIVE MARKETPLACE</p>
            <h1>发现正在发生的竞价</h1>
            <p class="muted">实时观察拍卖进度，出价后等待全场结果。</p>
          </div>
          <div class="wallet-chip">
            <span>可用余额</span><b>◎ {{ money(store.wallet.available) }}</b>
          </div>
        </div>
        <div v-if="selected" class="auction-detail">
          <button class="back-link" @click="selectedId = null">
            ← 返回拍卖列表
          </button>
          <div class="detail-grid">
            <article class="hero-panel">
              <div class="hero-top">
                <span class="status-pill" :class="selected.status.toLowerCase()"
                  ><i />{{ label(selected.status) }}</span
                ><span class="seq"
                  >SEQ {{ selected.seq.toString().padStart(3, "0") }}</span
                >
              </div>
              <h2>{{ selected.title }}</h2>
              <p class="muted">{{ selected.description }}</p>
              <div class="hero-price">
                <span>当前最高价</span
                ><strong>◎ {{ money(selected.currentPrice) }}</strong>
                <div class="leader">
                  领先者
                  <b>{{
                    selected.leaderId
                      ? store.userName(selected.leaderId)
                      : "暂无出价"
                  }}</b>
                </div>
              </div>
              <div class="countdown">
                <span>距离结束</span><b>{{ remaining(selected.endsAt) }}</b
                ><small>延时 {{ selected.extensionCount }} / 3 次</small>
              </div>
            </article>
            <article class="bid-panel">
              <div class="panel-label">
                参与竞价
                <span
                  v-if="selected.participants.includes(store.currentUser.id)"
                  class="joined"
                  >已加入</span
                >
              </div>
              <button
                v-if="
                  !selected.participants.includes(store.currentUser.id) &&
                  selected.status === 'RUNNING'
                "
                class="outline-button full"
                @click="join"
              >
                加入本场拍卖</button
              ><template v-else
                ><label>你的出价</label>
                <div class="bid-input">
                  <span>◎</span
                  ><input
                    v-model.number="bidAmount"
                    type="number"
                    :min="nextBid"
                    :step="selected.minIncrement"
                  /><span>积分</span>
                </div>
                <p class="hint">
                  最低出价 ◎ {{ money(nextBid) }} · 每次至少加价
                  {{ selected.minIncrement }}
                </p>
                <button
                  class="primary-button full"
                  :disabled="selected.status !== 'RUNNING'"
                  @click="bid"
                >
                  提交出价　↗
                </button></template
              >
              <div class="connection">
                <span class="live-dot" />实时状态已同步
              </div>
            </article>
          </div>
          <div class="detail-columns">
            <article class="table-panel">
              <div class="panel-heading">
                <h3>出价记录</h3>
                <span>{{ selected.bids.length }} 次出价</span>
              </div>
              <div
                v-for="item in [...selected.bids].reverse()"
                :key="item.id"
                class="bid-row"
              >
                <span class="bid-avatar">{{ item.userName[0] }}</span>
                <div>
                  <b>{{ item.userName }}</b
                  ><small>{{ item.time }}</small>
                </div>
                <strong>◎ {{ money(item.amount) }}</strong>
              </div>
              <div v-if="!selected.bids.length" class="empty">
                还没有出价，成为第一个竞拍者。
              </div>
            </article>
            <article class="table-panel summary">
              <div class="panel-heading"><h3>本场信息</h3></div>
              <div class="summary-row">
                <span>起拍价</span><b>◎ {{ money(selected.startPrice) }}</b>
              </div>
              <div class="summary-row">
                <span>参与人数</span
                ><b>{{ selected.participants.length }} 人</b>
              </div>
              <div v-if="selected.status === 'FINISHED'" class="result-box">
                <span>拍卖结果</span
                ><b>{{
                  !selected.leaderId
                    ? "无人出价，本场流拍"
                    : selected.leaderId === store.currentUser.id
                      ? "恭喜，你赢得了拍品"
                      : `赢家：${store.userName(selected.leaderId)}`
                }}</b>
              </div>
            </article>
          </div>
        </div>
        <template v-else
          ><div class="section-heading">
            <h2>进行中的拍卖</h2>
            <span>{{ running.length }} 场进行中</span>
          </div>
          <div class="auction-grid">
            <article
              v-for="a in running"
              :key="a.id"
              class="auction-card"
              @click="select(a)"
            >
              <div class="card-cover" :class="a.tone">
                <span class="cover-tag">LIVE AUCTION</span
                ><span class="cover-icon">{{ a.icon }}</span>
              </div>
              <div class="card-body">
                <div class="card-status">
                  <span class="status-pill running"><i />进行中</span
                  ><span class="time-left">{{ remaining(a.endsAt) }}</span>
                </div>
                <h3>{{ a.title }}</h3>
                <p>{{ a.description }}</p>
                <div class="card-price">
                  <span>当前价</span
                  ><strong>◎ {{ money(a.currentPrice) }}</strong>
                </div>
                <div class="card-foot">
                  <span
                    >{{ a.participants.length }} 位参与者 · 延时
                    {{ a.extensionCount }}/3</span
                  ><button class="icon-button">→</button>
                </div>
              </div>
            </article>
            <div v-if="!running.length" class="empty-wide">
              当前没有进行中的拍卖
            </div>
          </div>
          <div class="section-heading lower">
            <h2>其他拍卖</h2>
            <span>{{ drafts.length + finished.length }} 场</span>
          </div>
          <div class="compact-list">
            <button
              v-for="a in [...drafts, ...finished]"
              :key="a.id"
              @click="select(a)"
            >
              <span class="compact-icon">{{ a.icon }}</span
              ><span class="compact-title"
                ><b>{{ a.title }}</b
                ><small>{{ a.description }}</small></span
              ><span class="status-pill" :class="a.status.toLowerCase()"
                ><i />{{ label(a.status) }}</span
              ><strong>◎ {{ money(a.currentPrice) }}</strong
              ><span>→</span>
            </button>
          </div></template
        >
      </section>
      <section v-else-if="view === 'wallet'" class="content">
        <div class="page-heading">
          <div>
            <p class="kicker">YOUR ACCOUNT</p>
            <h1>我的钱包</h1>
            <p class="muted">查看余额、冻结积分和竞价流水。</p>
          </div>
        </div>
        <div class="balance-grid">
          <div>
            <span>总余额</span><b>◎ {{ money(store.wallet.total) }}</b>
          </div>
          <div>
            <span>冻结中</span
            ><b class="orange">◎ {{ money(store.wallet.frozen) }}</b>
          </div>
          <div>
            <span>可用余额</span
            ><b class="green">◎ {{ money(store.wallet.available) }}</b>
          </div>
        </div>
        <article class="table-panel ledger">
          <div class="panel-heading">
            <h3>资金流水</h3>
            <span>本地模拟记录</span>
          </div>
          <div
            v-for="item in store.wallet.ledger"
            :key="item.id"
            class="ledger-row"
          >
            <span class="ledger-icon" :class="item.type">{{
              item.type === "freeze" ? "↑" : item.type === "release" ? "↓" : "✓"
            }}</span>
            <div>
              <b>{{ item.label }}</b
              ><small>{{ item.time }}<template v-if="item.auctionId"> · {{ store.auctions.find((a) => a.id === item.auctionId)?.title }}</template></small>
            </div>
            <strong :class="item.type === 'freeze' ? 'orange' : 'green'"
              >{{ item.type === "freeze" ? "-" : "+" }}◎
              {{ money(item.amount) }}</strong
            >
          </div>
        </article>
      </section>
      <section v-else-if="view === 'agent'" class="content">
        <div class="page-heading"><div><p class="kicker">AUTOMATION WORKBENCH</p><h1>竞拍 Agent</h1><p class="muted">配置受限 Agent，观察授权拍卖并提交自动出价。</p></div><span class="status-pill running"><i />{{ agentEnabled ? "运行中" : "已暂停" }}</span></div>
        <div class="detail-grid">
          <article class="form-panel"><div class="panel-heading"><h3>Agent 配置</h3><span>Mock Token</span></div>
            <div class="summary-row"><span>服务端点</span><b>http://localhost:8090</b></div>
            <div class="summary-row"><span>Token</span><b>ag_••••••••9f2a</b></div>
            <label>授权拍卖<select><option>Leica M6 经典胶片相机（a1）</option><option>Mid-century 胡桃木边柜（a2）</option></select></label>
            <label>出价策略<select v-model="agentStrategy"><option>狙击加价</option><option>固定上限</option><option>人工确认</option></select></label>
            <label>最高出价<input v-model.number="agentBidAmount" type="number" placeholder="例如：320" /></label>
            <button class="primary-button full" @click="runAgentBid">提交 Agent 出价　↗</button>
            <button class="outline-button full" @click="agentEnabled = !agentEnabled">{{ agentEnabled ? "暂停 Agent" : "启用 Agent" }}</button>
          </article>
          <article class="table-panel"><div class="panel-heading"><h3>授权拍卖</h3><span>Token scope: auction:read, auction:bid</span></div>
            <div class="admin-row"><div><b>Leica M6 经典胶片相机</b><small>RUNNING · 当前价 ◎ {{ money(store.auctions[0].currentPrice) }}</small></div><span class="status-pill running"><i />可出价</span></div>
            <div class="admin-row"><div><b>Mid-century 胡桃木边柜</b><small>RUNNING · 当前价 ◎ {{ money(store.auctions[1].currentPrice) }}</small></div><span class="status-pill running"><i />可出价</span></div>
            <div class="panel-heading" style="margin-top:24px"><h3>调用日志</h3><span>最近请求</span></div>
            <div v-for="log in agentLogs" :key="log.time + log.action" class="admin-row"><div><b>{{ log.action }}</b><small>{{ log.time }}</small></div><strong>{{ log.result }}</strong></div>
          </article>
        </div>
      </section>
      <section v-else class="content">
        <div class="page-heading">
          <div>
            <p class="kicker">OPERATIONS</p>
            <h1>管理控制台</h1>
            <p class="muted">创建和控制本地模拟拍卖。</p>
          </div>
        </div>
        <div class="admin-layout">
          <article class="form-panel">
            <div class="panel-heading">
              <h3>创建新拍卖</h3>
              <span>草稿不会自动开始</span>
            </div>
            <label
              >拍品名称<input
                v-model="newTitle"
                placeholder="例如：复古胶片相机" /></label
            ><label
              >拍卖时长<select v-model.number="newDuration">
                <option :value="30">30 秒（演示）</option>
                <option :value="60">60 秒</option>
                <option :value="180">180 秒</option>
              </select></label
            ><button class="primary-button full" @click="create">
              创建拍卖　＋
            </button>
          </article>
          <article class="table-panel">
            <div class="panel-heading">
              <h3>拍卖控制</h3>
              <span>{{ drafts.length }} 个草稿</span>
            </div>
            <div
              v-for="a in [...drafts, ...running]"
              :key="a.id"
              class="admin-row"
            >
              <div>
                <b>{{ a.title }}</b
                ><small
                  >{{ label(a.status) }} · ◎ {{ money(a.currentPrice) }}</small
                >
              </div>
              <button
                v-if="a.status === 'DRAFT'"
                class="small-button"
                @click="
                  store.startAuction(a.id);
                  notify('拍卖已开始');
                "
              >
                开始</button
              ><button
                v-else
                class="small-button danger"
                @click="
                  store.cancelAuction(a.id);
                  notify('拍卖已取消');
                "
              >
                取消
              </button>
            </div>
          </article>
        </div>
        <article class="table-panel settlement-panel">
          <div class="panel-heading">
            <h3>成交记录</h3>
            <span>{{ store.settlements.length }} 条记录</span>
          </div>
          <div v-if="!store.settlements.length" class="empty">
            暂无成交记录，拍卖结束后会自动出现在这里。
          </div>
          <div
            v-for="item in store.settlements"
            :key="item.id"
            class="admin-row settlement-row"
          >
            <div>
              <b>{{ item.auctionTitle }}</b
              ><small>{{ item.settledAt }} · {{ item.reason }}</small>
            </div>
            <span>{{ item.winnerName ?? "无人出价" }}</span>
            <strong v-if="item.winnerId">◎ {{ money(item.amount) }}</strong
            ><strong v-else class="muted">--</strong>
          </div>
        </article>
      </section>
    </main>
    <div v-if="toast" class="toast">{{ toast }}</div>
    <div
      v-if="loginOpen"
      class="modal-backdrop"
      @click.self="loginOpen = false"
    >
      <div class="login-modal">
        <button class="close" @click="loginOpen = false">×</button>
        <p class="kicker">SWITCH PERSONA</p>
        <h2>切换演示账号</h2>
        <p class="muted">MVP 使用本地账号，不需要密码。</p>
        <button
          v-for="u in store.users"
          :key="u.id"
          class="persona"
          @click="switchUser(u)"
        >
          <span class="avatar">{{ u.name[0] }}</span
          ><span
            ><b>{{ u.name }}</b
            ><small>{{ u.role === "ADMIN" ? "管理员" : "竞拍者" }}</small></span
          ><span>→</span>
        </button>
      </div>
    </div>
  </div>
</template>
