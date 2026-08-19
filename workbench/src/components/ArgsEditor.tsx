/** FULL_MATCH 参数行编辑器（06 §5.3）：每行一个参数值，行数= args 数量，可增删 */
import { ValueCell } from './ValueCell'

export function ArgsEditor({
  args,
  onChange,
}: {
  args: unknown[]
  onChange: (next: unknown[]) => void
}) {
  const update = (index: number, value: unknown) => {
    const next = [...args]
    next[index] = value
    onChange(next)
  }
  const remove = (index: number) => onChange(args.filter((_, i) => i !== index))
  const add = () => onChange([...args, null])

  return (
    <div className="space-y-1.5">
      <div className="text-xs text-slate-400">与实参逐位深度相等比较；null 表示传 null</div>
      {args.map((arg, i) => (
        <div key={i} className="flex items-start gap-1.5">
          <span className="mt-2 w-6 shrink-0 text-right font-mono text-xs text-slate-400">{i}</span>
          <div className="min-w-0 flex-1">
            <ValueCell compact value={arg} onChange={(v) => update(i, v)} />
          </div>
          <button
            type="button"
            title="删除该参数"
            className="mt-1 shrink-0 rounded px-1.5 py-1 text-xs text-slate-400 hover:bg-red-50 hover:text-red-600"
            onClick={() => remove(i)}
          >
            ✕
          </button>
        </div>
      ))}
      <button
        type="button"
        className="rounded border border-dashed border-slate-300 px-2 py-1 text-xs text-slate-500 hover:border-brand-400 hover:text-brand-600"
        onClick={add}
      >
        + 添加参数
      </button>
    </div>
  )
}
