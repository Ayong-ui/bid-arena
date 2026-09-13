import type { components } from './schema'

// 契约类型的“人类可读别名”。
//
// 这里**不手写任何字段**：全部来自 `npm run gen:api` 从 `docs/openapi.yaml` 生成的结果。
// 手写一份“镜像类型”是慢性漂移的起点——契约改了、镜像没改，编译器还替这套假类型背书，
// 直到运行时才发现字段对不上。改契约的流程是：改 yaml → 重新生成 → 跟着修编译错误。
export type ApiCode = components['schemas']['ApiCode']
export type Envelope = components['schemas']['Envelope']
export type LoginRequest = components['schemas']['LoginRequest']
export type AuthData = components['schemas']['AuthData']
export type User = components['schemas']['User']
export type AuctionStatus = components['schemas']['AuctionStatus']
export type AuctionSnapshot = components['schemas']['AuctionSnapshot']
export type AuctionPage = components['schemas']['AuctionPage']
export type Bid = components['schemas']['Bid']
export type BidPage = components['schemas']['BidPage']
export type BidRequest = components['schemas']['BidRequest']
export type BidResult = components['schemas']['BidResult']
export type LedgerEntry = components['schemas']['LedgerEntry']
export type LedgerPage = components['schemas']['LedgerPage']
export type Participant = components['schemas']['Participant']
export type Wallet = components['schemas']['Wallet']
export type WsTicket = components['schemas']['WsTicket']
export type AuctionResult = components['schemas']['AuctionResult']
export type CreateAuctionRequest = components['schemas']['CreateAuctionRequest']
export type CreateAgentTokenRequest = components['schemas']['CreateAgentTokenRequest']
export type AgentToken = components['schemas']['AgentToken']
export type CreateMyAgentTokenRequest = components['schemas']['CreateMyAgentTokenRequest']
export type AgentTokenSummary = components['schemas']['AgentTokenSummary']
export type AgentTokenPage = components['schemas']['AgentTokenPage']
