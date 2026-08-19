/**
 * 正则工具：JS RegExp 即时校验（与 Java java.util.regex 常用子集语义一致）
 * 及 Java matches()（全匹配）语义的测试。
 */

export interface RegexCheck {
  ok: boolean
  error?: string
  /** JS 与 Java 方言差异提示（不阻断保存） */
  hint?: string
}

export function checkJsRegex(pattern: string): RegexCheck {
  if (typeof pattern !== 'string' || pattern.length === 0) {
    return { ok: false, error: '正则为空' }
  }
  try {
    // eslint-disable-next-line no-new
    new RegExp(pattern)
  } catch (e) {
    return { ok: false, error: `非法正则：${e instanceof Error ? e.message : String(e)}` }
  }
  const hints: string[] = []
  if (/\\p\{/.test(pattern) && !/\\p\{[^}]+\}/u.test(pattern.replace(/\\p\{/, '\\p{'))) {
    // 落到 hint 的场景极少：JS 基本都直接抛错，这里兜底提示
  }
  if (/\\p\{/.test(pattern)) hints.push('JS 需 u 标志才支持 \\p{...}，与 Java 行为可能有差异')
  if (/\(\?<name>/.test(pattern)) hints.push('命名分组语法两侧一致，但请确认分组名规则')
  return { ok: true, hint: hints.length ? hints.join('；') : undefined }
}

/** Java Pattern.matches() 是全匹配；用 ^(?:...)$ 包一层在 JS 里模拟 */
export function javaMatches(pattern: string, input: string): boolean {
  try {
    return new RegExp(`^(?:${pattern})$`).test(input)
  } catch {
    return false
  }
}

/** Java 侧字符串规范串（03 §5.1）中对 null/数字等的展示，用于测试匹配的提示 */
export function canonicalPreview(value: string): string {
  return value
}
