/** pluginStore 测试（bridge 全 mock）：启停/别名/移除/导入覆盖确认流 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ImportResult, PluginRow } from '../src/lib/types'

const row = (over: Partial<PluginRow> = {}): PluginRow => ({
  id: 'mock-cabinet',
  alias: '机柜',
  jar: 'mock-cabinet-1.0.0.jar',
  jarExists: true,
  jarSizeBytes: 1024,
  enabled: true,
  importedAt: '2026-08-19T10:00:00+08:00',
  note: '',
  runtimeState: 'STARTED',
  runtimeVersion: '1.0.0',
  runtimeError: null,
  ...over,
})

let serverRows: PluginRow[] = [row()]

const bridgeMock = {
  pluginsList: vi.fn(async () => ({ rows: serverRows.map((r) => ({ ...r })), orphanJars: [] })),
  pluginImport: vi.fn(async (): Promise<ImportResult> => ({ status: 'cancelled' })),
  pluginEnable: vi.fn(async (id: string, enabled: boolean) => {
    serverRows = serverRows.map((r) => (r.id === id ? { ...r, enabled } : r))
  }),
  pluginSetAlias: vi.fn(async (id: string, alias: string) => {
    serverRows = serverRows.map((r) => (r.id === id ? { ...r, alias } : r))
  }),
  pluginRemove: vi.fn(async (id: string) => {
    serverRows = serverRows.filter((r) => r.id !== id)
  }),
}

vi.mock('../src/ipc/bridge', () => ({ bridge: bridgeMock }))

const { usePluginStore } = await import('../src/stores/pluginStore')

describe('pluginStore', () => {
  beforeEach(() => {
    serverRows = [row()]
    for (const fn of Object.values(bridgeMock)) fn.mockClear()
    usePluginStore.setState({ rows: [], orphanJars: [], loading: false, pendingOverwrite: null, importing: false })
  })

  it('reload 填充联合视图', async () => {
    await usePluginStore.getState().reload()
    expect(usePluginStore.getState().rows[0]!.id).toBe('mock-cabinet')
  })

  it('enable 即时生效：先乐观更新再调 IPC，失败回滚', async () => {
    await usePluginStore.getState().reload()
    await usePluginStore.getState().enable('mock-cabinet', false)
    expect(bridgeMock.pluginEnable).toHaveBeenCalledWith('mock-cabinet', false)
    expect(usePluginStore.getState().rows[0]!.enabled).toBe(false)

    bridgeMock.pluginEnable.mockRejectedValueOnce(new Error('boom'))
    await usePluginStore.getState().enable('mock-cabinet', true)
    // 失败后 reload 回滚到服务端状态（仍是 false）
    expect(usePluginStore.getState().rows[0]!.enabled).toBe(false)
  })

  it('setAlias 成功后本地更新', async () => {
    await usePluginStore.getState().reload()
    await usePluginStore.getState().setAlias('mock-cabinet', '新别名')
    expect(usePluginStore.getState().rows[0]!.alias).toBe('新别名')
  })

  it('remove 调 IPC 并刷新', async () => {
    await usePluginStore.getState().reload()
    await usePluginStore.getState().remove('mock-cabinet', true)
    expect(bridgeMock.pluginRemove).toHaveBeenCalledWith('mock-cabinet', true)
  })

  it('导入遇到同 id → needs-overwrite-confirm，确认后带 overwrite 重发', async () => {
    const pending: Extract<ImportResult, { status: 'needs-overwrite-confirm' }> = {
      status: 'needs-overwrite-confirm',
      jarPath: 'C:\\tmp\\mock-cabinet-1.0.0.jar',
      manifest: {
        pluginId: 'mock-cabinet',
        pluginVersion: '1.0.0',
        pluginRequires: null,
        pluginDescription: null,
      },
      existing: row(),
    }
    bridgeMock.pluginImport.mockResolvedValueOnce(pending)
    await usePluginStore.getState().importPlugin()
    expect(usePluginStore.getState().pendingOverwrite?.status).toBe('needs-overwrite-confirm')

    bridgeMock.pluginImport.mockResolvedValueOnce({ status: 'overwritten', plugin: row(), warnings: [] })
    await usePluginStore.getState().confirmOverwrite()
    expect(bridgeMock.pluginImport).toHaveBeenLastCalledWith(pending.jarPath, { overwrite: true })
    expect(usePluginStore.getState().pendingOverwrite).toBe(null)
  })

  it('导入取消不产生状态', async () => {
    bridgeMock.pluginImport.mockResolvedValueOnce({ status: 'cancelled' })
    await usePluginStore.getState().importPlugin()
    expect(usePluginStore.getState().pendingOverwrite).toBe(null)
    expect(usePluginStore.getState().importing).toBe(false)
  })
})
