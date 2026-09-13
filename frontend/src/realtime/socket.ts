import type { WsTicket } from '../api/types'

/** 连接状态：界面据此显示“实时同步中 / 重连中 / 已断开”。 */
export type FeedState = 'idle' | 'connecting' | 'live' | 'resyncing' | 'retrying' | 'closed'

export interface SocketHandlers {
  onOpen(): void
  onMessage(data: string): void
  /** 关闭原因码由服务端给出（握手失败时服务端会先发一帧 CONNECTION_STATE 再关闭）。 */
  onClose(code: number, reason: string): void
  onError?(error: unknown): void
}

/** 只暴露 `close`：契约规定客户端发往 WS 的消息一律被忽略，因此这一层没有 send。 */
export interface SocketLike {
  close(): void
}

export type SocketFactory = (url: string, handlers: SocketHandlers) => SocketLike

/**
 * 浏览器/Node 内置 WebSocket 的适配。
 *
 * 抽成工厂是为了让“序号与快照恢复”那段逻辑可以不碰网络被测到——
 * 用假 socket 驱动 100 种帧顺序，比对着真服务端碰运气重现缺口可靠得多。
 */
export function createBrowserSocket(url: string, handlers: SocketHandlers): SocketLike {
  const ws = new WebSocket(url)
  ws.onopen = () => handlers.onOpen()
  ws.onmessage = (event: MessageEvent) => {
    handlers.onMessage(typeof event.data === 'string' ? event.data : String(event.data))
  }
  ws.onclose = (event: CloseEvent) => handlers.onClose(event.code, event.reason ?? '')
  ws.onerror = () => handlers.onError?.(new Error('websocket error'))
  return { close: () => ws.close() }
}

/**
 * 拼 WS 地址。
 *
 * 两件事不能想当然：
 * - 端口默认**必须**用服务端给的 `wsPort`（独立监听器，默认 HTTP 端口 +10000）。
 *   前端自己推导的话，改了配置就变成“静默连不上”；只有反代把 `/ws` 收进同一 origin
 *   的部署拓扑例外（{@link SocketUrlOptions.sameOrigin}）。
 * - 路径是**模板**（`/ws/auctions/{auctionId}`），必须把 `{auctionId}` 换掉。
 *   漏了这一步的表现是握手 404，而且看起来像"服务端没上线"（踩过一次，见 DBG-23）。
 *
 * 主机名沿用当前页面的 host，这样同一个前端既能连本机也能连远端（HTTPS 页面下用 wss）。
 */
export interface SocketUrlOptions {
  /**
   * 单 origin 部署（前端经反向代理与后端同源）时为 true。
   *
   * <p>此时 WS 和页面走同一个外部 origin（80/443），由反代把 `/ws` 转到后端的独立
   * 监听器；**不能**再把服务端给的 `wsPort`（18080）拼进去——那个端口在部署拓扑里
   * 并不对外暴露，拼上去的表现是浏览器一直重连、而服务端一切正常。
   * 直连拓扑（本机开发）保持默认 false，端口仍以服务端告知为准。
   */
  sameOrigin?: boolean
}

export function socketUrl(ticket: WsTicket, auctionId: string, options: SocketUrlOptions = {}): string {
  const scheme = globalThis.location?.protocol === 'https:' ? 'wss:' : 'ws:'
  const raw = ticket.wsPath.startsWith('/') ? ticket.wsPath : `/${ticket.wsPath}`
  const path = raw.replace('{auctionId}', encodeURIComponent(auctionId))
  const query = `?ticket=${encodeURIComponent(ticket.ticket)}`
  if (options.sameOrigin) {
    // host 已含外部端口（缺省 80/443）：同源部署下这是唯一正确的 authority。
    const host = globalThis.location?.host ?? 'localhost'
    return `${scheme}//${host}${path}${query}`
  }
  const host = globalThis.location?.hostname ?? 'localhost'
  return `${scheme}//${host}:${ticket.wsPort}${path}${query}`
}
