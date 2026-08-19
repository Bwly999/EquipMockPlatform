/**
 * PATTERN_MATCH 正则行编辑器（06 §5.3）：
 * 输入时即时 JS RegExp 校验（非法红框、禁保存），每行带"测试匹配"输入框（Java matches() 全匹配语义）。
 */
import { useState } from 'react'
import { checkJsRegex, javaMatches } from '../lib/validation/regex'

export interface PatternRowState {
  pattern: string
  valid: boolean
  error?: string
}

export function checkAllPatterns(patterns: string[]): PatternRowState[] {
  return patterns.map((p) => {
    const check = checkJsRegex(p)
    return { pattern: p, valid: check.ok, error: check.error }
  })
}

export function PatternEditor({
  patterns,
  onChange,
}: {
  patterns: string[]
  onChange: (next: string[]) => void
}) {
  const [tests, setTests] = useState<Record<number, string>>({})

  const update = (i: number, value: string) => {
    const next = [...patterns]
    next[i] = value
    onChange(next)
  }
  const remove = (i: number) => onChange(patterns.filter((_, idx) => idx !== i))
  const add = () => onChange([...patterns, ''])

  return (
    <div className="space-y-1.5">
      <div className="text-xs text-slate-400">
        每参数一个正则，按 Java <code>matches()</code> 全匹配语义比较（参数规范串）
      </div>
      {patterns.map((p, i) => {
        const check = checkJsRegex(p)
        const testInput = tests[i] ?? ''
        const testResult =
          testInput === ''
            ? null
            : check.ok
              ? javaMatches(p, testInput)
                ? 'match'
                : 'miss'
              : null
        return (
          <div key={i} className="flex items-start gap-1.5">
            <span className="mt-2 w-6 shrink-0 text-right font-mono text-xs text-slate-400">{i}</span>
            <div className="min-w-0 flex-1 space-y-1">
              <input
                type="text"
                className={`input font-mono ${p.length > 0 && !check.ok ? 'input-invalid' : ''}`}
                placeholder="如 \d+ 或 CH(9[0-9])"
                value={p}
                onChange={(e) => update(i, e.target.value)}
              />
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  className="flex-1 rounded border border-slate-200 bg-slate-50 px-2 py-1 font-mono text-xs outline-none focus:border-brand-400"
                  placeholder="测试匹配…"
                  value={testInput}
                  onChange={(e) => setTests((t) => ({ ...t, [i]: e.target.value }))}
                />
                {testResult && (
                  <span
                    className={`shrink-0 text-xs font-medium ${testResult === 'match' ? 'text-emerald-600' : 'text-slate-400'}`}
                    role="status"
                  >
                    {testResult === 'match' ? '✓ 全匹配' : '✗ 不匹配'}
                  </span>
                )}
              </div>
              {p.length > 0 && !check.ok && (
                <div className="text-xs text-red-600">{check.error}</div>
              )}
            </div>
            <button
              type="button"
              title="删除该正则"
              className="mt-1 shrink-0 rounded px-1.5 py-1 text-xs text-slate-400 hover:bg-red-50 hover:text-red-600"
              onClick={() => remove(i)}
            >
              ✕
            </button>
          </div>
        )
      })}
      <button
        type="button"
        className="rounded border border-dashed border-slate-300 px-2 py-1 text-xs text-slate-500 hover:border-brand-400 hover:text-brand-600"
        onClick={add}
      >
        + 添加正则
      </button>
      <div className="text-[11px] text-slate-400">
        提示：JS 与 Java 正则的常用子集语义一致，极少数方言（如 possessive 量词）在 JS 中非法，会在这里直接标红。
      </div>
    </div>
  )
}
