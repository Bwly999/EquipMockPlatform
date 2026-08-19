/** stores 测试：configStore 的脏标记/保存流/双模式切换，pluginStore 的启停（bridge 全 mock） */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GroupInfo, SubGroupDoc, WriteResult } from '../src/lib/types'
import { SKELETON_DEFAULT_CABINET } from '../src/lib/skeletonData'

const groups: GroupInfo[] = [
  { name: 'default', subGroups: [{ file: 'cabinet.json', name: 'cabinet', mockCount: 2 }] },
  { name: 'fault-sim', subGroups: [{ file: 'cabinet.json', name: 'cabinet', mockCount: 2 }] },
]

let settingsState = { activeGroup: 'default', mockEnabled: true }
let groupsState: GroupInfo[] = groups

const bridgeMock = {
  groupsList: vi.fn(async () => groupsState),
  settingsGetActive: vi.fn(async () => ({ ...settingsState })),
  settingsSetActive: vi.fn(async (_g: string) => {
    settingsState = { ...settingsState, activeGroup: _g }
  }),
  settingsSetMockEnabled: vi.fn(async (e: boolean) => {
    settingsState = { ...settingsState, mockEnabled: e }
  }),
  groupCreate: vi.fn(async (name: string) => {
    groupsState = [...groupsState, { name, subGroups: [] }]
  }),
  groupCopy: vi.fn(async () => undefined),
  groupDelete: vi.fn(async (name: string) => {
    groupsState = groupsState.filter((g) => g.name !== name)
  }),
  groupRename: vi.fn(async (from: string, to: string) => {
    groupsState = groupsState.map((g) => (g.name === from ? { ...g, name: to } : g))
    if (settingsState.activeGroup === from) settingsState = { ...settingsState, activeGroup: to }
  }),
  groupReadAll: vi.fn(async () => [{ file: 'cabinet.json', doc: SKELETON_DEFAULT_CABINET }]),
  subGroupRead: vi.fn(async () => JSON.parse(JSON.stringify(SKELETON_DEFAULT_CABINET)) as SubGroupDoc),
  subGroupWrite: vi.fn(async (_g: string, _f: string, doc: SubGroupDoc): Promise<WriteResult> => {
    void doc
    return { ok: true, errors: [] }
  }),
  subGroupCreate: vi.fn(async () => ({ file: 'newsub.json' })),
}

vi.mock('../src/ipc/bridge', () => ({ bridge: bridgeMock }))

const { useConfigStore } = await import('../src/stores/configStore')

const reset = () => {
  settingsState = { activeGroup: 'default', mockEnabled: true }
  groupsState = groups
  useConfigStore.setState({
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
  })
  for (const fn of Object.values(bridgeMock)) fn.mockClear()
}

describe('configStore', () => {
  beforeEach(reset)

  it('loadAll 加载组树与 settings', async () => {
    await useConfigStore.getState().loadAll()
    const s = useConfigStore.getState()
    expect(s.groups).toHaveLength(2)
    expect(s.settings?.activeGroup).toBe('default')
  })

  it('selectSubGroup 载入文档且干净；mutate 置脏', async () => {
    await useConfigStore.getState().selectSubGroup('default', 'cabinet.json')
    let s = useConfigStore.getState()
    expect(s.dirty).toBe(false)
    expect(s.doc!.mocks).toHaveLength(2)

    useConfigStore.getState().mutate((d) => {
      d.mocks[0]!.method = 'readStatus2'
    })
    s = useConfigStore.getState()
    expect(s.dirty).toBe(true)
    // mutate 不污染原快照（结构克隆）
    expect(s.doc!.mocks[0]!.method).toBe('readStatus2')
  })

  it('save：校验通过 → IPC 原子写，dirty 复位', async () => {
    await useConfigStore.getState().selectSubGroup('default', 'cabinet.json')
    useConfigStore.getState().mutate((d) => {
      d.description = '改一下'
    })
    const outcome = await useConfigStore.getState().save()
    expect(outcome.saved).toBe(true)
    expect(bridgeMock.subGroupWrite).toHaveBeenCalledOnce()
    expect(useConfigStore.getState().dirty).toBe(false)
    expect(useConfigStore.getState().lastSavedAt).not.toBeNull()
  })

  it('save：校验失败（非法正则）不落盘，返回错误列表', async () => {
    await useConfigStore.getState().selectSubGroup('default', 'cabinet.json')
    useConfigStore.getState().mutate((d) => {
      d.mocks[0]!.rules[1]!.argsPattern = ['[bad']
    })
    bridgeMock.subGroupWrite.mockClear()
    const outcome = await useConfigStore.getState().save()
    expect(outcome.saved).toBe(false)
    expect(outcome.errors[0]!.path).toBe('mocks[0].rules[1].argsPattern[0]')
    expect(bridgeMock.subGroupWrite).not.toHaveBeenCalled()
    expect(useConfigStore.getState().dirty).toBe(true)
  })

  it('save：主进程拒绝（返回 errors）也不算成功', async () => {
    await useConfigStore.getState().selectSubGroup('default', 'cabinet.json')
    bridgeMock.subGroupWrite.mockResolvedValueOnce({
      ok: false,
      errors: [{ path: 'name', message: '主进程兜底校验失败' }],
    })
    const outcome = await useConfigStore.getState().save()
    expect(outcome.saved).toBe(false)
    expect(useConfigStore.getState().errors[0]!.message).toBe('主进程兜底校验失败')
  })

  it('双模式无损切换：form→json→改文本→json→form 同步', async () => {
    await useConfigStore.getState().selectSubGroup('default', 'cabinet.json')
    expect(useConfigStore.getState().setMode('json')).toBe(true)
    expect(useConfigStore.getState().jsonText).toContain('PowerDevice')

    useConfigStore.getState().setJsonText(JSON.stringify({ name: 't', mocks: [] }, null, 2))
    expect(useConfigStore.getState().setMode('form')).toBe(true)
    expect(useConfigStore.getState().doc!.name).toBe('t')

    // 文本非法时禁止切回表单
    useConfigStore.getState().setMode('json')
    useConfigStore.getState().setJsonText('{oops')
    expect(useConfigStore.getState().setMode('form')).toBe(false)
    expect(useConfigStore.getState().mode).toBe('json')
  })

  it('JSON 模式保存：文本中的修改经校验后写入', async () => {
    await useConfigStore.getState().selectSubGroup('default', 'cabinet.json')
    useConfigStore.getState().setMode('json')
    const doc = JSON.parse(JSON.stringify(SKELETON_DEFAULT_CABINET)) as SubGroupDoc
    doc.description = 'json-mode-edit'
    useConfigStore.getState().setJsonText(JSON.stringify(doc, null, 2))
    const outcome = await useConfigStore.getState().save()
    expect(outcome.saved).toBe(true)
    expect(useConfigStore.getState().doc!.description).toBe('json-mode-edit')
  })

  it('组操作：切换生效组写 settings；删除选中组清空文档', async () => {
    await useConfigStore.getState().loadAll()
    await useConfigStore.getState().selectSubGroup('default', 'cabinet.json')
    await useConfigStore.getState().setActiveGroup('fault-sim')
    expect(bridgeMock.settingsSetActive).toHaveBeenCalledWith('fault-sim')
    expect(useConfigStore.getState().settings?.activeGroup).toBe('fault-sim')

    await useConfigStore.getState().deleteGroup('fault-sim')
    expect(bridgeMock.groupDelete).toHaveBeenCalledWith('fault-sim')
    expect(useConfigStore.getState().selectedGroup).toBe(null)
    expect(useConfigStore.getState().doc).toBe(null)
  })

  it('重命名组同步选中状态', async () => {
    await useConfigStore.getState().loadAll()
    useConfigStore.setState({ selectedGroup: 'default' })
    await useConfigStore.getState().renameGroup('default', 'default2')
    expect(useConfigStore.getState().selectedGroup).toBe('default2')
  })
})
