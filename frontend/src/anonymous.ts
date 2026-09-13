/**
 * 匿名标识（跨端契约，算法见 `docs/REALTIME_AND_COMMAND_FLOW.md` §4）：
 * `anon-` + `SHA-256(user_id)` 的前 4 字节十六进制（小写）。
 *
 * 为什么前端要自己算一遍而不是等服务端给：
 * - WS 事件里的 `leader` / `winner` 是匿名值，前端只有自己算出 `myAnonId` 才能判断
 *   “当前领先者是不是我”，从而高亮自己；
 * - HTTP 快照与出价记录里给的是原始 `user_id`，展示时必须先转成同一套匿名值，
 *   否则同一场拍卖里“我”在出价表和在事件里会显示成两个不同的东西。
 *
 * 算法公开且确定，因此两端实现必须是**同一个函数**的两种语言版本：
 * 这里的固定向量测试（`usr_bidder_a → anon-2952873c`）同时锁住了服务端实现。
 */
export async function anonymousId(userId: string | null | undefined): Promise<string | null> {
  if (!userId) return null
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(userId))
  const firstFourBytes = new Uint8Array(digest).subarray(0, 4)
  return 'anon-' + [...firstFourBytes].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

/**
 * 匿名标识缓存：显示层需要**同步**拿到匿名值（模板里不能 await），
 * 而摘要是异步 API。做法是“加载数据时顺手把出现过的 user_id 都解析一遍”，
 * 渲染时只查缓存。
 */
export interface AnonIdCache {
  ensure(userIds: Iterable<string | null | undefined>): Promise<void>
  get(userId: string | null | undefined): string | null
}

export function createAnonIdCache(): AnonIdCache {
  const cache = new Map<string, string>()

  return {
    async ensure(userIds) {
      const missing = [...new Set([...userIds].filter((id): id is string => typeof id === 'string' && id !== ''))]
        .filter((id) => !cache.has(id))
      // 单个 user_id 解析失败不该让整个列表加载失败：显示层拿不到就退化成原始 id 的短形态。
      await Promise.all(
        missing.map(async (id) => {
          const anon = await anonymousId(id)
          if (anon) cache.set(id, anon)
        }),
      )
    },
    get: (userId) => (userId ? (cache.get(userId) ?? null) : null),
  }
}
