/** 开发模式：起 vite dev server，就绪后用其 URL 启动 Electron */
import { spawn } from 'node:child_process'
import * as path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const root = path.resolve(import.meta.dirname, '..')
const isWin = process.platform === 'win32'

const viteBin = path.join(root, 'node_modules/.bin', isWin ? 'vite.cmd' : 'vite')
const vite = spawn(viteBin, ['--port', '5199', '--strictPort'], {
  cwd: root,
  stdio: ['ignore', 'pipe', 'inherit'],
  shell: isWin,
})

let electron = null
const startElectron = () => {
  if (electron) return
  const electronPath = require('electron')
  electron = spawn(electronPath, ['.'], {
    cwd: root,
    stdio: 'inherit',
    env: { ...process.env, VITE_DEV_SERVER_URL: 'http://localhost:5199' },
  })
  electron.on('exit', () => {
    vite.kill()
    process.exit(0)
  })
}

let buffer = ''
vite.stdout.on('data', (chunk) => {
  const text = chunk.toString()
  process.stdout.write(`[vite] ${text}`)
  buffer += text
  if (/localhost:5199|Local:/.test(buffer)) {
    buffer = ''
    startElectron()
  }
})

const shutdown = () => {
  electron?.kill()
  vite.kill()
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
vite.on('exit', (code) => {
  if (!electron) process.exit(code ?? 0)
})
