import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite 配置：React 插件 + 开发代理（后端就绪后将 /api 转发到 Spring Boot）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 后端 API 代理占位：对应 server 端 Spring Boot 默认端口
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
