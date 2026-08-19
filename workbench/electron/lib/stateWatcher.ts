/**
 * state.json + logs/agent.log 的变化监听（06 §3 state:subscribe）：
 * fs.watch（Windows 递归）为主 + 1s 轮询 mtime/size 兜底，变化后组装快照推送渲染层。
 * agentAlive = lastWriteAt 在 3s 内更新过 且 pid 存活（主进程探测）。
 */
import * as fs from 'node:fs'
import type { StateDoc, StateSnapshot } from '../../src/lib/types'
import { readLogTail, readStateTolerant } from './store'

const HEARTBEAT_MS = 3000

export function isPidAlive(pid: number | undefined | null): boolean {
  if (!pid || !Number.isInteger(pid) || pid <= 0) return false
  try {
    process.kill(pid, 0)
    return true
  } catch (e) {
    const code = (e as NodeJS.ErrnoException)?.code
    // Windows 上无权限时返回 EPERM——进程存在
    return code === 'EPERM'
  }
}

export function computeAgentAlive(state: StateDoc | null, now = Date.now()): boolean {
  if (!state?.lastWriteAt) return false
  const t = Date.parse(state.lastWriteAt)
  if (Number.isNaN(t)) return false
  if (now - t > HEARTBEAT_MS + 1500) return false // 解析侧允许 1.5s 时钟偏差
  return isPidAlive(state.pid)
}

/**
 * 组装快照。state.json 解析失败（原子写替换间隙读到半截）时沿用 lastGoodState，
 * 避免 UI 闪断（编排方与 Java M1 侧对齐的读取容错）。
 */
export function buildSnapshot(home: string, lastGoodState: StateDoc | null = null): StateSnapshot {
  const { doc, parseFailed } = readStateTolerant(home)
  const state = parseFailed ? lastGoodState : doc
  const logTail = readLogTail(home, 200)
  return {
    state,
    logTail,
    agentAlive: computeAgentAlive(state),
    pidAlive: isPidAlive(state?.pid),
    snapshotAt: new Date().toISOString(),
  }
}

export class StateWatcher {
  private watcher: fs.FSWatcher | null = null
  private pollTimer: NodeJS.Timeout | null = null
  private debounceTimer: NodeJS.Timeout | null = null
  private lastSignature = ''
  private lastGoodState: StateDoc | null = null
  private home: string | null = null
  private onSnapshot: (snapshot: StateSnapshot) => void

  constructor(onSnapshot: (snapshot: StateSnapshot) => void) {
    this.onSnapshot = onSnapshot
  }

  start(home: string): void {
    this.stop()
    this.home = home
    this.lastSignature = ''
    this.lastGoodState = null
    this.maybeEmit()

    // fs.watch：Windows 支持递归监听根目录（state.json 在根、agent 日志在 logs/）
    try {
      this.watcher = fs.watch(home, { recursive: true }, (_event, filename) => {
        const name = String(filename ?? '')
        if (!/^(state\.json|logs[/\\]agent\.log(\.\d+)*|logs[/\\])/.test(name)) return
        this.scheduleEmit(300)
      })
      this.watcher.on('error', () => {
        /* 监听失效由轮询兜底 */
      })
    } catch {
      /* 目录暂不可监听，由轮询兜底 */
    }

    // 1s 轮询兜底（防 Windows 偶发丢事件，同 D14 思路）
    this.pollTimer = setInterval(() => this.maybeEmit(), 1000)
    // 心跳灯需要随时间衰减为灰色
    this.pollTimer.unref?.()
  }

  stop(): void {
    this.watcher?.close()
    this.watcher = null
    if (this.pollTimer) clearInterval(this.pollTimer)
    this.pollTimer = null
    if (this.debounceTimer) clearTimeout(this.debounceTimer)
    this.debounceTimer = null
    this.home = null
  }

  private scheduleEmit(delay: number): void {
    if (this.debounceTimer) clearTimeout(this.debounceTimer)
    this.debounceTimer = setTimeout(() => this.maybeEmit(), delay)
  }

  private maybeEmit(): void {
    if (!this.home) return
    try {
      const snapshot = buildSnapshot(this.home, this.lastGoodState)
      if (snapshot.state) this.lastGoodState = snapshot.state
      const signature = JSON.stringify(snapshot.state) + '|' + snapshot.logTail.length + '|' + snapshot.logTail.at(-1) + '|' + snapshot.agentAlive + '|' + snapshot.pidAlive
      if (signature !== this.lastSignature) {
        this.lastSignature = signature
        this.onSnapshot(snapshot)
      }
    } catch {
      /* 读取瞬时失败（原子写中间态）由下次轮询补 */
    }
  }
}
