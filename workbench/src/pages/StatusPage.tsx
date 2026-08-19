/** 状态页（06 §7）：卡片 / 插件状态表 / lastError 跳转 / 日志尾部自动滚动（可暂停） */
import { useEffect, useRef, useState } from 'react'
import { useStateStore } from '../stores/stateStore'
import { useJumpStore } from '../stores/uiStore'
import { useUiStore } from '../stores/uiStore'

const STATE_BADGE: Record<string, string> = {
  STARTED: 'bg-emerald-100 text-emerald-700',
  RESOLVED: 'bg-sky-100 text-sky-700',
  DISABLED: 'bg-slate-200 text-slate-600',
  MISSING: 'bg-red-100 text-red-700',
  REJECTED: 'bg-amber-100 text-amber-700',
  FAILED: 'bg-red-100 text-red-700',
}

export function StatusPage() {
  const snapshot = useStateStore((s) => s.snapshot)
  const state = snapshot?.state ?? null
  const logTail = snapshot?.logTail ?? []
  const agentAlive = snapshot?.agentAlive ?? false
  const jumpTo = useJumpStore((s) => s.jumpTo)
  const setPage = useUiStore((s) => s.setPage)

  const [autoScroll, setAutoScroll] = useState(true)
  const logRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (autoScroll && logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight
    }
  }, [logTail, autoScroll])

  const jumpToErrorFile = (file: string) => {
    // config/groups/<group>/<file>.json → 相对路径解析
    const parts = file.replace(/\\/g, '/').split('/')
    if (parts.length >= 2) {
      const fileName = parts[parts.length - 1]
      const group = parts[parts.length - 2]
      setPage('config')
      jumpTo(group, fileName)
    }
  }

  return (
    <div className="h-full min-h-0 overflow-auto p-6">
      <div className="mx-auto max-w-5xl space-y-4">
        {!state && (
          <div className="rounded-lg border border-slate-200 bg-white p-5 text-sm text-slate-500">
            <div className="mb-1 font-medium text-slate-700">未检测到 agent 心跳（state.json 未更新）</div>
            当前为文件维护模式：对配置/清单的修改会照常保存，agent 启动后自动读取生效。
          </div>
        )}

        {state && !agentAlive && (
          <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-700">
            state.json 存在但心跳已停止（lastWriteAt 超过 3 秒未更新{snapshot?.pidAlive === false ? '，且 pid 已退出' : ''}）。
            以下为最后一次快照。
          </div>
        )}

        <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
          <Card label="agent 版本" value={state?.agentVersion ?? '—'} />
          <Card label="pid" value={state ? String(state.pid) : '—'} sub={snapshot?.pidAlive ? '进程存活' : '进程不存在'} />
          <Card label="启动时间" value={state?.startedAt ?? '—'} mono />
          <Card label="生效组" value={state?.activeGroup ?? '—'} />
          <Card label="mockEnabled" value={state ? String(state.mockEnabled) : '—'} highlight={state ? state.mockEnabled : undefined} />
          <Card label="instrumentedClasses" value={state ? String(state.instrumentedClasses ?? 0) : '—'} />
        </div>

        {state?.needsRestart && state.needsRestart.length > 0 && (
          <div className="rounded-lg border border-red-200 bg-red-50 p-3">
            <div className="mb-1 text-xs font-semibold text-red-700">needsRestart（需重启宿主才生效的类）</div>
            <ul className="list-disc pl-5 font-mono text-xs text-red-600">
              {state.needsRestart.map((cls) => (
                <li key={cls}>{cls}</li>
              ))}
            </ul>
          </div>
        )}

        {state?.lastError && (
          <button
            type="button"
            className="block w-full rounded-lg border border-red-200 bg-red-50 p-3 text-left hover:bg-red-100"
            onClick={() => jumpToErrorFile(state.lastError!.file)}
          >
            <div className="mb-1 text-xs font-semibold text-red-700">lastError（点击跳转到对应小分组）</div>
            <div className="font-mono text-xs text-red-600">{state.lastError.file}</div>
            <div className="mt-0.5 text-xs text-red-700">{state.lastError.message}</div>
            <div className="mt-0.5 text-[11px] text-red-400">{state.lastError.time}</div>
          </button>
        )}

        {state?.plugins && state.plugins.length > 0 && (
          <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
            <div className="border-b border-slate-100 px-3 py-2 text-xs font-semibold text-slate-500">插件状态（来自 state.json）</div>
            <table className="w-full text-sm">
              <tbody>
                {state.plugins.map((p) => (
                  <tr key={p.id} className="border-b border-slate-50 last:border-0">
                    <td className="px-3 py-1.5 font-mono text-xs">{p.id}</td>
                    <td className="px-3 py-1.5 text-xs text-slate-500">{p.version}</td>
                    <td className="px-3 py-1.5">
                      <span className={`rounded px-1.5 py-0.5 text-[11px] ${STATE_BADGE[p.state] ?? 'bg-slate-100 text-slate-500'}`}>
                        {p.state}
                      </span>
                    </td>
                    <td className="px-3 py-1.5 text-xs text-slate-400">{p.mockPoints ?? '—'} mock 点</td>
                    <td className="px-3 py-1.5 text-xs text-red-600">{p.error ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <div className="flex items-center gap-2 border-b border-slate-100 px-3 py-2">
            <span className="text-xs font-semibold text-slate-500">logs/agent.log 尾部 {logTail.length} 行</span>
            <label className="ml-auto flex items-center gap-1.5 text-xs text-slate-500">
              <input type="checkbox" checked={autoScroll} onChange={(e) => setAutoScroll(e.target.checked)} />
              自动滚动
            </label>
          </div>
          <div ref={logRef} className="h-80 overflow-auto bg-slate-900 p-3 font-mono text-[11px] leading-5 text-slate-300">
            {logTail.length === 0 && <div className="text-slate-500">（暂无日志）</div>}
            {logTail.map((line, i) => (
              <div key={i} className="whitespace-pre-wrap break-all">
                {line}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}

function Card({
  label,
  value,
  sub,
  mono,
  highlight,
}: {
  label: string
  value: string
  sub?: string
  mono?: boolean
  highlight?: boolean
}) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3">
      <div className="text-[11px] text-slate-400">{label}</div>
      <div className={`mt-1 truncate text-sm font-medium ${mono ? 'font-mono' : ''} ${highlight === false ? 'text-red-600' : 'text-slate-800'}`}>
        {value}
      </div>
      {sub && <div className="mt-0.5 text-[11px] text-slate-400">{sub}</div>}
    </div>
  )
}
