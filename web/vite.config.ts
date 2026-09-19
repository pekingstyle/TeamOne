import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      // W4：REST API 代理到本地 Spring Boot 后端。
      // 显式 127.0.0.1 而非 localhost：Node 17+ 对 localhost 按 DNS 序尝试 ::1/127.0.0.1，
      // WSL 镜像网络下 ::1 恒拒 → http-proxy 报 AggregateError ECONNREFUSED（502）
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
      // W3 WS 网关（auth/sub/msg/event 信令）
      '/ws': { target: 'ws://127.0.0.1:8080', ws: true },
    },
  },
})
