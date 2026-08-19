/** 左侧导航（06 §4 三页）+ 全局未保存守卫 + Toast 容器 */
import { useGuardStore, useUiStore, type Page } from '../stores/uiStore'
import { Modal } from './Dialogs'

const NAV: { page: Page; icon: string; label: string; hint: string }[] = [
  { page: 'config', icon: '⚙', label: '配置中心', hint: '配置组与小分组编辑' },
  { page: 'plugins', icon: '🧩', label: '插件', hint: '导入 / 启停 / 移除' },
  { page: 'status', icon: '◉', label: '状态', hint: 'agent 状态与日志' },
]

export function NavRail() {
  const page = useUiStore((s) => s.page)
  const ask = useGuardStore((s) => s.ask)

  return (
    <nav className="flex w-16 shrink-0 flex-col items-center gap-1 border-r border-slate-200 bg-slate-100 py-3">
      {NAV.map((item) => (
        <button
          key={item.page}
          type="button"
          title={item.hint}
          className={`flex w-14 flex-col items-center gap-0.5 rounded-lg px-1 py-2 text-center ${
            page === item.page ? 'bg-brand-600 text-white shadow' : 'text-slate-500 hover:bg-slate-200'
          }`}
          onClick={() => {
            if (page === item.page) return
            ask('切换页面', '当前小分组有未保存的修改', () => useUiStore.getState().setPage(item.page))
          }}
        >
          <span className="text-base leading-none">{item.icon}</span>
          <span className="text-[11px]">{item.label}</span>
        </button>
      ))}
    </nav>
  )
}

export function Toasts() {
  const toasts = useUiStore((s) => s.toasts)
  const dismiss = useUiStore((s) => s.dismiss)
  return (
    <div className="pointer-events-none fixed bottom-5 left-1/2 z-[60] flex -translate-x-1/2 flex-col items-center gap-2">
      {toasts.map((t) => (
        <button
          key={t.id}
          type="button"
          className={`pointer-events-auto max-w-[520px] rounded-lg px-4 py-2 text-sm text-white shadow-lg ${
            t.type === 'success' ? 'bg-emerald-600' : t.type === 'error' ? 'bg-red-600' : 'bg-slate-800'
          }`}
          onClick={() => dismiss(t.id)}
        >
          {t.message}
        </button>
      ))}
    </div>
  )
}

export function GuardDialog() {
  const pending = useGuardStore((s) => s.pending)
  const resolveDiscard = useGuardStore((s) => s.resolveDiscard)
  const resolveSave = useGuardStore((s) => s.resolveSave)
  const resolveCancel = useGuardStore((s) => s.resolveCancel)
  if (!pending) return null
  return (
    <Modal
      title={pending.title}
      onClose={resolveCancel}
      footer={
        <>
          <button type="button" className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-50" onClick={resolveCancel}>
            取消（留在当前）
          </button>
          <button type="button" className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-50" onClick={() => void resolveDiscard()}>
            丢弃修改并继续
          </button>
          <button type="button" className="rounded bg-brand-600 px-3 py-1.5 text-sm text-white hover:bg-brand-700" onClick={() => void resolveSave()}>
            保存后继续
          </button>
        </>
      }
    >
      {pending.description}。保存后继续会先执行保存（校验失败则留在原地）。
    </Modal>
  )
}
