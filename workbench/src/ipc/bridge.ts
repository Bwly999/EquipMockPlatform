/** 渲染层到主进程的类型化桥（preload 暴露的 window.equipmock） */
import type { EquipMockApi } from '../../electron/preload'

declare global {
  interface Window {
    equipmock: EquipMockApi
  }
}

function assertBridge(): EquipMockApi {
  const api = (window as { equipmock?: EquipMockApi }).equipmock
  if (!api) {
    throw new Error('工作台桥未注入：请通过 Electron 启动（pnpm dev:electron / pnpm start）')
  }
  return api
}

export const bridge: EquipMockApi = new Proxy({} as EquipMockApi, {
  get(_target, prop: string) {
    const api = assertBridge()
    const value = (api as unknown as Record<string, unknown>)[prop]
    return typeof value === 'function' ? value.bind(api) : value
  },
})
