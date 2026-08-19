/** 生产模式启动：直接以构建产物运行 Electron（需先 pnpm build） */
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import * as path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const root = path.resolve(import.meta.dirname, '..')

const mainFile = path.join(root, 'dist-electron/main.mjs')
if (!existsSync(mainFile)) {
  console.error('未找到 dist-electron/main.mjs，请先执行 pnpm build')
  process.exit(1)
}

const electronPath = require('electron')
const child = spawn(electronPath, ['.'], {
  cwd: root,
  stdio: 'inherit',
  env: { ...process.env },
})
child.on('exit', (code) => process.exit(code ?? 0))
