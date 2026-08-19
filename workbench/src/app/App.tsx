/** 应用外壳：顶栏 + 导航 + 三页 + Toast/守卫对话框 + 未设置 home 的空态 */
import { useEffect } from 'react'
import { GuardDialog, NavRail, Toasts } from '../components/NavRail'
import { TopBar } from '../components/TopBar'
import { ConfigPage } from '../pages/ConfigPage'
import { PluginsPage } from '../pages/PluginsPage'
import { StatusPage } from '../pages/StatusPage'
import { useHomeStore } from '../stores/homeStore'
import { useUiStore } from '../stores/uiStore'

export function App() {
  const init = useHomeStore((s) => s.init)
  const homePath = useHomeStore((s) => s.homePath)
  const ready = useHomeStore((s) => s.ready)
  const selectAndSet = useHomeStore((s) => s.selectAndSet)
  const initSkeletonHere = useHomeStore((s) => s.initSkeletonHere)
  const page = useUiStore((s) => s.page)

  useEffect(() => {
    void init()
  }, [init])

  return (
    <div className="flex h-full flex-col">
      <TopBar />
      {ready && !homePath ? (
        <EmptyHome onOpen={selectAndSet} onInit={initSkeletonHere} />
      ) : (
        <div className="flex min-h-0 flex-1">
          <NavRail />
          <main className="min-w-0 flex-1 bg-slate-50">
            {page === 'config' && <ConfigPage />}
            {page === 'plugins' && <PluginsPage />}
            {page === 'status' && <StatusPage />}
          </main>
        </div>
      )}
      <Toasts />
      <GuardDialog />
    </div>
  )
}

function EmptyHome({ onOpen, onInit }: { onOpen: () => void; onInit: () => void }) {
  return (
    <div className="flex flex-1 items-center justify-center bg-slate-50">
      <div className="w-[520px] rounded-xl border border-slate-200 bg-white p-8 text-center shadow-sm">
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-600 text-lg font-bold text-white">EM</div>
        <h1 className="mb-2 text-lg font-semibold text-slate-800">欢迎使用 EquipMock 工作台</h1>
        <p className="mb-6 text-sm text-slate-500">
          工作台通过维护 <code className="rounded bg-slate-100 px-1 font-mono text-xs">equip-mock/</code> 目录下的 json 文件与插件 jar
          工作。首次使用请选择已有主目录，或初始化一个带示例配置的骨架目录。
        </p>
        <div className="flex justify-center gap-3">
          <button
            type="button"
            className="rounded border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50"
            onClick={onOpen}
          >
            选择已有主目录…
          </button>
          <button
            type="button"
            className="rounded bg-brand-600 px-4 py-2 text-sm text-white hover:bg-brand-700"
            onClick={onInit}
          >
            初始化新主目录…
          </button>
        </div>
      </div>
    </div>
  )
}
