/**
 * 类型感知的 JSON 值编辑器（06 §5.3）：
 * 依已有值类型推断控件（数字/字符串/布尔/null），支持 {"$hex"}/{"$b64"} 类型标签对象与任意 JSON。
 */
import { useMemo, useState } from 'react'

export type ValueKind = 'string' | 'number' | 'boolean' | 'null' | 'hex' | 'b64' | 'json'

const HEX_RE = /^([0-9A-Fa-f]{2})*$/
const B64_RE = /^[A-Za-z0-9+/]*={0,2}$/

export function inferKind(value: unknown): ValueKind {
  if (value === null || value === undefined) return 'null'
  if (typeof value === 'number') return 'number'
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'string') return 'string'
  if (Array.isArray(value)) return 'json'
  if (typeof value === 'object') {
    const keys = Object.keys(value)
    if (keys.length === 1 && keys[0] === '$hex') return 'hex'
    if (keys.length === 1 && keys[0] === '$b64') return 'b64'
    return 'json'
  }
  return 'json'
}

const KIND_LABELS: Record<ValueKind, string> = {
  string: '字符串',
  number: '数字',
  boolean: '布尔',
  null: 'null',
  hex: '$hex 字节',
  b64: '$b64 字节',
  json: 'JSON',
}

export function defaultValueFor(kind: ValueKind): unknown {
  switch (kind) {
    case 'string':
      return ''
    case 'number':
      return 0
    case 'boolean':
      return true
    case 'null':
      return null
    case 'hex':
      return { $hex: '00' }
    case 'b64':
      return { $b64: '' }
    case 'json':
      return {}
  }
}

export function ValueCell({
  value,
  onChange,
  compact = false,
}: {
  value: unknown
  onChange: (next: unknown) => void
  compact?: boolean
}) {
  const kind = inferKind(value)
  const [jsonDraft, setJsonDraft] = useState<string | null>(null)

  const jsonText = useMemo(() => (jsonDraft !== null ? jsonDraft : JSON.stringify(value, null, 2)), [jsonDraft, value])

  const switchKind = (next: ValueKind) => {
    setJsonDraft(null)
    if (next === kind) return
    onChange(defaultValueFor(next))
  }

  return (
    <div className="flex w-full items-start gap-1.5">
      <select
        aria-label="值类型"
        className={`shrink-0 rounded border border-slate-300 bg-white text-xs text-slate-600 ${compact ? 'px-1 py-1' : 'px-1.5 py-1.5'}`}
        value={kind}
        onChange={(e) => switchKind(e.target.value as ValueKind)}
      >
        {(Object.keys(KIND_LABELS) as ValueKind[]).map((k) => (
          <option key={k} value={k}>
            {KIND_LABELS[k]}
          </option>
        ))}
      </select>

      <div className="min-w-0 flex-1">
        {kind === 'string' && (
          <input
            type="text"
            className={`input font-mono ${compact ? 'py-1' : ''}`}
            value={String(value ?? '')}
            onChange={(e) => onChange(e.target.value)}
          />
        )}

        {kind === 'number' && (
          <input
            type="number"
            step="any"
            className={`input font-mono ${compact ? 'py-1' : ''}`}
            value={Number(value ?? 0)}
            onChange={(e) => {
              const n = Number(e.target.value)
              onChange(e.target.value === '' ? null : Number.isNaN(n) ? null : n)
            }}
          />
        )}

        {kind === 'boolean' && (
          <select
            className={`input ${compact ? 'py-1' : ''}`}
            value={value ? 'true' : 'false'}
            onChange={(e) => onChange(e.target.value === 'true')}
          >
            <option value="true">true</option>
            <option value="false">false</option>
          </select>
        )}

        {kind === 'null' && <div className="px-1 py-1.5 font-mono text-xs text-slate-400">null</div>}

        {kind === 'hex' && <HexCell hex={(value as { $hex: string }).$hex} onChange={(hex) => onChange({ $hex: hex })} />}

        {kind === 'b64' && (
          <input
            type="text"
            className={`input font-mono ${compact ? 'py-1' : ''}`}
            placeholder="Base64"
            value={(value as { $b64: string }).$b64}
            onChange={(e) => {
              const v = e.target.value
              if (B64_RE.test(v) && v.length % 4 === 0) onChange({ $b64: v })
            }}
          />
        )}

        {kind === 'json' && (
          <textarea
            className={`input min-h-16 resize-y font-mono text-xs ${jsonDraft !== null && !isValidJson(jsonDraft) ? 'input-invalid' : ''}`}
            spellCheck={false}
            value={jsonText}
            onChange={(e) => {
              setJsonDraft(e.target.value)
              if (isValidJson(e.target.value)) onChange(JSON.parse(e.target.value))
            }}
            onBlur={() => setJsonDraft(null)}
          />
        )}
      </div>
    </div>
  )
}

function HexCell({ hex, onChange }: { hex: string; onChange: (hex: string) => void }) {
  const valid = HEX_RE.test(hex)
  const bytes = valid ? hex.length / 2 : 0
  return (
    <div>
      <input
        type="text"
        className={`input font-mono uppercase ${valid ? '' : 'input-invalid'}`}
        placeholder="如 A1B2FF"
        value={hex}
        onChange={(e) => {
          const v = e.target.value.replace(/[^0-9A-Fa-f]/g, '')
          onChange(v.toUpperCase())
        }}
      />
      <div className={`mt-0.5 text-[11px] ${valid ? 'text-slate-400' : 'text-red-600'}`}>
        {valid ? `${bytes} 字节（byte[]/char[] 参数编码）` : '必须是偶数长度的十六进制字符'}
      </div>
    </div>
  )
}

function isValidJson(text: string): boolean {
  if (text.trim() === '') return false
  try {
    JSON.parse(text)
    return true
  } catch {
    return false
  }
}
