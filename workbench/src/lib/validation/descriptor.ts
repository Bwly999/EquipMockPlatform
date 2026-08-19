/**
 * JVM 方法描述符解析（03 §6 加载期校验第 2 条的反向校验）。
 * 语法：( 参数描述符* ) 返回值描述符
 * 参数类型：B Z C D F I J S / L<内部名>; / [ 开头的数组维度 + 基类型
 */

export interface ParsedDescriptor {
  /** 参数类型描述符列表（不含括号） */
  params: string[]
  /** 返回类型描述符（可以是 V） */
  returnType: string
}

const PRIMITIVES = new Set(['B', 'Z', 'C', 'D', 'F', 'I', 'J', 'S'])

export type DescriptorResult = { ok: true; value: ParsedDescriptor } | { ok: false; error: string }

export function parseDescriptor(descriptor: string): DescriptorResult {
  if (typeof descriptor !== 'string' || descriptor.length === 0) {
    return { ok: false, error: '签名为空' }
  }
  if (descriptor[0] !== '(') {
    return { ok: false, error: `签名必须以 "(" 开头，实际是 "${descriptor[0]}"` }
  }
  let i = 1
  const params: string[] = []

  const readType = (allowVoid: boolean): string | null => {
    const start = i
    const ch = descriptor[i]
    if (ch === undefined) return null
    if (ch === '[') {
      let dims = 0
      while (descriptor[i] === '[') {
        dims++
        i++
      }
      const base = descriptor[i]
      if (base === 'L') {
        // [Ljava/lang/String;
        i++
        const semi = descriptor.indexOf(';', i)
        if (semi === -1) return null
        const name = descriptor.slice(start + dims + 1, semi)
        if (!isValidInternalName(name)) return null
        i = semi + 1
      } else if (base !== undefined && PRIMITIVES.has(base)) {
        i++
      } else {
        return null
      }
      return descriptor.slice(start, i)
    }
    if (PRIMITIVES.has(ch)) {
      i++
      return ch
    }
    if (ch === 'V') {
      if (!allowVoid) return null
      i++
      return 'V'
    }
    if (ch === 'L') {
      i++
      const semi = descriptor.indexOf(';', i)
      if (semi === -1) return null
      const name = descriptor.slice(start + 1, semi)
      if (!isValidInternalName(name)) return null
      i = semi + 1
      return descriptor.slice(start, i)
    }
    return null
  }

  while (true) {
    if (descriptor[i] === undefined) {
      return { ok: false, error: '签名缺少 ")" 或参数描述符不完整' }
    }
    if (descriptor[i] === ')') {
      i++
      break
    }
    const t = readType(false)
    if (t === null) {
      return { ok: false, error: `第 ${params.length + 1} 个参数描述符非法（位于 "${descriptor.slice(Math.max(0, i - 2), i + 4)}" 附近）` }
    }
    params.push(t)
  }

  const ret = readType(true)
  if (ret === null) {
    return { ok: false, error: '返回值描述符非法（应为 V/Z/B/C/D/F/I/J/S 或 L...; 或 [...）' }
  }
  if (i !== descriptor.length) {
    return { ok: false, error: `返回值描述符后存在多余内容 "${descriptor.slice(i)}"` }
  }
  return { ok: true, value: { params, returnType: ret } }
}

/** 内部名（L...; 之间）：至少一段，允许 . / $ 分隔，段以字母 _ $ 开头 */
function isValidInternalName(name: string): boolean {
  if (name.length === 0) return false
  return name.split(/[.$/]/).every((seg) => /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(seg))
}

/** 描述符 → 便于人读的形式，如 (ILjava/lang/String;)I → (int, String) → int */
const PRIM_NAMES: Record<string, string> = {
  B: 'byte',
  Z: 'boolean',
  C: 'char',
  D: 'double',
  F: 'float',
  I: 'int',
  J: 'long',
  S: 'short',
  V: 'void',
}

export function describeType(typeDesc: string): string {
  let dims = 0
  let t = typeDesc
  while (t.startsWith('[')) {
    dims++
    t = t.slice(1)
  }
  let base: string
  if (t.startsWith('L') && t.endsWith(';')) {
    base = t.slice(1, -1).replace(/\//g, '.')
  } else {
    base = PRIM_NAMES[t] ?? t
  }
  return base + '[]'.repeat(dims)
}

export function describeDescriptor(descriptor: string): string {
  const r = parseDescriptor(descriptor)
  if (!r.ok) return descriptor
  const params = r.value.params.map(describeType).join(', ')
  return `(${params}) → ${describeType(r.value.returnType)}`
}
