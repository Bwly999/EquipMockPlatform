/** 规则卡片与方法卡片（06 §5.3）：规则列表可拖动排序（first-match 优先级）、方法字段编辑、默认动作 */
import { useState } from 'react'
import { describeDescriptor, parseDescriptor } from '../lib/validation/descriptor'
import type { MockAction, MockEntry, Rule } from '../lib/types'
import { ActionEditor } from './ActionEditor'
import { ArgsEditor } from './ArgsEditor'
import { PatternEditor, checkAllPatterns } from './PatternEditor'

// ---------------- 规则 ----------------

export function RuleCard({
  rule,
  index,
  dragHandlers,
  onChange,
  onRemove,
}: {
  rule: Rule
  index: number
  dragHandlers: {
    onDragStart: (e: React.DragEvent) => void
    onDragOver: (e: React.DragEvent) => void
    onDrop: (e: React.DragEvent) => void
    onDragEnd: () => void
    isDragging: boolean
    isOver: boolean
  }
  onChange: (next: Rule) => void
  onRemove: () => void
}) {
  const hasInvalidRegex =
    rule.matchType === 'PATTERN_MATCH' &&
    Array.isArray(rule.argsPattern) &&
    checkAllPatterns(rule.argsPattern).some((r) => !r.valid)

  return (
    <div
      className={`rounded border bg-slate-50/60 p-2 ${hasInvalidRegex ? 'border-red-300' : 'border-slate-200'} ${dragHandlers.isDragging ? 'dragging' : ''} ${dragHandlers.isOver ? 'drag-over-top' : ''}`}
      id={`rule-${index}`}
    >
      <div className="mb-2 flex items-center gap-2">
        <span
          className="cursor-grab select-none rounded bg-white px-1.5 py-0.5 text-xs font-semibold text-slate-400 shadow-sm"
          title="拖动调整优先级（first-match）"
          draggable
          onDragStart={dragHandlers.onDragStart}
          onDragEnd={dragHandlers.onDragEnd}
        >
          ⣿ {index + 1}
        </span>
        <select
          aria-label="匹配方式"
          className="w-40 rounded border border-slate-300 bg-white px-1.5 py-1 text-xs"
          value={rule.matchType}
          onChange={(e) => {
            const matchType = e.target.value as Rule['matchType']
            onChange({ ...rule, matchType, ...(matchType === 'FULL_MATCH' ? { args: rule.args ?? [null] } : { argsPattern: rule.argsPattern ?? [''] }) })
          }}
        >
          <option value="FULL_MATCH">FULL_MATCH 参数全等</option>
          <option value="PATTERN_MATCH">PATTERN_MATCH 正则</option>
        </select>
        <input
          type="text"
          className="min-w-0 flex-1 rounded border border-transparent bg-transparent px-1.5 py-1 text-xs hover:border-slate-300 focus:border-brand-400 focus:bg-white focus:outline-none"
          placeholder="规则说明（可选）"
          value={rule.description ?? ''}
          onChange={(e) => onChange({ ...rule, description: e.target.value })}
        />
        <button
          type="button"
          title="删除规则"
          className="shrink-0 rounded px-1.5 py-1 text-xs text-slate-400 hover:bg-red-50 hover:text-red-600"
          onClick={onRemove}
        >
          ✕
        </button>
      </div>

      <div className="grid gap-2 md:grid-cols-2">
        <div className="rounded border border-slate-200 bg-white p-2">
          <div className="label">匹配条件</div>
          {rule.matchType === 'FULL_MATCH' ? (
            <ArgsEditor args={rule.args ?? []} onChange={(args) => onChange({ ...rule, args })} />
          ) : (
            <PatternEditor patterns={rule.argsPattern ?? []} onChange={(argsPattern) => onChange({ ...rule, argsPattern })} />
          )}
        </div>
        <div className="rounded border border-slate-200 bg-white p-2">
          <div className="label">命中后动作</div>
          <ActionEditor action={rule.action} onChange={(action) => action && onChange({ ...rule, action })} />
        </div>
      </div>
    </div>
  )
}

// ---------------- 方法卡片 ----------------

export function MethodCard({
  mock,
  index,
  onChange,
  onRemove,
}: {
  mock: MockEntry
  index: number
  onChange: (next: MockEntry) => void
  onRemove: () => void
}) {
  const [open, setOpen] = useState(true)
  const [dragIndex, setDragIndex] = useState<number | null>(null)
  const [overIndex, setOverIndex] = useState<number | null>(null)

  const sigCheck = mock.signature ? parseDescriptor(mock.signature) : null

  const moveRule = (from: number, to: number) => {
    const rules = [...mock.rules]
    const [item] = rules.splice(from, 1)
    rules.splice(to, 0, item)
    onChange({ ...mock, rules })
  }

  const pasteSignature = async () => {
    try {
      const text = await navigator.clipboard.readText()
      onChange({ ...mock, signature: text.trim() })
    } catch {
      window.alert('读取剪贴板失败，请手动粘贴')
    }
  }

  return (
    <div id={`mock-${index}`} className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <div className="flex items-center gap-2 border-b border-slate-100 px-3 py-2">
        <button
          type="button"
          className="w-5 text-left text-xs text-slate-400 hover:text-slate-600"
          onClick={() => setOpen(!open)}
          aria-label={open ? '折叠' : '展开'}
        >
          {open ? '▾' : '▸'}
        </button>
        <span className="font-mono text-sm font-semibold text-slate-800">{mock.method || '(未命名方法)'}</span>
        <span className="truncate font-mono text-xs text-slate-400">{mock.class}</span>
        <div className="ml-auto flex items-center gap-2">
          <label className="flex items-center gap-1 text-xs text-slate-500">
            <input
              type="checkbox"
              checked={mock.enabled}
              onChange={(e) => onChange({ ...mock, enabled: e.target.checked })}
            />
            启用
          </label>
          <button
            type="button"
            className="rounded px-1.5 py-1 text-xs text-slate-400 hover:bg-red-50 hover:text-red-600"
            onClick={onRemove}
            title="删除方法"
          >
            删除
          </button>
        </div>
      </div>

      {open && (
        <div className="space-y-3 px-3 py-3">
          <div className="grid gap-2 md:grid-cols-3">
            <div>
              <span className="label">类名 FQCN *</span>
              <input
                type="text"
                className="input font-mono text-xs"
                placeholder="com.equip.demo.PowerDevice"
                value={mock.class}
                onChange={(e) => onChange({ ...mock, class: e.target.value })}
              />
            </div>
            <div>
              <span className="label">方法名 *</span>
              <input
                type="text"
                className="input font-mono text-xs"
                placeholder="readStatus"
                value={mock.method}
                onChange={(e) => onChange({ ...mock, method: e.target.value })}
              />
            </div>
            <div>
              <span className="label">签名 descriptor（可选，区分重载）</span>
              <div className="flex gap-1">
                <input
                  type="text"
                  className={`input font-mono text-xs ${mock.signature && sigCheck && !sigCheck.ok ? 'input-invalid' : ''}`}
                  placeholder="(ILjava/lang/String;)I"
                  value={mock.signature ?? ''}
                  onChange={(e) => onChange({ ...mock, signature: e.target.value })}
                />
                <button
                  type="button"
                  title="从剪贴板粘贴签名"
                  className="shrink-0 rounded border border-slate-300 px-2 text-xs text-slate-500 hover:bg-slate-50"
                  onClick={() => void pasteSignature()}
                >
                  粘贴
                </button>
              </div>
              {mock.signature && sigCheck && !sigCheck.ok && (
                <span className="mt-0.5 block text-xs text-red-600">{sigCheck.error}</span>
              )}
              {mock.signature && sigCheck?.ok && (
                <span className="mt-0.5 block font-mono text-[11px] text-emerald-600">{describeDescriptor(mock.signature)}</span>
              )}
              {!mock.signature && <span className="mt-0.5 block text-[11px] text-amber-600">留空 = 作用于同名全部重载</span>}
            </div>
          </div>

          <div>
            <span className="label">方法说明</span>
            <input
              type="text"
              className="input"
              placeholder="读取通道状态"
              value={mock.description ?? ''}
              onChange={(e) => onChange({ ...mock, description: e.target.value })}
            />
          </div>

          <div>
            <div className="mb-1.5 flex items-center gap-2">
              <span className="text-xs font-semibold text-slate-600">规则（自上而下 first-match，拖动调整优先级）</span>
            </div>
            <div className="space-y-1.5">
              {mock.rules.map((rule, i) => (
                <RuleCard
                  key={i}
                  rule={rule}
                  index={i}
                  dragHandlers={{
                    isDragging: dragIndex === i,
                    isOver: overIndex === i && dragIndex !== i,
                    onDragStart: (e) => {
                      setDragIndex(i)
                      e.dataTransfer.effectAllowed = 'move'
                      e.dataTransfer.setData('text/plain', String(i))
                    },
                    onDragOver: (e) => {
                      e.preventDefault()
                      setOverIndex(i)
                    },
                    onDrop: (e) => {
                      e.preventDefault()
                      const from = dragIndex ?? Number(e.dataTransfer.getData('text/plain'))
                      if (!Number.isNaN(from) && from !== i) moveRule(from, i)
                      setDragIndex(null)
                      setOverIndex(null)
                    },
                    onDragEnd: () => {
                      setDragIndex(null)
                      setOverIndex(null)
                    },
                  }}
                  onChange={(next) => {
                    const rules = [...mock.rules]
                    rules[i] = next
                    onChange({ ...mock, rules })
                  }}
                  onRemove={() => onChange({ ...mock, rules: mock.rules.filter((_, idx) => idx !== i) })}
                />
              ))}
              <button
                type="button"
                className="w-full rounded border border-dashed border-slate-300 py-1.5 text-xs text-slate-500 hover:border-brand-400 hover:text-brand-600"
                onClick={() =>
                  onChange({
                    ...mock,
                    rules: [...mock.rules, { matchType: 'FULL_MATCH', args: [null], action: { type: 'VALUE', value: null } }],
                  })
                }
              >
                + 添加规则
              </button>
            </div>
          </div>

          <div className="rounded border border-slate-200 bg-slate-50 p-2">
            <span className="label">默认动作（规则全不命中时；无 = 放行真实调用）</span>
            <ActionEditor
              allowReal
              action={mock.defaultAction as MockAction | undefined}
              onChange={(next) => onChange({ ...mock, defaultAction: next })}
            />
          </div>
        </div>
      )}
    </div>
  )
}
