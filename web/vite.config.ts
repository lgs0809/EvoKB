import { defineConfig, loadEnv } from 'vite'
import preact from '@preact/preset-vite'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:18080'

  return {
    plugins: [preact(), tailwindcss()],
    build: {
      outDir: 'dist',
      emptyOutDir: true,
    },
    server: {
      proxy: {
        '/api': proxyTarget,
        '/mcp': proxyTarget,
      },
    },
  }
})
