/** 配置页（06 §5）：左组树 + 右单小分组编辑 */
import { useEffect } from 'react'
import { GroupPanel } from '../components/GroupPanel'
import { SubGroupEditor } from '../components/SubGroupEditor'
import { useConfigStore } from '../stores/configStore'
import { useJumpStore } from '../stores/uiStore'
import { useUiStore } from '../stores/uiStore'

export function ConfigPage() {
  const loadAll = useConfigStore((s) => s.loadAll)
  const loadError = useConfigStore((s) => s.loadError)
  const jumpTarget = useJumpStore((s) => s.jumpTarget)
  const clearJump = useJumpStore((s) => s.clearJump)
  const setPage = useUiStore((s) => s.setPage)

  useEffect(() => {
    if (!jumpTarget) return
    clearJump()
    setPage('config')
    void useConfigStore.getState().jumpTo(jumpTarget.group, jumpTarget.file)
  }, [jumpTarget, clearJump, setPage])

  useEffect(() => {
    void loadAll()
  }, [loadAll])

  return (
    <div className="flex h-full min-h-0">
      <GroupPanel />
      <div className="flex min-w-0 flex-1 flex-col">
        {loadError && (
          <div className="border-b border-red-200 bg-red-50 px-4 py-1.5 text-xs text-red-700">读取配置失败：{loadError}</div>
        )}
        <div className="min-h-0 flex-1">
          <SubGroupEditor />
        </div>
      </div>
    </div>
  )
}
