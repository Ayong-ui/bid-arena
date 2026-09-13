import { defineConfig } from 'vitest/config'

// 单元测试只覆盖“与 HTTP/WS 边界打交道的那一层”（api client、realtime client、store），
// 因此环境用 node 就够：这些模块不碰 DOM，令牌存储也由测试注入内存实现。
// 不引入 jsdom 是刻意的——为了能测一个 localStorage 就拉进整套 DOM 模拟，
// 会让“前端测试”变成“跑得慢又测不到契约”的装饰。
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
