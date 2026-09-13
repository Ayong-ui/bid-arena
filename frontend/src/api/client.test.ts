import { describe, expect, it } from 'vitest'
import { expectApiFailure } from '../testing/apiFailure'
import { createApiClient, isSuccessCode } from './client'
import { createAuctionApi } from './endpoints'
import type { BidResult } from './types'

/** 构造一个契约封套响应。 */
function envelope(code: string, data: unknown = null, requestId = 'req_test', message = 'ok'): Response {
  return new Response(JSON.stringify({ code, message, data, requestId }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('HTTP 客户端：封套与错误码', () => {
  it('成功时返回 data / code / requestId', async () => {
    const client = createApiClient({
      baseUrl: 'http://localhost:8080/api/v1',
      fetchImpl: async () => envelope('OK', { id: 'usr_1', name: '林默', role: 'BIDDER' }, 'req_abc'),
    })
    const ok = await client.get<{ id: string }>('/users/me')
    expect(ok.data.id).toBe('usr_1')
    expect(ok.code).toBe('OK')
    expect(ok.requestId).toBe('req_abc')
  })

  it('IDEMPOTENCY_REPLAY 是成功码（200），不是错误', async () => {
    const client = createApiClient({
      fetchImpl: async () => envelope('IDEMPOTENCY_REPLAY', { accepted: true, idempotent: true, price: 260 }),
    })
    const ok = await client.post<BidResult>('/auctions/a1/bids', { requestId: 'r1', amount: 260 })
    expect(isSuccessCode(ok.code)).toBe(true)
    expect(ok.data.idempotent).toBe(true)
  })

  it('HTTP 200 也可能装着一个错误封套：成败只看业务码，不看传输状态', async () => {
    // 响应已经开始后代理/过滤器再补写一个错误体，是现实中会发生的形态。
    // 契约把业务码定为成败的唯一判据，就是不让客户端去猜传输状态的含义。
    const client = createApiClient({
      fetchImpl: async () =>
        new Response(JSON.stringify({ code: 'FORBIDDEN', message: '只有参与者能看', data: null, requestId: 'r' }), {
          status: 200,
        }),
    })
    const failure = await expectApiFailure(client.get('/auctions/a1/result'))
    expect(failure.code).toBe('FORBIDDEN')
    expect(failure.httpStatus).toBe(200)
  })

  it('业务错误按 code 判定（即使 HTTP 状态码是 409）并保留 details 与 requestId', async () => {
    const client = createApiClient({
      fetchImpl: async () =>
        new Response(
          JSON.stringify({
            code: 'BID_TOO_LOW',
            message: '出价低于最低加价',
            data: { minimum: 270, currentPrice: 260 },
            requestId: 'req_low',
          }),
          { status: 409, headers: { 'Content-Type': 'application/json' } },
        ),
    })
    const failure = await expectApiFailure(client.post('/auctions/a1/bids', { requestId: 'r1', amount: 100 }))

    expect(failure.code).toBe('BID_TOO_LOW')
    expect(failure.httpStatus).toBe(409)
    expect(failure.requestId).toBe('req_low')
    expect(failure.userMessage).toBe('出价至少需要 ◎ 270')
  })

  it('错误码提示不依赖服务端文案（服务端返回英文也照样显示中文分支）', async () => {
    const client = createApiClient({
      fetchImpl: async () =>
        new Response(JSON.stringify({ code: 'NOT_JOINED', message: 'not a participant', data: {}, requestId: 'r' }), {
          status: 409,
        }),
    })
    const failure = await expectApiFailure(client.get('/auctions/a1'))
    expect(failure.userMessage).toBe('请先加入本场拍卖')
    expect(failure.serverMessage).toBe('not a participant')
  })

  it('代理返回 HTML（非契约响应）时给出明确错误，而不是把 HTML 当数据', async () => {
    const client = createApiClient({
      fetchImpl: async () => new Response('<html>502 Bad Gateway</html>', { status: 502 }),
    })
    const failure = await expectApiFailure(client.get('/auctions'))
    expect(failure.code).toBe('INTERNAL_ERROR')
    expect(failure.httpStatus).toBe(502)
    expect(failure.serverMessage).toContain('非契约响应')
  })

  it('网络不通时是 NETWORK 码（不是某个业务码）', async () => {
    const client = createApiClient({
      fetchImpl: async () => {
        throw new TypeError('fetch failed')
      },
    })
    const failure = await expectApiFailure(client.get('/auctions'))
    expect(failure.code).toBe('NETWORK')
    expect(failure.userMessage).toContain('网络')
  })

  it('请求超时会中止并归为 NETWORK（按钮不能永远禁用）', async () => {
    const client = createApiClient({
      timeoutMs: 20,
      fetchImpl: (_url, init) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
        }),
    })
    const failure = await expectApiFailure(client.get('/auctions'))
    expect(failure.code).toBe('NETWORK')
  })

  it('带着令牌被拒（401）时通知调用方清理会话，登录失败则不通知', async () => {
    let cleared = 0
    const withToken = createApiClient({
      token: () => 'jwt-token',
      onUnauthenticated: () => void (cleared += 1),
      fetchImpl: async () =>
        new Response(JSON.stringify({ code: 'UNAUTHENTICATED', message: 'bad token', data: null, requestId: 'r' }), {
          status: 401,
        }),
    })
    await expectApiFailure(withToken.get('/users/me'))
    expect(cleared).toBe(1)

    const anonymous = createApiClient({
      onUnauthenticated: () => void (cleared += 1),
      fetchImpl: async () =>
        new Response(JSON.stringify({ code: 'UNAUTHENTICATED', message: 'bad login', data: null, requestId: 'r' }), {
          status: 401,
        }),
    })
    await expectApiFailure(anonymous.post('/auth/login', { email: 'a@example.com', password: 'x' }))
    expect(cleared).toBe(1) // 登录页自己处理失败提示，不该把用户“踢出”一个本来就没登录的会话
  })
})

describe('HTTP 客户端：请求构造', () => {
  it('有令牌时带 Authorization 头，匿名请求不带', async () => {
    const seen: Array<RequestInit | undefined> = []
    const client = createApiClient({
      token: () => 'jwt-token',
      fetchImpl: async (_url, init) => {
        seen.push(init)
        return envelope('OK', {})
      },
    })
    await client.get('/users/me')
    expect(new Headers(seen[0]?.headers).get('Authorization')).toBe('Bearer jwt-token')

    const anonymous = createApiClient({
      token: () => null,
      fetchImpl: async (_url, init) => {
        seen.push(init)
        return envelope('OK', {})
      },
    })
    await anonymous.post('/auth/login', { email: 'a@example.com', password: 'secret123' })
    expect(new Headers(seen[1]?.headers).has('Authorization')).toBe(false)
    expect(seen[1]?.body).toBe(JSON.stringify({ email: 'a@example.com', password: 'secret123' }))
  })

  it('出价同时发 body.requestId 与 Idempotency-Key 头，且两者一致', async () => {
    let captured: { url: string; init?: RequestInit } | null = null
    const api = createAuctionApi(
      createApiClient({
        baseUrl: 'http://localhost:8080/api/v1/',
        fetchImpl: async (url, init) => {
          captured = { url, init }
          return envelope('OK', { accepted: true, price: 270, seq: 9 })
        },
      }),
    )
    await api.placeBid('auc_1', { requestId: 'req-abcdefgh', amount: 270 })

    expect(captured!.url).toBe('http://localhost:8080/api/v1/auctions/auc_1/bids') // baseUrl 尾斜杠不产生双斜杠
    const headers = new Headers(captured!.init?.headers)
    expect(headers.get('Idempotency-Key')).toBe('req-abcdefgh')
    expect(JSON.parse(String(captured!.init?.body))).toEqual({ requestId: 'req-abcdefgh', amount: 270 })
  })

  it('查询串只带定义过的参数（undefined 不出现），并做 URL 编码', async () => {
    const urls: string[] = []
    const api = createAuctionApi(
      createApiClient({
        baseUrl: '/api/v1',
        fetchImpl: async (url) => {
          urls.push(url)
          return envelope('OK', { items: [], page: 1, size: 20, total: 0 })
        },
      }),
    )
    await api.auctions({ status: 'RUNNING', page: 2, size: 5 })
    await api.auctions({})
    await api.auction('auc_1/../2')

    expect(urls[0]).toBe('/api/v1/auctions?status=RUNNING&page=2&size=5')
    expect(urls[1]).toBe('/api/v1/auctions?size=50')
    expect(urls[2]).toBe('/api/v1/auctions/auc_1%2F..%2F2')
  })
})
