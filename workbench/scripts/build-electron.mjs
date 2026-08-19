/** 主进程 + preload 构建：清空 dist-electron 后两次 vite lib 构建（ESM main + CJS preload） */
import { spawnSync } from 'node:child_process'
import { rmSync } from 'node:fs'
import * as path from 'node:path'

const root = path.resolve(import.meta.dirname, '..')
rmSync(path.join(root, 'dist-electron'), { recursive: true, force: true })

const viteJs = path.join(root, 'node_modules', 'vite', 'bin', 'vite.js')
for (const config of ['vite.config.main.ts', 'vite.config.preload.ts']) {
  const r = spawnSync(process.execPath, [viteJs, 'build', '--config', config], { cwd: root, stdio: 'inherit' })
  if (r.status !== 0) {
    console.error(`构建失败：${config}`)
    process.exit(r.status ?? 1)
  }
}
console.log('✓ 主进程与 preload 构建完成（dist-electron/main.mjs + preload.cjs）')
