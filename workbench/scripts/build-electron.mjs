/** 主进程 + preload 构建：清空 dist-electron 后两次 vite lib 构建（ESM main + CJS preload） */
import { spawnSync } from 'node:child_process'
import { rmSync } from 'node:fs'
import * as path from 'node:path'

const root = path.resolve(import.meta.dirname, '..')
rmSync(path.join(root, 'dist-electron'), { recursive: true, force: true })

for (const config of ['vite.config.main.ts', 'vite.config.preload.ts']) {
  const r = spawnSync(
    process.platform === 'win32' ? path.join(root, 'node_modules/.bin/vite.cmd') : path.join(root, 'node_modules/.bin/vite'),
    ['build', '--config', config],
    { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' },
  )
  if (r.status !== 0) {
    console.error(`构建失败：${config}`)
    process.exit(r.status ?? 1)
  }
}
console.log('✓ 主进程与 preload 构建完成（dist-electron/main.mjs + preload.cjs）')
