/** 左侧面板：配置组列表（生效组标记/切换/新建/复制/重命名/删除）+ 小分组列表（06 §5.1/5.2） */
import { useState } from 'react'
import { useConfigStore } from '../stores/configStore'
import { useGuardStore } from '../stores/uiStore'
import { useUiStore } from '../stores/uiStore'
import { ConfirmDialog, PromptDialog } from './Dialogs'

const NAME_RE = /^[A-Za-z0-9_-]{1,64}$/
const validateName = (v: string) => (NAME_RE.test(v) ? null : '仅字母、数字、下划线、中划线，长度 1-64')

type Dialog =
  | { kind: 'create-group' }
  | { kind: 'copy-group'; from: string }
  | { kind: 'rename-group'; from: string }
  | { kind: 'delete-group'; name: string }
  | { kind: 'create-subgroup'; group: string }
  | null

export function GroupPanel() {
  const {
    groups,
    settings,
    selectedGroup,
    selectedFile,
    selectGroup,
    selectSubGroup,
    createGroup,
    copyGroup,
    deleteGroup,
    renameGroup,
    setActiveGroup,
    createSubGroup,
    loadAll,
  } = useConfigStore()
  const ask = useGuardStore((s) => s.ask)
  const toast = useUiStore((s) => s.toast)
  const [dialog, setDialog] = useState<Dialog>(null)
  const [busy, setBusy] = useState(false)

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    try {
      await fn()
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  const guardSelectSub = (group: string, file: string) => {
    ask('切换小分组', '当前小分组有未保存的修改', () => void selectSubGroup(group, file))
  }

  const group = groups.find((g) => g.name === selectedGroup)

  return (
    <div className="flex h-full min-h-0 w-64 shrink-0 flex-col border-r border-slate-200 bg-white">
      <div className="flex items-center justify-between border-b border-slate-100 px-3 py-2">
        <span className="text-xs font-semibold tracking-wide text-slate-500">配置组</span>
        <div className="flex gap-1">
          <button
            type="button"
            title="新建配置组"
            className="rounded px-1.5 py-0.5 text-xs text-slate-500 hover:bg-brand-50 hover:text-brand-700"
            onClick={() => setDialog({ kind: 'create-group' })}
          >
            + 新建
          </button>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-auto px-2 py-2">
        {groups.length === 0 && <div className="px-2 py-4 text-xs text-slate-400">暂无配置组</div>}
        {groups.map((g) => {
          const active = settings?.activeGroup === g.name
          const selected = selectedGroup === g.name
          return (
            <div
              key={g.name}
              className={`group mb-0.5 rounded px-2 py-1.5 ${selected ? 'bg-brand-50' : 'hover:bg-slate-50'}`}
            >
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  className="min-w-0 flex-1 truncate text-left text-sm"
                  onClick={() => selectGroup(g.name)}
                  title={g.name}
                >
                  <span className={active ? 'text-brand-700' : 'text-slate-700'}>
                    {active && <span title="当前生效组">● </span>}
                    {g.name}
                  </span>
                  <span className="ml-1.5 text-[11px] text-slate-400">{g.subGroups.length} 个小分组</span>
                </button>
              </div>
              <div className="mt-1 flex flex-wrap gap-1 text-[11px]">
                {!active && (
                  <button
                    type="button"
                    className="rounded border border-slate-200 px-1.5 py-0.5 text-slate-500 hover:border-brand-300 hover:text-brand-700"
                    onClick={() => void run(() => setActiveGroup(g.name))}
                  >
                    切为生效组
                  </button>
                )}
                <button
                  type="button"
                  className="rounded border border-slate-200 px-1.5 py-0.5 text-slate-500 hover:border-brand-300 hover:text-brand-700"
                  onClick={() => setDialog({ kind: 'copy-group', from: g.name })}
                >
                  复制
                </button>
                <button
                  type="button"
                  className="rounded border border-slate-200 px-1.5 py-0.5 text-slate-500 hover:border-brand-300 hover:text-brand-700"
                  onClick={() => setDialog({ kind: 'rename-group', from: g.name })}
                >
                  重命名
                </button>
                <button
                  type="button"
                  disabled={settings?.activeGroup === g.name}
                  title={settings?.activeGroup === g.name ? '生效组禁止删除，请先切换' : '删除配置组'}
                  className="rounded border border-slate-200 px-1.5 py-0.5 text-slate-500 hover:border-red-300 hover:text-red-600 disabled:cursor-not-allowed disabled:opacity-40"
                  onClick={() => setDialog({ kind: 'delete-group', name: g.name })}
                >
                  删除
                </button>
              </div>
            </div>
          )
        })}
      </div>

      <div className="border-t border-slate-100">
        <div className="flex items-center justify-between px-3 py-2">
          <span className="text-xs font-semibold tracking-wide text-slate-500">小分组</span>
          <button
            type="button"
            className="rounded px-1.5 py-0.5 text-xs text-slate-500 hover:bg-brand-50 hover:text-brand-700 disabled:opacity-40"
            disabled={!selectedGroup}
            onClick={() => selectedGroup && setDialog({ kind: 'create-subgroup', group: selectedGroup })}
          >
            + 新建
          </button>
        </div>
        <div className="min-h-0 max-h-72 overflow-auto px-2 pb-2">
          {!selectedGroup && <div className="px-2 py-3 text-xs text-slate-400">先选择一个配置组</div>}
          {group?.subGroups.map((s) => (
            <button
              key={s.file}
              type="button"
              className={`mb-0.5 flex w-full items-center justify-between rounded px-2 py-1.5 text-left text-sm ${
                selectedFile === s.file ? 'bg-brand-100 text-brand-800' : 'text-slate-600 hover:bg-slate-50'
              }`}
              onClick={() => guardSelectSub(selectedGroup!, s.file)}
            >
              <span className="truncate">{s.name}</span>
              <span className={`ml-2 shrink-0 text-[11px] ${s.mockCount < 0 ? 'text-red-500' : 'text-slate-400'}`}>
                {s.mockCount < 0 ? '解析失败' : `${s.mockCount} 项`}
              </span>
            </button>
          ))}
        </div>
      </div>

      {dialog?.kind === 'create-group' && (
        <PromptDialog
          title="新建配置组"
          label="组名（即目录名）"
          placeholder="fault-sim"
          validate={validateName}
          onCancel={() => setDialog(null)}
          onSubmit={(name) => {
            setDialog(null)
            void run(() => createGroup(name))
          }}
        />
      )}

      {dialog?.kind === 'copy-group' && (
        <PromptDialog
          title={`复制配置组 ${dialog.from}`}
          label="新组名"
          initialValue={`${dialog.from}-copy`}
          validate={validateName}
          onCancel={() => setDialog(null)}
          onSubmit={(name) => {
            setDialog(null)
            void run(() => copyGroup(dialog.from, name))
          }}
        />
      )}

      {dialog?.kind === 'rename-group' && (
        <PromptDialog
          title={`重命名配置组 ${dialog.from}`}
          label="新组名（settings.json 引用将同步更新）"
          initialValue={dialog.from}
          validate={validateName}
          onCancel={() => setDialog(null)}
          onSubmit={(name) => {
            setDialog(null)
            void run(() => renameGroup(dialog.from, name))
          }}
        />
      )}

      {dialog?.kind === 'delete-group' && (
        <ConfirmDialog
          title={`删除配置组 ${dialog.name}`}
          message={
            <>
              将删除目录 <code className="font-mono">config/groups/{dialog.name}</code> 及其全部小分组文件，不可恢复。
            </>
          }
          confirmText="删除"
          danger
          onCancel={() => setDialog(null)}
          onConfirm={() => {
            setDialog(null)
            void run(() => deleteGroup(dialog.name))
          }}
        />
      )}

      {dialog?.kind === 'create-subgroup' && (
        <PromptDialog
          title={`在 ${dialog.group} 中新建小分组`}
          label="小分组名（即文件名）"
          placeholder="radar"
          validate={validateName}
          onCancel={() => setDialog(null)}
          onSubmit={(name) => {
            setDialog(null)
            void run(async () => {
              await createSubGroup(dialog.group, name)
              await loadAll()
            })
          }}
        />
      )}

      {busy && <div className="border-t border-slate-100 px-3 py-1 text-[11px] text-slate-400">处理中…</div>}
    </div>
  )
}
