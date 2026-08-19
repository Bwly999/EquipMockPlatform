/** 主进程构建：ESM 单文件（dist-electron/main.mjs），external: electron */
import { defineConfig } from 'vite'

export default defineConfig({
  build: {
    outDir: 'dist-electron',
    emptyOutDir: false,
    lib: {
      entry: 'electron/main.ts',
      formats: ['es'],
      fileName: () => 'main.mjs',
    },
    rollupOptions: {
      external: ['electron'],
    },
    target: 'node22',
    minify: false,
    sourcemap: false,
  },
})
