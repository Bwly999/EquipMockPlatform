import { create } from 'zustand'

export type Page = 'config' | 'plugins' | 'status'

export interface Toast {
  id: number
  type: 'success' | 'error' | 'info'
  message: string
}

interface UiState {
  page: Page
  toasts: Toast[]
  setPage: (page: Page) => void
  toast: (message: string, type?: Toast['type']) => void
  dismiss: (id: number) => void
}

let toastSeq = 1

export const useUiStore = create<UiState>((set) => ({
  page: 'config',
  toasts: [],
  setPage: (page) => set({ page }),
  toast: (message, type = 'info') => {
    const id = toastSeq++
    set((s) => ({ toasts: [...s.toasts, { id, type, message }].slice(-4) }))
    setTimeout(() => {
      set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }))
    }, type === 'error' ? 6000 : 2600)
  },
  dismiss: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
}))

/** 跳转配置页并定位到指定小分组（状态页 lastError 用） */
interface JumpTarget {
  group: string
  file: string
  nonce: number
}
interface JumpState {
  jumpTarget: JumpTarget | null
  jumpTo: (group: string, file: string) => void
  clearJump: () => void
}
export const useJumpStore = create<JumpState>((set) => ({
  jumpTarget: null,
  jumpTo: (group, file) => set({ jumpTarget: { group, file, nonce: Date.now() } }),
  clearJump: () => set({ jumpTarget: null }),
}))

/** 全局"未保存修改"守卫：切换页面/小分组/组前弹确认（06 §5.1） */
interface GuardState {
  pending: { title: string; description: string; action: () => void | Promise<void> } | null
  /** dirty 时弹确认，否则直接执行 */
  ask: (title: string, description: string, action: () => void | Promise<void>) => void
  resolveDiscard: () => Promise<void>
  resolveSave: () => Promise<void>
  resolveCancel: () => void
}

import { useConfigStore } from './configStore'

export const useGuardStore = create<GuardState>((set, get) => ({
  pending: null,
  ask: (title, description, action) => {
    const config = useConfigStore.getState()
    if (config.dirty && config.doc) {
      set({ pending: { title, description, action } })
    } else {
      void action()
    }
  },
  resolveDiscard: async () => {
    const pending = get().pending
    set({ pending: null })
    await pending?.action()
  },
  resolveSave: async () => {
    const pending = get().pending
    set({ pending: null })
    if (!pending) return
    const outcome = await useConfigStore.getState().save()
    if (outcome.saved) {
      useUiStore.getState().toast('已保存', 'success')
      await pending.action()
    } else {
      useUiStore.getState().toast('保存失败，已留在当前小分组', 'error')
    }
  },
  resolveCancel: () => set({ pending: null }),
}))
