import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type Role = 'ADMIN' | 'BIDDER'
export type Status = 'DRAFT' | 'RUNNING' | 'FINISHED' | 'CANCELLED'
export interface User { id: string; name: string; role: Role }
export interface Bid { id: string; userId: string; userName: string; amount: number; time: string }
export interface Auction { id: string; title: string; description: string; icon: string; tone: string; status: Status; startPrice: number; currentPrice: number; minIncrement: number; leaderId: string | null; endsAt: number | null; extensionCount: number; participants: string[]; bids: Bid[]; seq: number }
export interface Ledger { id: string; label: string; amount: number; type: 'freeze' | 'release' | 'settle'; time: string; auctionId?: string }
export interface Settlement { id: string; auctionId: string; auctionTitle: string; winnerId: string | null; winnerName: string | null; amount: number; settledAt: string; reason: '成交' | '无人出价' | '取消' }
const users: User[] = [{ id: 'admin', name: '管理员', role: 'ADMIN' }, { id: 'you', name: '林默', role: 'BIDDER' }, { id: 'buyer-b', name: '周航', role: 'BIDDER' }]
const seed = (): Auction[] => [{ id: 'a1', title: 'Leica M6 经典胶片相机', description: '1990 年代经典旁轴相机，收藏级品相', icon: '📷', tone: 'olive', status: 'RUNNING', startPrice: 100, currentPrice: 260, minIncrement: 10, leaderId: 'buyer-b', endsAt: Date.now() + 72_000, extensionCount: 1, participants: ['you', 'buyer-b'], bids: [{ id: 'b1', userId: 'you', userName: '林默', amount: 220, time: '刚刚' }, { id: 'b2', userId: 'buyer-b', userName: '周航', amount: 260, time: '1 分钟前' }], seq: 12 }, { id: 'a2', title: 'Mid-century 胡桃木边柜', description: '手工榫卯结构，温润胡桃木纹理', icon: '🪑', tone: 'rose', status: 'RUNNING', startPrice: 100, currentPrice: 180, minIncrement: 10, leaderId: 'buyer-b', endsAt: Date.now() + 168_000, extensionCount: 0, participants: ['buyer-b'], bids: [{ id: 'b3', userId: 'buyer-b', userName: '周航', amount: 180, time: '3 分钟前' }], seq: 7 }, { id: 'a3', title: '手作铜制台灯', description: '设计师限量作品，暖光氛围绝佳', icon: '💡', tone: 'blue', status: 'DRAFT', startPrice: 100, currentPrice: 100, minIncrement: 10, leaderId: null, endsAt: null, extensionCount: 0, participants: [], bids: [], seq: 0 }]

export const useAuctionStore = defineStore('auction', () => {
  const currentUser = ref<User>(users[1]); const auctions = ref<Auction[]>(seed()); const now = ref(Date.now()); const usersRef = ref(users)
  const wallets = ref<Record<string, { total: number; frozen: number; ledger: Ledger[] }>>(Object.fromEntries(users.filter(u => u.role === 'BIDDER').map(u => [u.id, { total: 1000, frozen: 0, ledger: [] as Ledger[] }])))
  const settlements = ref<Settlement[]>([])
  const wallet = computed(() => wallets.value[currentUser.value.id] ?? { total: 0, frozen: 0, ledger: [] as Ledger[] })
  const available = computed(() => wallet.value.total - wallet.value.frozen)
  const adminFinance = computed(() => {
    const bidderWallets = Object.values(wallets.value)
    return {
      frozen: bidderWallets.reduce((sum, item) => sum + item.frozen, 0),
      settled: settlements.value.filter(item => item.reason === '成交').reduce((sum, item) => sum + item.amount, 0),
      released: bidderWallets.flatMap(item => item.ledger).filter(item => item.type === 'release').reduce((sum, item) => sum + item.amount, 0),
      settlementCount: settlements.value.length,
    }
  })
  function walletFor(userId: string) { return wallets.value[userId] ?? (wallets.value[userId] = { total: 1000, frozen: 0, ledger: [] }) }
  function addLedger(userId: string, entry: Omit<Ledger, 'id'>) { walletFor(userId).ledger.unshift({ ...entry, id: crypto.randomUUID() }) }
  function login(user: User) { currentUser.value = user }
  function userName(id: string | null) { return users.find(u => u.id === id)?.name ?? '未知用户' }
  function tick() { now.value = Date.now(); auctions.value.forEach(a => { if (a.status === 'RUNNING' && a.endsAt && now.value >= a.endsAt) settleAuction(a.id) }) }
  function joinAuction(id: string) { const a = auctions.value.find(x => x.id === id); if (!a) return '拍卖不存在'; if (!a.participants.includes(currentUser.value.id)) a.participants.push(currentUser.value.id); return '已加入本场拍卖' }
  function placeBid(id: string, amount: number) { const a = auctions.value.find(x => x.id === id); if (!a) return { accepted: false, message: '拍卖不存在', price: 0 }; if (a.status !== 'RUNNING') return { accepted: false, message: '拍卖当前不可出价', price: a.currentPrice }; if (!a.participants.includes(currentUser.value.id)) return { accepted: false, message: '请先加入拍卖', price: a.currentPrice }; if (amount < a.currentPrice + a.minIncrement) return { accepted: false, message: `出价至少需要 ◎ ${a.currentPrice + a.minIncrement}`, price: a.currentPrice }; const mine = walletFor(currentUser.value.id); const previous = a.bids.findLast(b => b.userId === currentUser.value.id)?.amount ?? 0; const add = amount - previous; if (add > mine.total - mine.frozen) return { accepted: false, message: '可用余额不足', price: a.currentPrice }; const oldLeader = a.leaderId; const old = oldLeader ? a.bids.findLast(b => b.userId === oldLeader)?.amount ?? 0 : 0; mine.frozen += add; if (old && oldLeader && oldLeader !== currentUser.value.id) { const oldWallet = walletFor(oldLeader); oldWallet.frozen = Math.max(0, oldWallet.frozen - old); addLedger(oldLeader, { label: `「${a.title}」被超越，释放冻结`, amount: old, type: 'release', time: '刚刚', auctionId: a.id }) } a.leaderId = currentUser.value.id; a.currentPrice = amount; a.bids.push({ id: crypto.randomUUID(), userId: currentUser.value.id, userName: currentUser.value.name, amount, time: '刚刚' }); a.seq++; if (a.endsAt && a.endsAt - now.value <= 5000 && a.extensionCount < 3) { a.endsAt += 10000; a.extensionCount++ } addLedger(currentUser.value.id, { label: `参与「${a.title}」出价，冻结积分`, amount: add, type: 'freeze', time: '刚刚', auctionId: a.id }); return { accepted: true, message: '出价成功，已成为当前领先者', price: amount } }
  function createAuction(title: string) { const a: Auction = { id: crypto.randomUUID(), title, description: '新建拍卖，等待开始', icon: '✦', tone: 'olive', status: 'DRAFT', startPrice: 100, currentPrice: 100, minIncrement: 10, leaderId: null, endsAt: null, extensionCount: 0, participants: [], bids: [], seq: 0 }; auctions.value.unshift(a); return a }
  function startAuction(id: string) { const a = auctions.value.find(x => x.id === id); if (a && a.status === 'DRAFT') { a.status = 'RUNNING'; a.endsAt = Date.now() + 60_000; now.value = Date.now() } }
  function cancelAuction(id: string) { const a = auctions.value.find(x => x.id === id); if (a) a.status = 'CANCELLED' }
  function settleAuction(id: string) { const a = auctions.value.find(x => x.id === id); if (!a || a.status !== 'RUNNING') return; a.status = 'FINISHED'; a.endsAt = null; const winner = a.leaderId; if (winner && a.currentPrice) { const winnerWallet = walletFor(winner); winnerWallet.total -= a.currentPrice; winnerWallet.frozen = Math.max(0, winnerWallet.frozen - a.currentPrice); addLedger(winner, { label: `赢得「${a.title}」，成交扣款`, amount: a.currentPrice, type: 'settle', time: '刚刚', auctionId: a.id }) } for (const participant of a.participants) { if (participant !== winner) { const participantWallet = walletFor(participant); if (participantWallet.frozen > 0) { const released = participantWallet.frozen; participantWallet.frozen = 0; addLedger(participant, { label: `「${a.title}」未中标，释放冻结`, amount: released, type: 'release', time: '刚刚', auctionId: a.id }) } } } settlements.value.unshift({ id: crypto.randomUUID(), auctionId: a.id, auctionTitle: a.title, winnerId: winner, winnerName: winner ? userName(winner) : null, amount: winner ? a.currentPrice : 0, settledAt: new Date().toLocaleString('zh-CN'), reason: winner ? '成交' : '无人出价' }) }
  return { users: usersRef, currentUser, auctions, settlements, adminFinance, wallet: computed(() => ({ ...wallet.value, available: available.value })), now, login, userName, tick, joinAuction, placeBid, createAuction, startAuction, cancelAuction, settleAuction }
})
