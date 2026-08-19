/**
 * preload（CJS 打包为 preload.cjs）：把 IPC 契约封装成类型化桥暴露给渲染层。
 * 错误统一规整为带 message 的 Error 拒绝值；不向渲染层泄漏任何 Node API。
 */
import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'
import type {
  GroupInfo,
  ImportResult,
  PluginRow,
  OrphanJar,
  Settings,
  StateSnapshot,
  SubGroupDoc,
  WriteResult,
} from '../src/lib/types'

interface HomeInitResult {
  homePath: string
  created: string[]
  alreadyInitialized: boolean
}

interface SubGroupRef {
  file: string
  doc: SubGroupDoc
}

const invoke = async <T>(channel: string, ...args: unknown[]): Promise<T> => {
  try {
    return (await ipcRenderer.invoke(channel, ...args)) as T
  } catch (e) {
    const message = e instanceof Error ? e.message : typeof e === 'object' && e && 'message' in e ? String((e as { message: unknown }).message) : String(e)
    throw new Error(message || `IPC 调用失败：${channel}`)
  }
}

const api = {
  homeGet: (): Promise<{ homePath: string | null }> => invoke('home:get'),
  homeSelect: (): Promise<string | null> => invoke('home:select'),
  homeSet: (path: string): Promise<{ homePath: string | null }> => invoke('home:set', path),
  homeInitSkeleton: (path?: string): Promise<HomeInitResult> => invoke('home:initSkeleton', path),

  groupsList: (): Promise<GroupInfo[]> => invoke('groups:list'),
  groupCreate: (name: string): Promise<void> => invoke('group:create', name),
  groupCopy: (from: string, to: string): Promise<void> => invoke('group:copy', from, to),
  groupDelete: (name: string): Promise<void> => invoke('group:delete', name),
  groupRename: (from: string, to: string): Promise<void> => invoke('group:rename', from, to),
  groupReadAll: (group: string): Promise<SubGroupRef[]> => invoke('group:readAll', group),

  subGroupRead: (group: string, file: string): Promise<SubGroupDoc> => invoke('subgroup:read', group, file),
  subGroupWrite: (group: string, file: string, doc: SubGroupDoc): Promise<WriteResult> =>
    invoke('subgroup:write', group, file, doc),
  subGroupCreate: (group: string, name: string): Promise<{ file: string }> =>
    invoke('subgroup:create', group, name),

  settingsGetActive: (): Promise<Settings> => invoke('settings:getActive'),
  settingsSetActive: (group: string): Promise<void> => invoke('settings:setActive', group),
  settingsSetMockEnabled: (enabled: boolean): Promise<void> => invoke('settings:setMockEnabled', enabled),

  pluginsList: (): Promise<{ rows: PluginRow[]; orphanJars: OrphanJar[] }> => invoke('plugins:list'),
  pluginImport: (jarPath?: string | null, opts?: { overwrite?: boolean }): Promise<ImportResult> =>
    invoke('plugin:import', jarPath ?? null, opts),
  pluginEnable: (id: string, enabled: boolean): Promise<void> => invoke('plugin:enable', id, enabled),
  pluginSetAlias: (id: string, alias: string): Promise<void> => invoke('plugin:setAlias', id, alias),
  pluginRemove: (id: string, deleteJar: boolean): Promise<void> => invoke('plugin:remove', id, deleteJar),

  stateGet: (): Promise<StateSnapshot> => invoke('state:get'),
  stateSubscribe: (): Promise<StateSnapshot> => invoke('state:subscribe'),
  onStateChanged: (cb: (snapshot: StateSnapshot) => void): (() => void) => {
    const listener = (_e: IpcRendererEvent, snapshot: StateSnapshot): void => cb(snapshot)
    ipcRenderer.on('state:changed', listener)
    return () => ipcRenderer.removeListener('state:changed', listener)
  },
}

export type EquipMockApi = typeof api

contextBridge.exposeInMainWorld('equipmock', api)
