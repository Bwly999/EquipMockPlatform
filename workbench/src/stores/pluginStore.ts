/** 插件 store（06 §6）：清单联合视图、导入流程状态、启停/别名/移除 */
import { create } from 'zustand'
import { bridge } from '../ipc/bridge'
import type { ImportResult, OrphanJar, PluginRow } from '../lib/types'
import { useUiStore } from './uiStore'

interface PluginState {
  rows: PluginRow[]
  orphanJars: OrphanJar[]
  loading: boolean
  /** 导入流程：null 空闲；否则待确认的覆盖信息 */
  pendingOverwrite: Extract<ImportResult, { status: 'needs-overwrite-confirm' }> | null
  importing: boolean
  reload: () => Promise<void>
  importPlugin: () => Promise<void>
  confirmOverwrite: () => Promise<void>
  cancelOverwrite: () => void
  enable: (id: string, enabled: boolean) => Promise<void>
  setAlias: (id: string, alias: string) => Promise<void>
  remove: (id: string, deleteJar: boolean) => Promise<void>
}

export const usePluginStore = create<PluginState>((set, get) => ({
  rows: [],
  orphanJars: [],
  loading: false,
  pendingOverwrite: null,
  importing: false,

  reload: async () => {
    set({ loading: true })
    try {
      const { rows, orphanJars } = await bridge.pluginsList()
      set({ rows, orphanJars, loading: false })
    } catch (e) {
      set({ loading: false })
      useUiStore.getState().toast(e instanceof Error ? e.message : String(e), 'error')
    }
  },

  importPlugin: async () => {
    if (get().importing) return
    set({ importing: true })
    try {
      const result = await bridge.pluginImport()
      if (result.status === 'cancelled') {
        set({ importing: false })
        return
      }
      if (result.status === 'needs-overwrite-confirm') {
        set({ pendingOverwrite: result, importing: false })
        return
      }
      await get().reload()
      set({ importing: false })
      const verb = result.status === 'overwritten' ? '已覆盖导入' : '已导入'
      const extra = result.warnings.length ? `（${result.warnings.join('；')}）` : ''
      useUiStore.getState().toast(`${verb}：${result.plugin.id} v${result.plugin.runtimeVersion ?? ''}${extra}`.replace(' v', result.plugin.runtimeVersion ? ' v' : ''), result.warnings.length ? 'info' : 'success')
    } catch (e) {
      set({ importing: false })
      useUiStore.getState().toast(e instanceof Error ? e.message : String(e), 'error')
    }
  },

  confirmOverwrite: async () => {
    const pending = get().pendingOverwrite
    if (!pending) return
    set({ pendingOverwrite: null, importing: true })
    try {
      const result = await bridge.pluginImport(pending.jarPath, { overwrite: true })
      await get().reload()
      set({ importing: false })
      if (result.status === 'overwritten' || result.status === 'imported') {
        useUiStore.getState().toast(`已覆盖导入：${result.plugin.id}`, 'success')
      }
    } catch (e) {
      set({ importing: false })
      useUiStore.getState().toast(e instanceof Error ? e.message : String(e), 'error')
    }
  },

  cancelOverwrite: () => set({ pendingOverwrite: null }),

  enable: async (id, enabled) => {
    const rows = get().rows.map((r) => (r.id === id ? { ...r, enabled } : r))
    set({ rows })
    try {
      await bridge.pluginEnable(id, enabled)
      await get().reload()
    } catch (e) {
      useUiStore.getState().toast(e instanceof Error ? e.message : String(e), 'error')
      await get().reload()
    }
  },

  setAlias: async (id, alias) => {
    try {
      await bridge.pluginSetAlias(id, alias)
      set({ rows: get().rows.map((r) => (r.id === id ? { ...r, alias } : r)) })
    } catch (e) {
      useUiStore.getState().toast(e instanceof Error ? e.message : String(e), 'error')
    }
  },

  remove: async (id, deleteJar) => {
    try {
      await bridge.pluginRemove(id, deleteJar)
      await get().reload()
      useUiStore.getState().toast(`已移除插件：${id}${deleteJar ? '（jar 已删除）' : ''}`, 'success')
    } catch (e) {
      useUiStore.getState().toast(e instanceof Error ? e.message : String(e), 'error')
    }
  },
}))
