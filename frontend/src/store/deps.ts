import { createApiClient } from '../api/client'
import { createAuctionApi, type AuctionApi } from '../api/endpoints'
import { createSessionStorage, type SessionStorage } from '../api/session'
import type { SocketFactory } from '../realtime/socket'

/** store 的外部依赖：全部可注入，测试里换成假 HTTP、假 socket、假时钟。 */
export interface ArenaDeps {
  api: AuctionApi
  session: SessionStorage
  createSocket?: SocketFactory
  now?: () => number
}

/** 真实依赖：真 HTTP 客户端 + 浏览器存储。 */
export function defaultArenaDeps(): ArenaDeps {
  const session = createSessionStorage()
  const client = createApiClient({
    token: () => session.read()?.accessToken ?? null,
    onUnauthenticated: () => session.clear(),
  })
  return { api: createAuctionApi(client), session }
}

/**
 * 依赖覆盖点。
 *
 * Pinia 的 setup store 没有构造参数，而商店必须在测试里换掉 HTTP 与 socket。
 * 这里用一个显式的一次性覆盖（在 `setActivePinia` 之前设置），
 * 比在 store 内部写 `if (import.meta.env.MODE === 'test')` 清楚得多：
 * 生产代码里不该出现只为测试存在的分支。
 */
let override: ArenaDeps | null = null

export function setArenaDeps(deps: ArenaDeps | null): void {
  override = deps
}

export function arenaDeps(): ArenaDeps {
  return override ?? defaultArenaDeps()
}
