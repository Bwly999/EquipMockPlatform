/** 顶栏（06 §4）：home 路径切换器 / 全局 Mock 开关 / agent 活跃状态灯 */
import { useEffect, useRef, useState } from 'react'
import { useConfigStore } from '../stores/configStore'
import { useHomeStore } from '../stores/homeStore'
import { useStateStore } from '../stores/stateStore'
import { useUiStore } from '../stores/uiStore'

export function TopBar() {
  const homePath = useHomeStore((s) => s.homePath)
  const busy = useHomeStore((s) => s.busy)
  const selectAndSet = useHomeStore((s) => s.selectAndSet)
  const initSkeletonHere = useHomeStore((s) => s.initSkeletonHere)
  const mockEnabled = useConfigStore((s) => s.settings?.mockEnabled ?? true)
  const setMockEnabled = useConfigStore((s) => s.setMockEnabled)
  const agentAlive = useStateStore((s) => s.snapshot?.agentAlive ?? false)
  const toast = useUiStore((s) => s.toast)
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!menuOpen) return
    const onDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false)
    }
    window.addEventListener('mousedown', onDown)
    return () => window.removeEventListener('mousedown', onDown)
  }, [menuOpen])

  return (
    <header className="flex h-12 shrink-0 items-center gap-3 border-b border-slate-800 bg-slate-900 px-4 text-slate-200">
      <div className="flex items-center gap-2">
        <span className="flex h-6 w-6 items-center justify-center rounded bg-brand-600 text-xs font-bold text-white">EM</span>
        <span className="text-sm font-semibold tracking-wide">EquipMock 工作台</span>
      </div>

      <div className="relative" ref={menuRef}>
        <button
          type="button"
          className="flex items-center gap-1.5 rounded bg-slate-800 px-2.5 py-1.5 text-xs text-slate-300 hover:bg-slate-700 disabled:opacity-50"
          disabled={busy}
          onClick={() => setMenuOpen(!menuOpen)}
          title="切换 / 初始化主目录"
        >
          <span className="opacity-60">主目录</span>
          <span className="max-w-72 truncate font-mono">{homePath ?? '未设置'}</span>
          <span className="opacity-60">▾</span>
        </button>
        {menuOpen && (
          <div className="absolute left-0 top-full z-40 mt-1 w-80 rounded border border-slate-200 bg-white py-1 text-sm text-slate-700 shadow-xl">
            <button
              type="button"
              className="block w-full px-3 py-2 text-left hover:bg-slate-50"
              onClick={() => {
                setMenuOpen(false)
                void selectAndSet()
              }}
            >
              切换主目录…
              <span className="block text-xs text-slate-400">选择一个已存在的 equip-mock 目录（含 settings.json）</span>
            </button>
            <button
              type="button"
              className="block w-full px-3 py-2 text-left hover:bg-slate-50"
              onClick={() => {
                setMenuOpen(false)
                void initSkeletonHere()
              }}
            >
              初始化新主目录…
              <span className="block text-xs text-slate-400">生成骨架 + 示例 default / fault-sim 配置组</span>
            </button>
          </div>
        )}
      </div>

      <div className="ml-auto flex items-center gap-4">
        <label className="flex cursor-pointer items-center gap-2 text-xs" title="settings.json 的 mockEnabled：false = 所有拦截点放行真实调用">
          <span className={mockEnabled ? 'text-emerald-300' : 'text-slate-400'}>全局 Mock</span>
          <button
            type="button"
            role="switch"
            aria-checked={mockEnabled}
            disabled={!homePath}
            className={`relative h-5 w-10 rounded-full transition-colors ${mockEnabled ? 'bg-emerald-500' : 'bg-slate-600'} disabled:opacity-40`}
            onClick={() => {
              void setMockEnabled(!mockEnabled)
                .then(() => toast(mockEnabled ? '全局 Mock 已关闭：所有调用放行真实' : '全局 Mock 已开启', 'success'))
                .catch((e: unknown) => toast(e instanceof Error ? e.message : String(e), 'error'))
            }}
          >
            <span
              className={`absolute top-0.5 h-4 w-4 rounded-full bg-white transition-all ${mockEnabled ? 'left-5' : 'left-0.5'}`}
            />
          </button>
        </label>

        <div className="flex items-center gap-1.5 text-xs" title="state.json 心跳 3s 内更新且 pid 存活 → 运行中">
          <span className={`h-2.5 w-2.5 rounded-full ${agentAlive ? 'bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.8)]' : 'bg-slate-500'}`} />
          <span className={agentAlive ? 'text-emerald-300' : 'text-slate-400'}>{agentAlive ? 'agent 运行中' : 'agent 未运行'}</span>
        </div>
      </div>
    </header>
  )
}
