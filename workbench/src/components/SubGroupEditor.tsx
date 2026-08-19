/**
 * 小分组编辑器（06 §5.3 核心）：
 * 表单 ⇄ Monaco 双模式无损切换；Ctrl+S / 保存按钮统一走"渲染层校验 → IPC 原子写"；
 * 校验失败展示行内错误列表并可定位。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useConfigStore } from '../stores/configStore'
import { useUiStore } from '../stores/uiStore'
import type { ValidationIssue } from '../lib/types'
import { MethodCard } from './MethodCard'
import { MonacoPane, type MonacoApi } from './MonacoPane'

export function SubGroupEditor() {
  const {
    doc,
    jsonText,
    mode,
    dirty,
    saving,
    lastSavedAt,
    errors,
    warnings,
    groupWarnings,
    selectedFile,
    selectedGroup,
    settings,
    mutate,
    setJsonText,
    toggleMode,
    save,
  } = useConfigStore()
  const toast = useUiStore((s) => s.toast)
  const monacoApiRef = useRef<MonacoApi | null>(null)
  const [showIssues, setShowIssues] = useState(true)

  const doSave = useCallback(async () => {
    try {
      const outcome = await save()
      if (outcome.saved) {
        toast('已保存', 'success')
        setShowIssues(false)
      } else if (outcome.errors.length > 0) {
        toast(`保存被阻止：${outcome.errors.length} 个错误`, 'error')
        setShowIssues(true)
      }
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error')
    }
  }, [save, toast])

  // Ctrl+S 统一保存流
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault()
        void doSave()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [doSave])

  if (!doc) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-slate-400">
        在左侧选择一个小分组开始编辑
      </div>
    )
  }

  const nonActive = settings && settings.activeGroup !== selectedGroup

  const locateIssue = (issue: ValidationIssue) => {
    if (mode === 'json' && issue.line) {
      monacoApiRef.current?.reveal(issue.line, issue.column)
      return
    }
    const m = /^mocks\[(\d+)\]/.exec(issue.path)
    if (m) {
      const el = document.getElementById(`mock-${m[1]}`)
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' })
        el.classList.remove('flash-locate')
        // 强制 reflow 后重加 class 触发动画
        void el.offsetWidth
        el.classList.add('flash-locate')
      }
    }
  }

  const switchMode = () => {
    const ok = toggleMode()
    if (!ok) {
      toast('JSON 存在语法或校验错误，无法切回表单模式', 'error')
      setShowIssues(true)
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      {nonActive && (
        <div className="border-b border-amber-200 bg-amber-50 px-4 py-1.5 text-xs text-amber-700">
          正在编辑非生效组（当前生效组：{settings?.activeGroup}），修改保存后不会立即影响运行中的 agent，切换生效组后生效
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3 border-b border-slate-200 bg-white px-4 py-2">
        <div className="flex items-center gap-2">
          <span className="font-mono text-sm font-semibold text-slate-800">{selectedFile}</span>
          <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-500">{selectedGroup}</span>
          {dirty && <span className="h-2 w-2 rounded-full bg-amber-500" title="有未保存修改" />}
          {!dirty && lastSavedAt && <span className="text-[11px] text-emerald-600">已保存</span>}
        </div>

        <div className="flex overflow-hidden rounded border border-slate-300 text-xs">
          <button
            type="button"
            className={`px-3 py-1 ${mode === 'form' ? 'bg-brand-600 text-white' : 'bg-white text-slate-600 hover:bg-slate-50'}`}
            onClick={() => mode !== 'form' && switchMode()}
          >
            表单
          </button>
          <button
            type="button"
            className={`px-3 py-1 ${mode === 'json' ? 'bg-brand-600 text-white' : 'bg-white text-slate-600 hover:bg-slate-50'}`}
            onClick={() => mode !== 'json' && switchMode()}
          >
            JSON 源码
          </button>
        </div>

        <button
          type="button"
          className="ml-auto flex items-center gap-1.5 rounded bg-brand-600 px-4 py-1.5 text-sm text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-50"
          disabled={!dirty || saving}
          onClick={() => void doSave()}
        >
          {saving ? '保存中…' : '保存'}
          <span className="text-[10px] opacity-75">Ctrl+S</span>
        </button>
      </div>

      {(errors.length > 0 || warnings.length > 0 || groupWarnings.length > 0) && (
        <div className="border-b border-slate-200 bg-white px-4 py-2">
          <button
            type="button"
            className="mb-1 text-xs font-medium text-slate-500 hover:text-slate-700"
            onClick={() => setShowIssues(!showIssues)}
          >
            {showIssues ? '▾' : '▸'} 校验信息（{errors.length} 错误 / {warnings.length + groupWarnings.length} 提示）
          </button>
          {showIssues && (
            <div className="max-h-32 space-y-1 overflow-auto">
              {errors.map((issue, i) => (
                <button
                  key={`e${i}`}
                  type="button"
                  className="block w-full rounded bg-red-50 px-2 py-1 text-left text-xs text-red-700 hover:bg-red-100"
                  onClick={() => locateIssue(issue)}
                >
                  <span className="mr-1.5 font-mono opacity-70">{issue.path || '(根)'}</span>
                  {issue.message}
                  {issue.line && <span className="ml-1.5 opacity-60">第 {issue.line} 行</span>}
                </button>
              ))}
              {warnings.map((issue, i) => (
                <div key={`w${i}`} className="rounded bg-amber-50 px-2 py-1 text-xs text-amber-700">
                  <span className="mr-1.5 font-mono opacity-70">{issue.path || '(根)'}</span>
                  {issue.message}
                </div>
              ))}
              {groupWarnings.map((issue, i) => (
                <div key={`g${i}`} className="rounded bg-sky-50 px-2 py-1 text-xs text-sky-700">
                  [组级] {issue.message}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <div className="min-h-0 flex-1 overflow-auto p-4">
        {mode === 'form' ? (
          <div className="mx-auto max-w-4xl space-y-4">
            <div className="grid gap-2 rounded-lg border border-slate-200 bg-white p-3 md:grid-cols-2">
              <div>
                <span className="label">小分组名 *</span>
                <input
                  className="input"
                  value={doc.name}
                  onChange={(e) => mutate((d) => { d.name = e.target.value })}
                />
              </div>
              <div>
                <span className="label">说明</span>
                <input
                  className="input"
                  placeholder="机柜电源相关 Mock"
                  value={doc.description ?? ''}
                  onChange={(e) => mutate((d) => { d.description = e.target.value })}
                />
              </div>
            </div>

            {doc.mocks.map((mock, i) => (
              <MethodCard
                key={i}
                mock={mock}
                index={i}
                onChange={(next) =>
                  mutate((d) => {
                    d.mocks[i] = next
                  })
                }
                onRemove={() =>
                  mutate((d) => {
                    d.mocks.splice(i, 1)
                  })
                }
              />
            ))}

            <button
              type="button"
              className="w-full rounded-lg border border-dashed border-slate-300 py-2 text-sm text-slate-500 hover:border-brand-400 hover:text-brand-600"
              onClick={() =>
                mutate((d) => {
                  d.mocks.push({
                    class: '',
                    method: '',
                    enabled: true,
                    rules: [],
                  })
                })
              }
            >
              + 添加方法
            </button>
          </div>
        ) : (
          <div className="h-full min-h-0 rounded-lg border border-slate-200 bg-white p-1">
            <MonacoPane
              value={jsonText}
              onChange={setJsonText}
              onSave={() => void doSave()}
              onReady={(api) => {
                monacoApiRef.current = api
              }}
            />
          </div>
        )}
      </div>
    </div>
  )
}
