import { messageForCode, minimumOf, type ClientErrorCode } from './messages'
import type { ApiCode, Envelope } from './types'

/**
 * HTTP 客户端的“跨层”错误类型。
 *
 * 每次失败都同时带着三样东西：**业务码**（判定依据）、**HTTP 状态**（排障依据）、
 * **requestId**（能拿着它去服务端日志里对齐同一次请求）。只留一句 error message
 * 是没法排障的——这正是前端最容易被诟病的那种“只显示请求失败”。
 */
export class ApiError extends Error {
  readonly code: ClientErrorCode
  readonly httpStatus: number
  readonly requestId: string | null
  readonly details: unknown
  readonly serverMessage: string | null

  constructor(init: {
    code: ClientErrorCode
    httpStatus?: number
    requestId?: string | null
    details?: unknown
    serverMessage?: string | null
    cause?: unknown
  }) {
    super(init.serverMessage ?? messageForCode(init.code), { cause: init.cause })
    this.name = 'ApiError'
    this.code = init.code
    this.httpStatus = init.httpStatus ?? 0
    this.requestId = init.requestId ?? null
    this.details = init.details ?? null
    this.serverMessage = init.serverMessage ?? null
  }

  /** 面向用户的提示：按错误码分支；`BID_TOO_LOW` 这类可纠正的错误带上服务端给的下限。 */
  get userMessage(): string {
    if (this.code === 'BID_TOO_LOW') {
      const minimum = minimumOf(this.details)
      if (minimum !== null) return `出价至少需要 ◎ ${minimum}`
    }
    return messageForCode(this.code)
  }
}

/** 出价与登录这类“可能重试”的请求不该无限期挂在界面上（按钮会一直禁用）。 */
export const DEFAULT_TIMEOUT_MS = 10_000

/** 契约里两种成功码都对应 HTTP 200，重放不是错误。 */
export function isSuccessCode(code: ApiCode): boolean {
  return code === 'OK' || code === 'IDEMPOTENCY_REPLAY'
}

/** 成功响应：保留 `code`（要区分重放）与 `requestId`（排障），业务数据在 `data`。 */
export interface ApiOk<T> {
  code: ApiCode
  message: string
  requestId: string
  data: T
}

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export interface ApiClientOptions {
  /** 契约里的 server 根路径。默认 `/api/v1`，由 vite dev server 代理到后端。 */
  baseUrl?: string
  fetchImpl?: FetchLike
  timeoutMs?: number
  /** 取当前访问令牌；返回 null 表示匿名请求（登录、健康检查）。 */
  token?: () => string | null
  /** 令牌失效时回调（清理会话并回到登录页）。只在“带着令牌还被拒”时触发。 */
  onUnauthenticated?: () => void
}

export interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
  query?: Record<string, string | number | undefined>
  headers?: Record<string, string>
  timeoutMs?: number
  signal?: AbortSignal
}

const DEFAULT_BASE_URL = '/api/v1'

function buildUrl(baseUrl: string, path: string, query?: RequestOptions['query']): string {
  const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
  const url = `${base}${path.startsWith('/') ? path : `/${path}`}`
  if (!query) return url
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) search.set(key, String(value))
  }
  const qs = search.toString()
  return qs ? `${url}?${qs}` : url
}

/** 只把“看起来像契约封套”的对象当作封套，其余（代理返回的 HTML、空响应体）一律算协议违规。 */
function parseEnvelope(text: string): Envelope | null {
  try {
    const parsed: unknown = JSON.parse(text)
    if (typeof parsed !== 'object' || parsed === null) return null
    const candidate = parsed as Partial<Envelope>
    if (typeof candidate.code !== 'string') return null
    return candidate as Envelope
  } catch {
    return null
  }
}

export interface ApiClient {
  request<T>(path: string, options?: RequestOptions): Promise<ApiOk<T>>
  get<T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>): Promise<ApiOk<T>>
  post<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>): Promise<ApiOk<T>>
}

export function createApiClient(options: ApiClientOptions = {}): ApiClient {
  const baseUrl = options.baseUrl ?? DEFAULT_BASE_URL
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const doFetch: FetchLike = options.fetchImpl ?? ((input, init) => fetch(input, init))

  async function request<T>(path: string, requestOptions: RequestOptions = {}): Promise<ApiOk<T>> {
    const token = options.token?.() ?? null
    const headers: Record<string, string> = { Accept: 'application/json', ...requestOptions.headers }
    if (token) headers['Authorization'] = `Bearer ${token}`
    if (requestOptions.body !== undefined) headers['Content-Type'] = 'application/json'

    // 超时与调用方传入的 signal 合并：任一方中止都应当结束这次请求。
    const timeout = AbortSignal.timeout(requestOptions.timeoutMs ?? timeoutMs)
    const signal = requestOptions.signal ? AbortSignal.any([requestOptions.signal, timeout]) : timeout

    let response: Response
    try {
      response = await doFetch(buildUrl(baseUrl, path, requestOptions.query), {
        method: requestOptions.method ?? 'GET',
        headers,
        body: requestOptions.body === undefined ? undefined : JSON.stringify(requestOptions.body),
        signal,
      })
    } catch (cause) {
      // 到这里说明请求没到达服务端（DNS/断网/超时/被中止），没有业务码可谈。
      throw new ApiError({ code: 'NETWORK', cause })
    }

    const text = await response.text().catch(() => '')
    const envelope = parseEnvelope(text)
    if (!envelope) {
      throw new ApiError({
        code: 'INTERNAL_ERROR',
        httpStatus: response.status,
        serverMessage: `服务端返回了非契约响应（HTTP ${response.status}）`,
      })
    }

    if (!isSuccessCode(envelope.code as ApiCode)) {
      if (envelope.code === 'UNAUTHENTICATED' && token) options.onUnauthenticated?.()
      throw new ApiError({
        code: envelope.code as ApiCode,
        httpStatus: response.status,
        requestId: envelope.requestId,
        details: envelope.data,
        serverMessage: envelope.message,
      })
    }

    return {
      code: envelope.code as ApiCode,
      message: envelope.message,
      requestId: envelope.requestId,
      data: envelope.data as T,
    }
  }

  return {
    request,
    get: (path, requestOptions) => request(path, { ...requestOptions, method: 'GET' }),
    post: (path, body, requestOptions) => request(path, { ...requestOptions, method: 'POST', body }),
  }
}
