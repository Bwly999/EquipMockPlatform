/** agent 状态 store（06 §7）：state:subscribe 推送 + 本地兜底刷新 */
import { create } from 'zustand'
import { bridge } from '../ipc/bridge'
import type { StateSnapshot } from '../lib/types'

interface StateState {
  snapshot: StateSnapshot | null
  subscribed: boolean
  error: string | null
  restart: () => Promise<void>
  refresh: () => Promise<void>
}

let unsubscribe: (() => void) | null = null

export const useStateStore = create<StateState>((set) => ({
  snapshot: null,
  subscribed: false,
  error: null,

  restart: async () => {
    unsubscribe?.()
    unsubscribe = null
    set({ subscribed: false, snapshot: null, error: null })
    try {
      const initial = await bridge.stateSubscribe()
      set({ snapshot: initial, subscribed: true })
      unsubscribe = bridge.onStateChanged((snapshot) => set({ snapshot }))
    } catch (e) {
      // 尚未设置 home 等场景：保留空快照，不报错弹窗
      set({ subscribed: false, error: e instanceof Error ? e.message : String(e) })
    }
  },

  refresh: async () => {
    try {
      const snapshot = await bridge.stateGet()
      set({ snapshot, error: null })
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e) })
    }
  },
}))
