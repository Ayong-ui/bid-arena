import { describe, expect, it } from 'vitest'
import { createAnonIdCache, anonymousId } from './anonymous'
import type { Session } from './api/session'
import { createMemoryStorage, createSessionStorage, isExpired } from './api/session'

describe('匿名标识（跨端契约）', () => {
  it('固定向量与服务端一致（服务端 Java 实现同样输出这两个值）', async () => {
    expect(await anonymousId('usr_bidder_a')).toBe('anon-2952873c')
    expect(await anonymousId('usr_admin')).toBe('anon-76d6c64f')
  })

  it('确定性：同一 user_id 永远同值；不同 user_id 不同值', async () => {
    expect(await anonymousId('usr_bidder_b')).toBe(await anonymousId('usr_bidder_b'))
    expect(await anonymousId('usr_bidder_a')).not.toBe(await anonymousId('usr_bidder_b'))
  })

  it('不含原始 user_id，且 null / 空串安全', async () => {
    const anon = await anonymousId('usr_bidder_a')
    expect(anon).not.toContain('usr_bidder_a')
    expect(anon).toMatch(/^anon-[0-9a-f]{8}$/)
    expect(await anonymousId(null)).toBeNull()
    expect(await anonymousId(undefined)).toBeNull()
    expect(await anonymousId('')).toBeNull()
  })

  it('缓存：ensure 之后可以同步读取，且重复 ensure 不改变结果', async () => {
    const cache = createAnonIdCache()
    expect(cache.get('usr_bidder_a')).toBeNull()
    await cache.ensure(['usr_bidder_a', 'usr_bidder_b', 'usr_bidder_a'])
    expect(cache.get('usr_bidder_a')).toBe('anon-2952873c')
    await cache.ensure(['usr_bidder_a'])
    expect(cache.get('usr_bidder_a')).toBe('anon-2952873c')
    expect(cache.get(null)).toBeNull()
  })
})

describe('会话存储', () => {
  const session: Session = {
    accessToken: 'jwt-token',
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    user: { id: 'usr_1', name: '林默', role: 'BIDDER' },
  }

  it('写入后可读回，clear 后为空', () => {
    const storage = createSessionStorage(createMemoryStorage())
    storage.write(session)
    expect(storage.read()).toEqual(session)
    storage.clear()
    expect(storage.read()).toBeNull()
  })

  it('过期的会话读出来是 null（并顺手清掉），不留着让下次启动再撞一次 401', () => {
    const backing = createMemoryStorage()
    const storage = createSessionStorage(backing)
    storage.write({ ...session, expiresAt: new Date(Date.now() - 1_000).toISOString() })
    expect(storage.read()).toBeNull()
    expect(backing.getItem('bid-arena.session')).toBeNull()
  })

  it('快过期的会话同样视为不可用（提前 30 秒判定）', () => {
    const soon = new Date(Date.now() + 5_000).toISOString()
    expect(isExpired({ ...session, expiresAt: soon })).toBe(true)
    expect(isExpired({ ...session, expiresAt: '不是时间' })).toBe(true)
    expect(isExpired(session)).toBe(false)
  })

  it('存储里是坏数据而不是崩溃', () => {
    const backing = createMemoryStorage()
    backing.setItem('bid-arena.session', '{ not json')
    expect(createSessionStorage(backing).read()).toBeNull()
    backing.setItem('bid-arena.session', JSON.stringify({ accessToken: 1 }))
    expect(createSessionStorage(backing).read()).toBeNull()
  })
})
