/**
 * WebSocket 事件信封（权威定义见 `docs/REALTIME_AND_COMMAND_FLOW.md` §3/§4）。
 *
 * 这里不生成类型：事件是**运行时**来的 JSON，不是编译期契约（OpenAPI 只描述 HTTP）。
 * 因此信封按“宽容解析 + 显式校验”处理：解析失败或字段不对，就当作没收到这一帧，
 * 而不是带着半截对象去改状态。
 */
export type AuctionEventType =
  | 'AUCTION_SNAPSHOT'
  | 'PARTICIPANT_JOINED'
  | 'BID_ACCEPTED'
  | 'BID_REJECTED'
  | 'AUCTION_EXTENDED'
  | 'AUCTION_FINISHED'
  | 'CONNECTION_STATE'

export interface AuctionEventEnvelope {
  type: AuctionEventType
  auctionId: string
  seq: number
  serverTime: string
  payload: Record<string, unknown>
}

const EVENT_TYPES: readonly AuctionEventType[] = [
  'AUCTION_SNAPSHOT',
  'PARTICIPANT_JOINED',
  'BID_ACCEPTED',
  'BID_REJECTED',
  'AUCTION_EXTENDED',
  'AUCTION_FINISHED',
  'CONNECTION_STATE',
]

/** 未知类型（服务端加了新事件而前端还没更新）返回 null：**忽略**比“猜着处理”安全。 */
export function isEventType(value: unknown): value is AuctionEventType {
  return typeof value === 'string' && (EVENT_TYPES as readonly string[]).includes(value)
}

export function parseEvent(raw: string): AuctionEventEnvelope | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const candidate = parsed as Partial<AuctionEventEnvelope>
  if (!isEventType(candidate.type)) return null
  if (typeof candidate.auctionId !== 'string') return null
  if (typeof candidate.seq !== 'number' || !Number.isFinite(candidate.seq)) return null
  if (typeof candidate.serverTime !== 'string') return null
  const payload = candidate.payload
  if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) return null
  return {
    type: candidate.type,
    auctionId: candidate.auctionId,
    seq: candidate.seq,
    serverTime: candidate.serverTime,
    payload: payload as Record<string, unknown>,
  }
}

/**
 * 去重键：`(auctionId, seq, type)`。
 *
 * 为什么不是只有 `seq`：**一次提交会把多个事件放在同一个 `seq` 上**
 * （最后 5 秒内的出价会同时发 `BID_ACCEPTED` 与 `AUCTION_EXTENDED`）。
 * 只按 `seq` 去重会把第二个事件当成重复丢掉，于是“延时”这件事在界面上永远不显示。
 */
export function eventKey(event: AuctionEventEnvelope): string {
  return `${event.auctionId}:${event.seq}:${event.type}`
}

/** 取数值字段；字段缺席或类型不对时返回 undefined（`null` 即缺席，见契约 §4）。 */
export function numberField(payload: Record<string, unknown>, key: string): number | undefined {
  const value = payload[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

export function stringField(payload: Record<string, unknown>, key: string): string | undefined {
  const value = payload[key]
  return typeof value === 'string' ? value : undefined
}

export function booleanField(payload: Record<string, unknown>, key: string): boolean | undefined {
  const value = payload[key]
  return typeof value === 'boolean' ? value : undefined
}
