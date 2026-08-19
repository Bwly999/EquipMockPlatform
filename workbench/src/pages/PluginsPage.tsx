/** 插件页（06 §6）：清单联合视图表格 + 导入全流程（MANIFEST 校验/覆盖确认/警告提示） */
import { useEffect, useState } from 'react'
import { ConfirmDialog } from '../components/Dialogs'
import { usePluginStore } from '../stores/pluginStore'
import type { PluginRuntimeState } from '../lib/types'

const STATE_BADGE: Record<PluginRuntimeState | '未运行', { label: string; cls: string }> = {
  STARTED: { label: 'STARTED', cls: 'bg-emerald-100 text-emerald-700' },
  RESOLVED: { label: 'RESOLVED', cls: 'bg-sky-100 text-sky-700' },
  DISABLED: { label: 'DISABLED', cls: 'bg-slate-200 text-slate-600' },
  MISSING: { label: 'MISSING', cls: 'bg-red-100 text-red-700' },
  REJECTED: { label: 'REJECTED', cls: 'bg-amber-100 text-amber-700' },
  FAILED: { label: 'FAILED', cls: 'bg-red-100 text-red-700' },
  未运行: { label: '未运行', cls: 'bg-slate-100 text-slate-400' },
}

function formatBytes(n: number): string {
  if (n <= 0) return '-'
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(2)} MB`
}

export function PluginsPage() {
  const {
    rows,
    orphanJars,
    loading,
    importing,
    pendingOverwrite,
    reload,
    importPlugin,
    confirmOverwrite,
    cancelOverwrite,
    enable,
    setAlias,
    remove,
  } = usePluginStore()
  const [removing, setRemoving] = useState<{ id: string; deleteJar: boolean } | null>(null)

  useEffect(() => {
    void reload()
  }, [reload])

  return (
    <div className="h-full min-h-0 overflow-auto p-6">
      <div className="mx-auto max-w-5xl">
        <div className="mb-4 flex items-center gap-3">
          <h2 className="text-base font-semibold text-slate-800">插件管理</h2>
          <span className="text-xs text-slate-400">清单 plugin-registry.json 是唯一事实源；未登记的 jar 不会被 agent 加载</span>
          <button
            type="button"
            className="ml-auto rounded bg-brand-600 px-4 py-1.5 text-sm text-white hover:bg-brand-700 disabled:opacity-50"
            disabled={importing}
            onClick={() => void importPlugin()}
          >
            {importing ? '导入中…' : '导入插件 jar…'}
          </button>
        </div>

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs text-slate-500">
                <th className="px-3 py-2 font-medium">启用</th>
                <th className="px-3 py-2 font-medium">ID</th>
                <th className="px-3 py-2 font-medium">别名</th>
                <th className="px-3 py-2 font-medium">版本</th>
                <th className="px-3 py-2 font-medium">状态</th>
                <th className="px-3 py-2 font-medium">jar 文件</th>
                <th className="px-3 py-2 font-medium">大小</th>
                <th className="px-3 py-2 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 && !loading && (
                <tr>
                  <td colSpan={8} className="px-3 py-8 text-center text-sm text-slate-400">
                    暂无插件，点击右上角导入
                  </td>
                </tr>
              )}
              {rows.map((row) => {
                const badge = STATE_BADGE[row.runtimeState ?? '未运行']
                return (
                  <tr key={row.id} className="border-b border-slate-100 last:border-0 hover:bg-slate-50/60">
                    <td className="px-3 py-2">
                      <input
                        type="checkbox"
                        checked={row.enabled}
                        onChange={(e) => void enable(row.id, e.target.checked)}
                        title="即时写入清单（enabled）"
                      />
                    </td>
                    <td className="px-3 py-2 font-mono text-xs">{row.id}</td>
                    <td className="px-3 py-2">
                      <AliasCell alias={row.alias} onCommit={(alias) => void setAlias(row.id, alias)} />
                    </td>
                    <td className="px-3 py-2 text-xs text-slate-500">{row.runtimeVersion ?? '—'}</td>
                    <td className="px-3 py-2">
                      <span className={`inline-block rounded px-1.5 py-0.5 text-[11px] font-medium ${badge.cls}`} title={row.runtimeError ?? undefined}>
                        {badge.label}
                      </span>
                    </td>
                    <td className="px-3 py-2 font-mono text-xs">
                      {row.jar}
                      {!row.jarExists && <span className="ml-1 text-red-600">(文件缺失)</span>}
                    </td>
                    <td className="px-3 py-2 text-xs text-slate-400">{formatBytes(row.jarSizeBytes)}</td>
                    <td className="px-3 py-2">
                      <button
                        type="button"
                        className="rounded border border-slate-200 px-2 py-0.5 text-xs text-slate-500 hover:border-red-300 hover:text-red-600"
                        onClick={() => setRemoving({ id: row.id, deleteJar: false })}
                      >
                        移除
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>

        {orphanJars.length > 0 && (
          <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800">
            <div className="mb-1 font-medium">未登记的 jar（agent 不会加载，仅提示）</div>
            <ul className="list-disc pl-4 font-mono">
              {orphanJars.map((o) => (
                <li key={o.jar}>
                  {o.jar}（{formatBytes(o.sizeBytes)}）
                </li>
              ))}
            </ul>
          </div>
        )}

        {pendingOverwrite && (
          <ConfirmDialog
            title="插件 ID 已存在"
            message={
              <>
                jar 的 <code className="font-mono">Plugin-Id={pendingOverwrite.manifest.pluginId}</code> 已登记
                （当前 jar：{pendingOverwrite.existing.jar}）。覆盖导入将替换 jar 文件、清除 note 并置为启用。
              </>
            }
            confirmText="覆盖导入"
            danger
            onCancel={cancelOverwrite}
            onConfirm={() => void confirmOverwrite()}
          />
        )}

        {removing && (
          <ConfirmDialog
            title={`移除插件 ${removing.id}`}
            message="将从清单中移除该插件条目（agent 会卸载其路由）。"
            confirmText="移除"
            danger
            extraCheckbox={{
              label: '同时删除 jar 文件（不可恢复）',
              checked: removing.deleteJar,
              onChange: (v) => setRemoving({ ...removing, deleteJar: v }),
            }}
            onCancel={() => setRemoving(null)}
            onConfirm={() => {
              const target = removing
              setRemoving(null)
              void remove(target.id, target.deleteJar)
            }}
          />
        )}
      </div>
    </div>
  )
}

function AliasCell({ alias, onCommit }: { alias: string; onCommit: (alias: string) => void }) {
  return (
    <input
      type="text"
      className="w-36 rounded border border-transparent bg-transparent px-1.5 py-1 text-sm hover:border-slate-300 focus:border-brand-400 focus:bg-white focus:outline-none"
      placeholder="（别名）"
      defaultValue={alias}
      key={alias}
      onBlur={(e) => {
        if (e.target.value !== alias) onCommit(e.target.value.trim())
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
      }}
    />
  )
}
