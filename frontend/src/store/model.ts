import { ApiError } from '../api/client'
import type { AuctionSnapshot, AuctionStatus } from '../api/types'
import type { SnapshotPayload } from '../realtime/feed'

/** 界面用的拍卖模型：把 HTTP 快照与 WS 事件合并成同一个形状。 */
export interface LiveAuction {
  id: string
  title: string
  description: string
  status: AuctionStatus
  startPrice: number
  minIncrement: number
  currentPrice: number
  /** 匿名标识：事件与展示统一用它（契约 §4 的可见性边界）。 */
  leaderAnon: string | null
  endsAt: string | null
  /**
   * 预告开拍时间（D-35）。为 null 就是“只等管理员手动开始”。
   * 它只影响列表里那句“还有多久开拍”，不参与出价/延时/结算的任何判定。
   */
  startsAt: string | null
  extensionCount: number
  participantCount: number
  seq: number
  finalGameWindowSeconds: number
}

/** 演示账号：契约与种子数据里就是这三个（密码只用于本地演示，见 README）。 */
export const DEMO_ACCOUNTS = [
  { label: '管理员', email: 'admin@example.com', password: 'Admin123456!' },
  { label: '竞拍者 A', email: 'bidder_a@example.com', password: 'Test123456!' },
  { label: '竞拍者 B', email: 'bidder_b@example.com', password: 'Test123456!' },
] as const

/**
 * HTTP 快照 → 界面模型。
 *
 * `leaderAnon` 必须由调用方算好再传进来：HTTP 快照给的是**原始 `user_id`**，
 * 而展示与比较统一用匿名标识。把这一步放在这里（而不是让模板自己转）是为了让
 * “原始 id 只在这一处出现一次”这件事在代码里看得见。
 */
export function toLive(snapshot: AuctionSnapshot, description: string, leaderAnon: string | null): LiveAuction {
  return {
    id: snapshot.id,
    title: snapshot.title,
    description,
    status: snapshot.status,
    startPrice: snapshot.startPrice,
    minIncrement: snapshot.minIncrement,
    currentPrice: snapshot.currentPrice,
    leaderAnon,
    endsAt: snapshot.endsAt ?? null,
    startsAt: snapshot.startsAt ?? null,
    extensionCount: snapshot.extensionCount,
    participantCount: snapshot.participantCount,
    seq: snapshot.seq,
    finalGameWindowSeconds: snapshot.finalGameWindowSeconds,
  }
}

/** WS 快照 → 界面模型。事件里已经是匿名标识（契约 §4），不需要再转一次。 */
export function liveFromPayload(payload: SnapshotPayload, description: string): LiveAuction {
  // `startsAt` 在事件里是可选的（快照帧不一定带），这里补一个 null 让模型形状固定：
  // 让下游去分辨“缺字段”和“确实是 null”只会逼出一堆无意义的判空。
  return { ...payload, startsAt: payload.startsAt ?? null, description }
}

/**
 * HTTP 快照 → feed 要的 `SnapshotPayload`。
 *
 * 为什么两个方向都要转：feed 只认一种输入（无论快照来自 socket 还是 HTTP），
 * 否则“基线”会有两套解析代码，迟早出现一边认一边不认的字段。
 */
export function toSnapshotPayload(snapshot: AuctionSnapshot, leaderAnon: string | null): SnapshotPayload {
  return {
    id: snapshot.id,
    title: snapshot.title,
    status: snapshot.status,
    startPrice: snapshot.startPrice,
    minIncrement: snapshot.minIncrement,
    currentPrice: snapshot.currentPrice,
    leaderAnon,
    endsAt: snapshot.endsAt ?? null,
    startsAt: snapshot.startsAt ?? null,
    extensionCount: snapshot.extensionCount,
    participantCount: snapshot.participantCount,
    seq: snapshot.seq,
    finalGameWindowSeconds: snapshot.finalGameWindowSeconds,
    // 服务端时间必须带上：消费方要用它校准时钟。
    serverTime: snapshot.serverTime,
  }
}

/**
 * 幂等键。
 *
 * 用 `crypto.randomUUID` 而不是“时间戳 + 自增”：这个值要在重试时**原样复用**，
 * 所以必须由客户端生成一次并记住；同时它会被写进服务端日志，
 * 可预测的序列会让“这是谁的请求”变得可枚举。
 */
export function newRequestId(): string {
  const random = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2)
  return `web-${random}`
}

export function isUnauthenticated(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'UNAUTHENTICATED'
}

/** 错误提示按**错误码**分支（后端文案可能变，错误码是契约）。 */
export function describe(error: unknown): string {
  if (error instanceof ApiError) return error.userMessage
  return error instanceof Error ? error.message : '未知错误'
}
