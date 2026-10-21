import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite 配置：React 插件 + 开发代理（后端就绪后将 /api 转发到 Spring Boot）
export default defineConfig({
  plugins: [react()],
  build: {
    // 生产包直接输出 UTF-8 中文，避免 ascii 转义成字面 \uXXXX
    charset: 'utf8',
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/antd') || id.includes('node_modules/@ant-design') || id.includes('node_modules/rc-')) {
            return 'antd';
          }
          return undefined;
        },
      },
    },
  },
  optimizeDeps: {
    include: ['antd'],
  },
  server: {
    port: 5173,
    proxy: {
      // 后端 API 代理占位：对应 server 端 Spring Boot 默认端口
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 聊天 SSE 可能持续数分钟（循环预算 300s），避免代理提前断开
        timeout: 360_000,
        proxyTimeout: 360_000,
      },
    },
  },
});
