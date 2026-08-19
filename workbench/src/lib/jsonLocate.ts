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

/** V8 JSON.parse 错误 → 行列；无法提取时用自有扫描器兜底定位 */
export function parseErrorPosition(text: string, err: unknown): Pos | undefined {
  const message = err instanceof Error ? err.message : String(err)
  const m = /position (\d+)/.exec(message)
  if (m) return offsetToPos(text, Number(m[1]))
  // Node/V8 新版错误消息不带 position：用自有扫描器兜底定位
  const offset = scanErrorOffset(text)
  if (offset !== null) return offsetToPos(text, offset)
  return undefined
}

/**
 * 建立归一化路径 → 值/键起始位置 的索引。
 * 路径形式与 ValidationIssue.path 一致：mocks[0].rules[1].argsPattern[0]。
 * 仅对合法 JSON 建立（调用前先成功 parse）；非法文本抛含 offset 的错误。
 */
export function indexJsonPositions(text: string): Map<string, Pos> {
  const parser = new JsonIndexParser(text)
  return parser.run()
}

/** 扫描非法 JSON，返回第一个出错字符的 offset（供 parse 错误定位兜底）；合法返回 null */
export function scanErrorOffset(text: string): number | null {
  const parser = new JsonIndexParser(text)
  try {
    parser.run()
    return null
  } catch (e) {
    const m = /at (\d+)/.exec(e instanceof Error ? e.message : String(e))
    return m ? Number(m[1]) : null
  }
}

class JsonIndexParser {
  private readonly map = new Map<string, Pos>()
  private i = 0
  constructor(private readonly text: string) {}

  run(): Map<string, Pos> {
    this.parseValue('')
    return this.map
  }

  private fail(message: string): never {
    throw new Error(`${message} at ${this.i}`)
  }

  private skipWs(): void {
    while (this.i < this.text.length && /\s/.test(this.text[this.i]!)) this.i++
  }

  private record(path: string, pos: Pos): void {
    if (!this.map.has(path)) this.map.set(path, pos)
  }

  private parseValue(path: string): unknown {
    this.skipWs()
    const start = this.i
    const ch = this.text[this.i]
    if (ch === undefined) this.fail('unexpected end of input')
    if (ch === '{') {
      this.record(path, offsetToPos(this.text, start))
      this.i++
      const obj: Record<string, unknown> = {}
      this.skipWs()
      if (this.text[this.i] === '}') {
        this.i++
        return obj
      }
      while (true) {
        this.skipWs()
        const keyStart = this.i
        if (this.text[this.i] !== '"') this.fail('expect object key')
        const key = this.parseString()
        const keyPath = path ? `${path}.${key}` : key
        this.record(keyPath, offsetToPos(this.text, keyStart))
        this.skipWs()
        if (this.text[this.i] !== ':') this.fail('expect :')
        this.i++
        obj[key] = this.parseValue(keyPath)
        this.skipWs()
        if (this.text[this.i] === ',') {
          this.i++
          continue
        }
        if (this.text[this.i] === '}') {
          this.i++
          return obj
        }
        this.fail('expect , or }')
      }
    }
    if (ch === '[') {
      this.record(path, offsetToPos(this.text, start))
      this.i++
      const arr: unknown[] = []
      this.skipWs()
      if (this.text[this.i] === ']') {
        this.i++
        return arr
      }
      let idx = 0
      while (true) {
        arr.push(this.parseValue(`${path}[${idx}]`))
        idx++
        this.skipWs()
        if (this.text[this.i] === ',') {
          this.i++
          continue
        }
        if (this.text[this.i] === ']') {
          this.i++
          return arr
        }
        this.fail('expect , or ]')
      }
    }
    if (ch === '"') {
      this.record(path, offsetToPos(this.text, start))
      return this.parseString()
    }
    // number / true / false / null
    const m = /^(true|false|null|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(this.text.slice(this.i))
    if (!m) this.fail(`unexpected token '${this.text[this.i]}'`)
    this.record(path, offsetToPos(this.text, start))
    this.i += m[1]!.length
    if (m[1] === 'true') return true
    if (m[1] === 'false') return false
    if (m[1] === 'null') return null
    return Number(m[1])
  }

  private parseString(): string {
    // text[i] === '"'
    this.i++
    let out = ''
    while (this.i < this.text.length) {
      const c = this.text[this.i]!
      if (c === '"') {
        this.i++
        return out
      }
      if (c === '\\') {
        const n = this.text[this.i + 1]
        if (n === undefined) this.fail('unterminated string')
        if (n === 'u') {
          const hex = this.text.slice(this.i + 2, this.i + 6)
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) this.fail('bad unicode escape')
          out += String.fromCharCode(parseInt(hex, 16))
          this.i += 6
          continue
        }
        out += n === 'n' ? '\n' : n === 't' ? '\t' : n === 'r' ? '\r' : n!
        this.i += 2
        continue
      }
      out += c
      this.i++
    }
    this.fail('unterminated string')
  }
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
