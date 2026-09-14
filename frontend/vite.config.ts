import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 本机开发的两个可调项，默认与 README 一致（Vite 5173、后端 8080）：
 *   VITE_DEV_PORT        Vite 自身端口
 *   VITE_DEV_API_TARGET  后端 HTTP 地址（`/api` 的开发代理目标）
 *
 * 后端换了端口或跑在另一台机器时**不必改这个文件**：导出同名环境变量，
 * 或写在 `frontend/.env.local`（已被 .gitignore 排除）里即可。
 * 注意这里只影响 `npm run dev`；生产是 Nginx 反代（见 frontend/nginx.conf）。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  const devPort = Number(env.VITE_DEV_PORT || 5173)
  const apiTarget = env.VITE_DEV_API_TARGET || 'http://localhost:8080'
  return {
    plugins: [vue()],
    server: { port: devPort, proxy: { '/api': apiTarget } },
  }
})
