/** preload 构建：CJS 单文件（dist-electron/preload.cjs），适配 sandbox 渲染进程 */
import { defineConfig } from 'vite'

export default defineConfig({
  build: {
    outDir: 'dist-electron',
    emptyOutDir: false,
    lib: {
      entry: 'electron/preload.ts',
      formats: ['cjs'],
      fileName: () => 'preload.cjs',
    },
    rollupOptions: {
      external: ['electron'],
    },
    target: 'node22',
    minify: false,
    sourcemap: false,
  },
})
