import type { ApiCode } from './types'

/**
 * 客户端侧的错误码集合。
 *
 * `NETWORK` 是本层新增的：它不是契约里的业务码，而是“请求根本没走到服务端”。
 * 把它混进业务码里会让人以为服务端返回了它；单独留一个值，UI 才能区分
 * “服务端说不行”与“我们没连上”。
 */
export type ClientErrorCode = ApiCode | 'NETWORK'

/**
 * 错误码 → 面向用户的提示。
 *
 * 为什么不用服务端返回的 `message` 直接展示：那是给排障看的（可能含英文、字段名、内部细节），
 * 而且一旦服务端改了文案，前端录屏里的提示就会跟着变。这里按**错误码**分支，
 * 服务端的 message 只作为兜底与日志信息保留（见 `ApiError.serverMessage`）。
 */
export function messageForCode(code: ClientErrorCode): string {
  switch (code) {
    case 'OK':
      return '成功'
    case 'IDEMPOTENCY_REPLAY':
      return '本次提交已按首次结果处理'
    case 'VALIDATION_FAILED':
      return '请求参数不合法，请检查填写内容'
    case 'UNAUTHENTICATED':
      return '登录已过期，请重新登录'
    case 'FORBIDDEN':
      return '当前账号没有权限执行该操作'
    case 'NOT_FOUND':
      return '拍卖不存在或已被删除'
    case 'METHOD_NOT_ALLOWED':
      return '接口不支持该请求方式'
    case 'INVALID_STATE':
      return '拍卖当前状态不允许该操作'
    case 'BID_TOO_LOW':
      return '出价低于最低加价，请提高金额'
    case 'BID_LATE':
      return '已经超过截止时间，本次出价被拒绝'
    case 'HUMAN_ONLY_PERIOD':
      // 尾段“博弈时间”：真人仍可出价，Agent 被系统清场（见 DECISIONS D-32）。
      return '已进入最后博弈时间，仅限真人出价'
    case 'NOT_JOINED':
      return '请先加入本场拍卖'
    case 'INSUFFICIENT_BALANCE':
      return '可用余额不足'
    case 'CONFLICT':
      return '操作冲突，请刷新后重试'
    case 'RATE_LIMITED':
      return '操作过于频繁，请稍后再试'
    case 'INTERNAL_ERROR':
      return '服务端出错了，请稍后重试'
    case 'NETWORK':
      return '网络不可用或请求超时，请重试'
  }
}

/** 从错误 `data` 里取“最低允许金额”。契约里 `BID_TOO_LOW` 的细节字段。 */
export function minimumOf(details: unknown): number | null {
  if (typeof details !== 'object' || details === null) return null
  const value = (details as Record<string, unknown>)['minimum']
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}
