import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // 开发环境把 /api 代理到后端（srm-backend 默认 8080）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // §1.6 上传件静态访问映射（后端 WebStaticResourceConfig：/uploads/** → 本地磁盘），
      // 开发环境一并代理到后端，资质列表 file_url 新窗口打开才不会 404
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
