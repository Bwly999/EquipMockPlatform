/**
 * 原子写（04 §5）：写 `<file>.tmp-<6位随机>` 后 rename 替换；
 * Windows 下目标被短暂占用（杀毒/索引/agent 读取）时 rename 可能 EPERM/EACCES——重试 3 次。
 * 任何路径不直接覆写目标文件。
 */
import * as fs from 'node:fs'
import * as path from 'node:path'

export function randomId6(): string {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789'
  let out = ''
  for (let i = 0; i < 6; i++) out += chars[Math.floor(Math.random() * chars.length)]
  return out
}

export function sleepSync(ms: number): void {
  const shared = new SharedArrayBuffer(4)
  Atomics.wait(new Int32Array(shared), 0, 0, ms)
}

export async function atomicWriteFile(absPath: string, content: string): Promise<void> {
  const dir = path.dirname(absPath)
  const tmp = path.join(dir, `${path.basename(absPath)}.tmp-${randomId6()}`)
  // utf-8 无 BOM
  const fd = fs.openSync(tmp, 'w')
  try {
    fs.writeFileSync(fd, content, 'utf8')
    fs.fsyncSync(fd)
  } finally {
    fs.closeSync(fd)
  }
  try {
    await renameWithRetry(tmp, absPath, 3)
  } catch (e) {
    try {
      fs.unlinkSync(tmp)
    } catch {
      /* 清理失败不影响错误上抛 */
    }
    throw e
  }
}

export async function renameWithRetry(src: string, dst: string, retries: number): Promise<void> {
  let lastErr: unknown
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      fs.renameSync(src, dst)
      return
    } catch (e) {
      lastErr = e
      const code = (e as NodeJS.ErrnoException)?.code
      if (code !== 'EPERM' && code !== 'EACCES' && code !== 'EBUSY') throw e
      if (attempt < retries) sleepSync(50 * (attempt + 1))
    }
  }
  throw lastErr
}

export function readJsonIfExists(absPath: string): unknown | undefined {
  try {
    const text = fs.readFileSync(absPath, 'utf8')
    return JSON.parse(stripBom(text))
  } catch (e) {
    const code = (e as NodeJS.ErrnoException)?.code
    if (code === 'ENOENT') return undefined
    throw e
  }
}

export function stripBom(text: string): string {
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text
}

export function writeJsonPretty(value: unknown): string {
  return JSON.stringify(value, null, 2) + '\n'
}
