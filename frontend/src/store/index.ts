/**
 * store 的统一入口。
 *
 * 只导出商店与依赖注入点：组件不该知道 `arena.ts`/`deps.ts` 的拆分，
 * 否则以后移动文件就要改一堆 import。
 */
export { useArenaStore } from './arena'
export { setArenaDeps, type ArenaDeps } from './deps'
export { DEMO_ACCOUNTS, describe, type LiveAuction } from './model'
