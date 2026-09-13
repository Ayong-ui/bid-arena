import type { ApiClient, ApiOk } from './client'
import type {
  AuctionPage,
  AuctionResult,
  AuctionSnapshot,
  AuctionStatus,
  AuthData,
  BidPage,
  BidRequest,
  BidResult,
  CreateAuctionRequest,
  LedgerPage,
  LoginRequest,
  Participant,
  User,
  Wallet,
  WsTicket,
} from './types'

/**
 * 契约端点的类型化封装。
 *
 * 存在的意义是让调用方**看不见路径与查询串**：路径写错、少传参数、传错类型都会在这里变成编译错误，
 * 而不是运行时的一个 404。路径字符串在整份代码里只出现一次，也就是这一层。
 *
 * 这里只封装 P4 需要的端点。`/agent/*`（独立端口 :8090）属 P5，**不在这里造占位实现**：
 * 一个“看起来能用”的客户端封装比没有封装更糟。
 */
export interface AuctionApi {
  login(body: LoginRequest): Promise<ApiOk<AuthData>>
  currentUser(): Promise<ApiOk<User>>
  wallet(): Promise<ApiOk<Wallet>>
  ledger(page?: number, size?: number): Promise<ApiOk<LedgerPage>>
  auctions(params?: { status?: AuctionStatus; page?: number; size?: number }): Promise<ApiOk<AuctionPage>>
  auction(auctionId: string): Promise<ApiOk<AuctionSnapshot>>
  joinAuction(auctionId: string): Promise<ApiOk<Participant>>
  bids(auctionId: string, page?: number, size?: number): Promise<ApiOk<BidPage>>
  /** 幂等键同时放进 body 与 `Idempotency-Key` 头（两者必须一致，否则服务端 400）。 */
  placeBid(auctionId: string, body: BidRequest): Promise<ApiOk<BidResult>>
  result(auctionId: string): Promise<ApiOk<AuctionResult>>
  createAuction(body: CreateAuctionRequest): Promise<ApiOk<AuctionSnapshot>>
  startAuction(auctionId: string): Promise<ApiOk<AuctionSnapshot>>
  cancelAuction(auctionId: string): Promise<ApiOk<AuctionSnapshot>>
  wsTicket(): Promise<ApiOk<WsTicket>>
}

export function createAuctionApi(client: ApiClient): AuctionApi {
  return {
    login: (body) => client.post<AuthData>('/auth/login', body),
    currentUser: () => client.get<User>('/users/me'),
    wallet: () => client.get<Wallet>('/wallets/me'),
    ledger: (page = 1, size = 20) => client.get<LedgerPage>('/wallets/me/ledger', { query: { page, size } }),
    auctions: (params = {}) =>
      client.get<AuctionPage>('/auctions', { query: { status: params.status, page: params.page, size: params.size ?? 50 } }),
    auction: (auctionId) => client.get<AuctionSnapshot>(`/auctions/${encodeURIComponent(auctionId)}`),
    joinAuction: (auctionId) => client.post<Participant>(`/auctions/${encodeURIComponent(auctionId)}/join`),
    bids: (auctionId, page = 1, size = 50) =>
      client.get<BidPage>(`/auctions/${encodeURIComponent(auctionId)}/bids`, { query: { page, size } }),
    placeBid: (auctionId, body) =>
      client.post<BidResult>(`/auctions/${encodeURIComponent(auctionId)}/bids`, body, {
        headers: { 'Idempotency-Key': body.requestId },
      }),
    result: (auctionId) => client.get<AuctionResult>(`/auctions/${encodeURIComponent(auctionId)}/result`),
    createAuction: (body) => client.post<AuctionSnapshot>('/admin/auctions', body),
    startAuction: (auctionId) => client.post<AuctionSnapshot>(`/admin/auctions/${encodeURIComponent(auctionId)}/start`),
    cancelAuction: (auctionId) => client.post<AuctionSnapshot>(`/admin/auctions/${encodeURIComponent(auctionId)}/cancel`),
    wsTicket: () => client.post<WsTicket>('/auth/ws-tickets'),
  }
}
