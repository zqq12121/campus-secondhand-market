import { defineConfig, loadEnv, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { mockPlugin } from './mock/plugin'

/** 开发时把 /admin/* 的页面请求交给 admin.html（后台用 history 路由，base 为 /admin/） */
function adminHistoryFallback(): Plugin {
  return {
    name: 'admin-history-fallback',
    configureServer(server) {
      server.middlewares.use((req, _res, next) => {
        const url = req.url ?? ''
        if (url === '/admin' || (url.startsWith('/admin/') && !url.includes('.') && req.headers.accept?.includes('text/html'))) {
          req.url = '/admin.html'
        }
        next()
      })
    },
  }
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const useMock = env.VITE_USE_MOCK === 'true'
  return {
    plugins: [vue(), adminHistoryFallback(), useMock && mockPlugin()],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: {
      port: 5180,
      proxy: useMock
        ? undefined
        : {
            // 先匹配：校园二手助手走 Python ai-service
            '/api/v1/ai': { target: env.VITE_AI_TARGET || 'http://localhost:8090', changeOrigin: true },
            '/api': { target: env.VITE_API_TARGET, changeOrigin: true },
            '/ws': { target: env.VITE_API_TARGET, ws: true, changeOrigin: true },
          },
    },
    build: {
      rollupOptions: {
        input: {
          web: fileURLToPath(new URL('./index.html', import.meta.url)),
          admin: fileURLToPath(new URL('./admin.html', import.meta.url)),
        },
      },
    },
  }
})
