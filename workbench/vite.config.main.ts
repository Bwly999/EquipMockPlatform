/** 主进程构建：ESM 单文件（dist-electron/main.mjs），external: electron + node 内置模块 */
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
      // node:* 必须显式 external，否则 lib 默认按浏览器环境替换成 stub
      external: ['electron', /^node:/],
    },
    target: 'node22',
    minify: false,
    sourcemap: false,
  },
})
