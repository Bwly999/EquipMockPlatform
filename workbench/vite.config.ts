/**
 * 渲染进程构建（rolldown-vite = Oxc 驱动的 vite，CLI 仍是 vite）。
 * - @vitejs/plugin-react + babel-plugin-react-compiler（构建期开启 React Compiler）
 * - @tailwindcss/vite（Tailwind v4）
 * - base './'：Electron file:// 加载；worker 内联（Monaco，禁 CDN）
 */
import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

export default defineConfig({
  base: './',
  plugins: [
    react({
      babel: {
        plugins: [['babel-plugin-react-compiler', {}]],
      },
    }),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    target: 'chrome140',
    chunkSizeWarningLimit: 8000,
  },
  worker: {
    format: 'iife',
  },
})
