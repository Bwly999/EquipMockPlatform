/**
 * JSON 文本定位：解析错误的行列号 + 归一化路径 → 行列 的索引。
 * 用于 Monaco 模式下的错误列表定位（06 §5.3）。
 */

export interface Pos {
  line: number // 1-based
  column: number // 1-based
}

export function offsetToPos(text: string, offset: number): Pos {
  let line = 1
  let lastLineStart = 0
  for (let i = 0; i < offset && i < text.length; i++) {
    if (text[i] === '\n') {
      line++
      lastLineStart = i + 1
    }
  }
  return { line, column: offset - lastLineStart + 1 }
}

/** V8 JSON.parse 错误 → 行列；无法提取时返回 undefined */
export function parseErrorPosition(text: string, err: unknown): Pos | undefined {
  const message = err instanceof Error ? err.message : String(err)
  const m = /position (\d+)/.exec(message)
  if (m) return offsetToPos(text, Number(m[1]))
  return undefined
}

/**
 * 建立归一化路径 → 值/键起始位置 的索引。
 * 路径形式与 ValidationIssue.path 一致：mocks[0].rules[1].argsPattern[0]。
 * 仅对合法 JSON 建立（调用前先成功 parse）。
 */
export function indexJsonPositions(text: string): Map<string, Pos> {
  const map = new Map<string, Pos>()
  let i = 0
  const len = text.length

  const skipWs = () => {
    while (i < len && /\s/.test(text[i]!)) i++
  }
  const record = (path: string, pos: Pos) => {
    if (!map.has(path)) map.set(path, pos)
  }

  const parseValue = (path: string): unknown => {
    skipWs()
    const start = i
    const ch = text[i]
    if (ch === '{') {
      record(path, offsetToPos(text, start))
      i++
      const obj: Record<string, unknown> = {}
      skipWs()
      if (text[i] === '}') {
        i++
        return obj
      }
      while (true) {
        skipWs()
        const keyStart = i
        if (text[i] !== '"') throw new Error('expect object key')
        const key = parseString()
        const keyPath = path ? `${path}.${key}` : key
        record(keyPath, offsetToPos(text, keyStart))
        skipWs()
        if (text[i] !== ':') throw new Error('expect :')
        i++
        obj[key] = parseValue(keyPath)
        skipWs()
        if (text[i] === ',') {
          i++
          continue
        }
        if (text[i] === '}') {
          i++
          return obj
        }
        throw new Error('expect , or }')
      }
    }
    if (ch === '[') {
      record(path, offsetToPos(text, start))
      i++
      const arr: unknown[] = []
      skipWs()
      if (text[i] === ']') {
        i++
        return arr
      }
      let idx = 0
      while (true) {
        arr.push(parseValue(`${path}[${idx}]`))
        idx++
        skipWs()
        if (text[i] === ',') {
          i++
          continue
        }
        if (text[i] === ']') {
          i++
          return arr
        }
        throw new Error('expect , or ]')
      }
    }
    if (ch === '"') {
      record(path, offsetToPos(text, start))
      return parseString()
    }
    // number / true / false / null
    const m = /^(true|false|null|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(text.slice(i))
    if (!m) throw new Error(`unexpected token at ${i}`)
    record(path, offsetToPos(text, start))
    i += m[1].length
    if (m[1] === 'true') return true
    if (m[1] === 'false') return false
    if (m[1] === 'null') return null
    return Number(m[1])
  }

  const parseString = (): string => {
    // text[i] === '"'
    i++
    let out = ''
    while (i < len) {
      const c = text[i]!
      if (c === '"') {
        i++
        return out
      }
      if (c === '\\') {
        const n = text[i + 1]
        if (n === 'u') {
          out += String.fromCharCode(parseInt(text.slice(i + 2, i + 6), 16))
          i += 6
          continue
        }
        out += n === 'n' ? '\n' : n === 't' ? '\t' : n === 'r' ? '\r' : n!
        i += 2
        continue
      }
      out += c
      i++
    }
    throw new Error('unterminated string')
  }

  try {
    parseValue('')
  } catch {
    // 非法 JSON：返回已建立的部分索引
  }
  return map
}

/** 把 ajv 的 instancePath（/mocks/0/rules）归一化为 mocks[0].rules */
export function normalizeAjvPath(instancePath: string): string {
  if (!instancePath) return ''
  const parts = instancePath.split('/').filter(Boolean)
  let out = ''
  for (const part of parts) {
    if (/^\d+$/.test(part)) {
      out += `[${part}]`
    } else {
      out += (out ? '.' : '') + part
    }
  }
  return out
}
