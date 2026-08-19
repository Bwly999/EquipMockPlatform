/** home store（06 §2 / §8）：路径定位、骨架初始化、切换后全量重载 */
import { create } from 'zustand'
import { bridge } from '../ipc/bridge'
import { useConfigStore } from './configStore'
import { usePluginStore } from './pluginStore'
import { useStateStore } from './stateStore'
import { useUiStore } from './uiStore'

interface HomeState {
  homePath: string | null
  ready: boolean
  busy: boolean
  error: string | null
  init: () => Promise<void>
  selectAndSet: () => Promise<boolean>
  initSkeletonHere: () => Promise<boolean>
  reloadAll: () => Promise<void>
}

async function reloadAll(): Promise<void> {
  await Promise.all([
    useConfigStore.getState().loadAll(),
    usePluginStore.getState().reload(),
    useStateStore.getState().restart(),
  ])
}

export const useHomeStore = create<HomeState>((set, get) => ({
  homePath: null,
  ready: false,
  busy: false,
  error: null,

  init: async () => {
    try {
      const { homePath } = await bridge.homeGet()
      set({ homePath, ready: true })
      if (homePath) await reloadAll()
    } catch (e) {
      set({ ready: true, error: e instanceof Error ? e.message : String(e) })
    }
  },

  selectAndSet: async () => {
    if (get().busy) return false
    set({ busy: true, error: null })
    try {
      const picked = await bridge.homeSelect()
      if (!picked) {
        set({ busy: false })
        return false
      }
      const { homePath } = await bridge.homeSet(picked)
      set({ homePath, busy: false })
      await reloadAll()
      useUiStore.getState().toast(`主目录已切换：${homePath}`, 'success')
      return true
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      set({ busy: false, error: message })
      useUiStore.getState().toast(message, 'error')
      return false
    }
  },

  initSkeletonHere: async () => {
    if (get().busy) return false
    set({ busy: true, error: null })
    try {
      const result = await bridge.homeInitSkeleton()
      set({ homePath: result.homePath, busy: false })
      await reloadAll()
      useUiStore.getState().toast(
        result.alreadyInitialized ? '该目录已是 equip-mock 主目录，直接打开' : '骨架已初始化（default / fault-sim 示例组）',
        'success',
      )
      return true
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      set({ busy: false, error: message })
      if (message !== '已取消') useUiStore.getState().toast(message, 'error')
      return false
    }
  },

  reloadAll,
}))
