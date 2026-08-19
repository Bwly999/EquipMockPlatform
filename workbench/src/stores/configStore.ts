/**
 * 配置中心 store（06 §8）：组树 / 当前编辑文档 / 脏标记 / 双模式 / 校验结果。
 * 表单模式以 doc 为源，JSON 模式以 jsonText 为源；切换时做无损同步。
 */
import { create } from 'zustand'
import { bridge } from '../ipc/bridge'
import type { GroupInfo, Settings, SubGroupDoc, ValidationIssue } from '../lib/types'
import { checkGroupCrossFile } from '../lib/validation/groupChecks'
import { validateSubGroupDoc, validateSubGroupText } from '../lib/validation/validator'

export type EditorMode = 'form' | 'json'

export interface SaveOutcome {
  saved: boolean
  errors: ValidationIssue[]
}

interface ConfigState {
  groups: GroupInfo[]
  settings: Settings | null
  selectedGroup: string | null
  selectedFile: string | null
  doc: SubGroupDoc | null
  jsonText: string
  mode: EditorMode
  dirty: boolean
  saving: boolean
  lastSavedAt: number | null
  /** 文档级校验结果（schema+语义） */
  errors: ValidationIssue[]
  warnings: ValidationIssue[]
  /** 组级跨文件提示（06 §5.4） */
  groupWarnings: ValidationIssue[]
  loadError: string | null

  loadAll: () => Promise<void>
  refreshGroups: () => Promise<void>
  selectGroup: (name: string) => void
  selectSubGroup: (group: string, file: string) => Promise<void>
  clearDocument: () => void
  setMode: (mode: EditorMode) => boolean
  toggleMode: () => boolean
  mutate: (fn: (draft: SubGroupDoc) => void) => void
  setJsonText: (text: string) => void
  save: () => Promise<SaveOutcome>
  createGroup: (name: string) => Promise<void>
  copyGroup: (from: string, to: string) => Promise<void>
  deleteGroup: (name: string) => Promise<void>
  renameGroup: (from: string, to: string) => Promise<void>
  setActiveGroup: (group: string) => Promise<void>
  setMockEnabled: (enabled: boolean) => Promise<void>
  createSubGroup: (group: string, name: string) => Promise<void>
  /** 状态页跳转入口 */
  jumpTo: (group: string, file: string) => Promise<void>
}

function serializeDoc(doc: SubGroupDoc): string {
  return JSON.stringify(doc, null, 2) + '\n'
}

export const useConfigStore = create<ConfigState>((set, get) => ({
  groups: [],
  settings: null,
  selectedGroup: null,
  selectedFile: null,
  doc: null,
  jsonText: '',
  mode: 'form',
  dirty: false,
  saving: false,
  lastSavedAt: null,
  errors: [],
  warnings: [],
  groupWarnings: [],
  loadError: null,

  loadAll: async () => {
    set({ loadError: null })
    try {
      const [groups, settings] = await Promise.all([bridge.groupsList(), bridge.settingsGetActive()])
      const state = get()
      let selectedGroup = state.selectedGroup
      if (selectedGroup && !groups.some((g) => g.name === selectedGroup)) selectedGroup = null
      set({ groups, settings, selectedGroup })
      const selFile = get().selectedFile
      const grp = groups.find((g) => g.name === selectedGroup)
      if (selFile && (!grp || !grp.subGroups.some((s) => s.file === selFile))) {
        get().clearDocument()
      }
    } catch (e) {
      set({ loadError: e instanceof Error ? e.message : String(e) })
    }
  },

  refreshGroups: async () => {
    try {
      const groups = await bridge.groupsList()
      set({ groups })
    } catch {
      /* 载入失败由 loadAll 统一处理 */
    }
  },

  selectGroup: (name) => set({ selectedGroup: name }),

  selectSubGroup: async (group, file) => {
    const doc = await bridge.subGroupRead(group, file)
    set({
      selectedGroup: group,
      selectedFile: file,
      doc,
      jsonText: serializeDoc(doc),
      mode: 'form',
      dirty: false,
      errors: [],
      warnings: [],
      groupWarnings: [],
      lastSavedAt: null,
    })
    void refreshGroupWarnings(group)
  },

  clearDocument: () =>
    set({
      selectedFile: null,
      doc: null,
      jsonText: '',
      mode: 'form',
      dirty: false,
      errors: [],
      warnings: [],
      groupWarnings: [],
    }),

  setMode: (mode) => {
    const { mode: current, doc, jsonText } = get()
    if (mode === current) return true
    if (mode === 'json') {
      // 表单 → 源码：以当前 doc 序列化（未保存的修改也带过去）
      if (!doc) return false
      set({ mode, jsonText: serializeDoc(doc) })
      return true
    }
    // 源码 → 表单：文本必须可解析
    const result = validateSubGroupText(jsonText)
    if (!result.ok) {
      set({ errors: result.errors })
      return false
    }
    set({ mode, doc: result.doc, errors: [] })
    return true
  },

  toggleMode: () => get().setMode(get().mode === 'form' ? 'json' : 'form'),

  mutate: (fn) => {
    const { doc, mode } = get()
    if (!doc || mode !== 'form') return
    const draft = structuredClone(doc) as SubGroupDoc
    fn(draft)
    set({ doc: draft, dirty: true, lastSavedAt: null })
  },

  setJsonText: (text) => set({ jsonText: text, dirty: true, lastSavedAt: null }),

  save: async () => {
    const state = get()
    const { selectedGroup, selectedFile, mode } = state
    if (!selectedGroup || !selectedFile || state.saving) {
      return { saved: false, errors: [] }
    }

    let doc: SubGroupDoc | null = null
    if (mode === 'json') {
      const result = validateSubGroupText(state.jsonText)
      if (!result.ok) {
        set({ errors: result.errors, warnings: result.warnings })
        return { saved: false, errors: result.errors }
      }
      doc = result.doc
      set({ doc, errors: [], warnings: result.warnings })
    } else {
      doc = state.doc
      const result = validateSubGroupDoc(doc)
      if (!result.ok) {
        set({ errors: result.errors, warnings: result.warnings })
        return { saved: false, errors: result.errors }
      }
      set({ errors: [], warnings: result.warnings })
    }
    if (!doc) return { saved: false, errors: [] }

    set({ saving: true })
    try {
      const writeResult = await bridge.subGroupWrite(selectedGroup, selectedFile, doc)
      if (!writeResult.ok) {
        set({ errors: writeResult.errors, saving: false })
        return { saved: false, errors: writeResult.errors }
      }
      set({
        saving: false,
        dirty: false,
        lastSavedAt: Date.now(),
        jsonText: serializeDoc(doc),
      })
      void refreshGroupWarnings(selectedGroup)
      void get().refreshGroups()
      return { saved: true, errors: [] }
    } catch (e) {
      set({ saving: false })
      throw e
    }
  },

  createGroup: async (name) => {
    await bridge.groupCreate(name)
    await get().refreshGroups()
    set({ selectedGroup: name })
  },

  copyGroup: async (from, to) => {
    await bridge.groupCopy(from, to)
    await get().refreshGroups()
  },

  deleteGroup: async (name) => {
    await bridge.groupDelete(name)
    if (get().selectedGroup === name) get().clearDocument()
    set({ selectedGroup: null })
    await get().loadAll()
  },

  renameGroup: async (from, to) => {
    await bridge.groupRename(from, to)
    const patch: Partial<ConfigState> = {}
    if (get().selectedGroup === from) patch.selectedGroup = to
    await get().loadAll()
    set(patch)
  },

  setActiveGroup: async (group) => {
    await bridge.settingsSetActive(group)
    const settings = await bridge.settingsGetActive()
    set({ settings })
  },

  setMockEnabled: async (enabled) => {
    await bridge.settingsSetMockEnabled(enabled)
    const settings = await bridge.settingsGetActive()
    set({ settings })
  },

  createSubGroup: async (group, name) => {
    const { file } = await bridge.subGroupCreate(group, name)
    await get().refreshGroups()
    await get().selectSubGroup(group, file)
  },

  jumpTo: async (group, file) => {
    await get().loadAll()
    await get().selectSubGroup(group, file)
  },
}))

async function refreshGroupWarnings(group: string): Promise<void> {
  try {
    const refs = await bridge.groupReadAll(group)
    useConfigStore.setState({ groupWarnings: checkGroupCrossFile(refs) })
  } catch {
    useConfigStore.setState({ groupWarnings: [] })
  }
}
