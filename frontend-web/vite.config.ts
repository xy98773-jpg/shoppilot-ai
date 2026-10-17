import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 开发期将 /api 代理到后端 Java 服务（默认 8080）。
      // ★ M3-B10：可用环境变量 VITE_PROXY_TARGET 指向隔离联调栈（如 http://localhost:8082），
      //   不改默认行为；仅开发服务器读取，构建产物无影响。
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
  // 单元测试（M3-B10 / T18-1）：jsdom 环境 + 全局 API + 只收集 src 下的 *.spec.ts。
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.spec.ts'],
  },
})
