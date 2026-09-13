import { afterEach, describe, expect, it } from 'vitest'
import type { WsTicket } from '../api/types'
import { socketUrl } from './socket'

/**
 * `socketUrl` 的两种拓扑：
 * - 直连（默认）：端口取自服务端给的 `wsPort`，本机开发与端到端脚本用这条；
 * - 单 origin（`sameOrigin: true`）：端口取自页面自身，反代再把 `/ws` 转到 18080（D-37）。
 *
 * 这里必须把 `location` 两种情形都钉住，因为拼错的后果是“浏览器一直重连、服务端却一切正常”
 * ——最贵的那种故障。
 */
const ticket: WsTicket = {
  ticket: 'abc+/=',
  expiresAt: '2030-01-01T00:00:00Z',
  wsPath: '/ws/auctions/{auctionId}',
  wsPort: 18080,
}

const originalLocation = Object.getOwnPropertyDescriptor(globalThis, 'location')

function setLocation(value: unknown): void {
  Object.defineProperty(globalThis, 'location', { value, configurable: true, writable: true })
}

afterEach(() => {
  if (originalLocation) {
    Object.defineProperty(globalThis, 'location', originalLocation)
  } else {
    delete (globalThis as { location?: unknown }).location
  }
})

describe('socketUrl', () => {
  it('直连拓扑用服务端给的 wsPort，并把 ticket 做 URL 编码', () => {
    setLocation({ protocol: 'http:', hostname: 'localhost', host: 'localhost:5173' })
    expect(socketUrl(ticket, 'auc-1')).toBe(
      'ws://localhost:18080/ws/auctions/auc-1?ticket=abc%2B%2F%3D',
    )
  })

  it('单 origin 拓扑用页面自身的 host（含外部端口），不拼 wsPort', () => {
    setLocation({ protocol: 'http:', hostname: 'localhost', host: 'localhost:8088' })
    expect(socketUrl(ticket, 'auc-1', { sameOrigin: true })).toBe(
      'ws://localhost:8088/ws/auctions/auc-1?ticket=abc%2B%2F%3D',
    )
  })

  it('HTTPS 页面下两种拓扑都升级为 wss', () => {
    setLocation({ protocol: 'https:', hostname: 'arena.example.com', host: 'arena.example.com' })
    expect(socketUrl(ticket, 'auc-1', { sameOrigin: true })).toBe(
      'wss://arena.example.com/ws/auctions/auc-1?ticket=abc%2B%2F%3D',
    )
    setLocation({ protocol: 'https:', hostname: 'arena.example.com', host: 'arena.example.com' })
    expect(socketUrl(ticket, 'auc-1')).toBe(
      'wss://arena.example.com:18080/ws/auctions/auc-1?ticket=abc%2B%2F%3D',
    )
  })

  it('auctionId 进路径前必须编码，避免注入查询串', () => {
    setLocation({ protocol: 'http:', hostname: 'localhost', host: 'localhost:5173' })
    expect(socketUrl(ticket, 'a b&c')).toBe(
      'ws://localhost:18080/ws/auctions/a%20b%26c?ticket=abc%2B%2F%3D',
    )
  })
})
