import { ApiError } from '../api/client'

/**
 * 测试辅助：把“期待失败”的请求收敛成 `ApiError`。
 *
 * 直接写 `.catch((error) => error as ApiError)` 会得到 `ApiError | ApiOk<T>` 这种联合类型，
 * 于是每条断言都要再收窄一次；更糟的是它把“其实成功了”悄悄当成通过。
 * 这里在“本应失败却成功”时直接抛错，把用例写错的情形也变成可见的红。
 */
export async function expectApiFailure(promise: Promise<unknown>): Promise<ApiError> {
  const outcome = await promise.then(
    (value) => value,
    (error: unknown) => error,
  )
  if (!(outcome instanceof ApiError)) {
    throw new Error(`本应失败，却成功了：${JSON.stringify(outcome)}`)
  }
  return outcome
}
