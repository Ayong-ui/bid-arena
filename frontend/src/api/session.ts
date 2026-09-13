import type { User } from './types'

/** 会话 = 访问令牌 + 过期时刻 + 用户投影。三者一起持久化，避免“有令牌但不知道是谁”。 */
export interface Session {
  accessToken: string
  expiresAt: string
  user: User
}

/** localStorage 的最小接口（测试注入内存实现，避免为了一个键值对引入 jsdom）。 */
export interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface SessionStorage {
  read(): Session | null
  write(session: Session): void
  clear(): void
}

const STORAGE_KEY = 'bid-arena.session'

/**
 * 提前 30 秒判定过期：令牌恰好在这一刻失效时，请求会以 401 返回，
 * 与其让用户看到一次“莫名其妙失败”，不如提前当作未登录并回到登录页。
 */
const EXPIRY_SKEW_MS = 30_000

export function isExpired(session: Session, now: number = Date.now()): boolean {
  const expiresAt = Date.parse(session.expiresAt)
  if (Number.isNaN(expiresAt)) return true
  return expiresAt - now <= EXPIRY_SKEW_MS
}

/** 内存实现：node 测试与“禁用存储的浏览器”都用它，行为与 localStorage 一致。 */
export function createMemoryStorage(): StorageLike {
  const map = new Map<string, string>()
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  }
}

/** 浏览器存储：隐私模式或配额异常时退回内存，让页面仍然可用（只是刷新后要重新登录）。 */
export function selectStorage(): StorageLike {
  try {
    if (typeof localStorage !== 'undefined') {
      const probe = '__bid_arena_probe__'
      localStorage.setItem(probe, '1')
      localStorage.removeItem(probe)
      return localStorage
    }
  } catch {
    // 忽略：下面的内存实现就是兜底
  }
  return createMemoryStorage()
}

export function createSessionStorage(backing: StorageLike = selectStorage()): SessionStorage {
  function read(): Session | null {
    const raw = backing.getItem(STORAGE_KEY)
    if (!raw) return null
    try {
      const parsed: unknown = JSON.parse(raw)
      if (typeof parsed !== 'object' || parsed === null) return null
      const candidate = parsed as Partial<Session>
      if (typeof candidate.accessToken !== 'string' || typeof candidate.expiresAt !== 'string') return null
      if (typeof candidate.user !== 'object' || candidate.user === null) return null
      const session = candidate as Session
      if (isExpired(session)) {
        // 过期的令牌留在存储里只会让下次启动再走一遍 401，直接清掉。
        backing.removeItem(STORAGE_KEY)
        return null
      }
      return session
    } catch {
      backing.removeItem(STORAGE_KEY)
      return null
    }
  }

  return {
    read,
    // 只存令牌相关内容；**不打印、不写日志**（令牌泄露后到过期前都能冒用身份）。
    write: (session) => backing.setItem(STORAGE_KEY, JSON.stringify(session)),
    clear: () => backing.removeItem(STORAGE_KEY),
  }
}
