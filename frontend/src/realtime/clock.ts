/**
 * 服务端校准时钟。
 *
 * 为什么不能直接用 `Date.now()` 比 `endsAt`：**客户端时钟可能整分钟地偏**。
 * 本项目实测过一次：开发机比虚拟机快约 2 分 40 秒，用本地时间算倒计时会先把一场还没结束的拍卖
 * 显示成“已结束”，再让按钮在服务端看来完全合法的时候保持禁用。
 *
 * 做法是最朴素也最可靠的一种：每次收到带 `serverTime` 的响应/事件，就算出偏移量，
 * 之后所有“现在几点了”都问这个时钟。偏移量取**最新一次的采样**，
 * 因为网络往返会把单次采样污染成偏小或偏大；用最新值至少不会让两次采样互相打架。
 */
export interface ServerClock {
  /** 用服务端时间校准本地时钟。 */
  sync(serverTime: string, receivedAt?: number): void
  /** 当前的“服务端时间”，单位毫秒。 */
  now(): number
  /** 距离 `endsAt`（ISO 字符串）还有多少毫秒；无效输入返回 null。 */
  remainingMs(endsAt: string | null | undefined): number | null
  /** 是否已经校准过。没校准过时界面应当显示“同步中”而不是猜一个倒计时。 */
  readonly synced: boolean
  /** 当前偏移量（服务端 - 本地），毫秒。 */
  offsetMs(): number
}

export function createServerClock(): ServerClock {
  let offset = 0
  let synced = false

  return {
    sync(serverTime, receivedAt = Date.now()) {
      const server = Date.parse(serverTime)
      if (Number.isNaN(server)) return
      offset = server - receivedAt
      synced = true
    },
    now: () => Date.now() + offset,
    remainingMs(endsAt) {
      if (!endsAt) return null
      const end = Date.parse(endsAt)
      if (Number.isNaN(end)) return null
      return end - this.now()
    },
    get synced() {
      return synced
    },
    offsetMs: () => offset,
  }
}

/** 把剩余毫秒格式化成 `mm:ss`（负数按 0 处理：过期的拍卖显示 `00:00`）。 */
export function formatRemaining(ms: number | null): string {
  if (ms === null) return '--:--'
  const total = Math.max(0, Math.ceil(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}
