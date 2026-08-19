/** action 编辑器（06 §5.3）：VALUE（类型感知输入，含 $hex 辅助）/ THROW（FQCN+message）/ VOID；默认动作额外支持 REAL */
import type { MockAction } from '../lib/types'
import { ValueCell } from './ValueCell'

const FQCN_RE = /^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$/

export function ActionEditor({
  action,
  onChange,
  allowReal = false,
}: {
  action: MockAction | undefined
  onChange: (next: MockAction | undefined) => void
  /** defaultAction 专用：无动作 = REAL 放行真实调用 */
  allowReal?: boolean
}) {
  const kind = action === undefined ? 'REAL' : action.type

  const setKind = (next: string) => {
    switch (next) {
      case 'REAL':
        onChange(undefined)
        break
      case 'VALUE':
        onChange({ type: 'VALUE', value: null })
        break
      case 'THROW':
        onChange({ type: 'THROW', exception: '', message: '' })
        break
      case 'VOID':
        onChange({ type: 'VOID' })
        break
    }
  }

  const fqcnInvalid = action?.type === 'THROW' && action.exception.length > 0 && !FQCN_RE.test(action.exception)

  return (
    <div className="space-y-1.5">
      <select
        aria-label="动作类型"
        className="input w-44"
        value={kind}
        onChange={(e) => setKind(e.target.value)}
      >
        {allowReal && <option value="REAL">REAL（放行真实调用）</option>}
        <option value="VALUE">VALUE（返回固定值）</option>
        <option value="THROW">THROW（抛异常）</option>
        <option value="VOID">VOID（跳过真实调用）</option>
      </select>

      {action?.type === 'VALUE' && (
        <ValueCell value={action.value} onChange={(value) => onChange({ type: 'VALUE', value })} />
      )}

      {action?.type === 'THROW' && (
        <div className="space-y-1.5">
          <div>
            <span className="label">异常类 FQCN（需有无参或 String 构造）</span>
            <input
              type="text"
              className={`input font-mono ${fqcnInvalid ? 'input-invalid' : ''}`}
              placeholder="java.io.IOException"
              value={action.exception}
              onChange={(e) => onChange({ ...action, exception: e.target.value })}
            />
            {fqcnInvalid && <span className="mt-0.5 block text-xs text-red-600">不是合法 FQCN</span>}
          </div>
          <div>
            <span className="label">message（可选）</span>
            <input
              type="text"
              className="input"
              placeholder="device timeout"
              value={action.message ?? ''}
              onChange={(e) => onChange({ ...action, message: e.target.value })}
            />
          </div>
        </div>
      )}

      {action?.type === 'VOID' && <div className="text-xs text-slate-400">跳过真实调用（仅 void 方法生效）</div>}
    </div>
  )
}
