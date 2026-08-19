/** JVM descriptor 解析与正则工具测试 */
import { describe, expect, it } from 'vitest'
import { describeDescriptor, parseDescriptor } from '../src/lib/validation/descriptor'
import { checkJsRegex, javaMatches } from '../src/lib/validation/regex'

describe('parseDescriptor', () => {
  const valid: [string, string[], string][] = [
    ['()V', [], 'V'],
    ['()D', [], 'D'],
    ['(I)V', ['I'], 'V'],
    ['(ILjava/lang/String;)I', ['I', 'Ljava/lang/String;'], 'I'],
    ['([BI)V', ['[B', 'I'], 'V'],
    ['([[Ljava/lang/Object;)[Ljava/lang/String;', ['[[Ljava/lang/Object;'], '[Ljava/lang/String;'],
    ['(Z[C[B[S[J[F)J', ['Z', '[C', '[B', '[S', '[J', '[F'], 'J'],
    ['(Lcom/outer$Inner;)V', ['Lcom/outer$Inner;'], 'V'],
  ]
  for (const [input, params, ret] of valid) {
    it(`合法：${input}`, () => {
      const r = parseDescriptor(input)
      expect(r.ok).toBe(true)
      if (r.ok) {
        expect(r.value.params).toEqual(params)
        expect(r.value.returnType).toBe(ret)
      }
    })
  }

  const invalid = ['', 'I)V', '(I', '(Lcom/Foo)V', '(X)V', '()VV', '(I)I extra', '(', '()', '([)V']
  for (const input of invalid) {
    it(`非法：${JSON.stringify(input)}`, () => {
      expect(parseDescriptor(input).ok).toBe(false)
    })
  }

  it('人读描述', () => {
    expect(describeDescriptor('(ILjava/lang/String;)I')).toBe('(int, java.lang.String) → int')
    expect(describeDescriptor('()[Ljava/lang/String;')).toBe('() → java.lang.String[]')
  })
})

describe('checkJsRegex', () => {
  it('合法正则', () => {
    expect(checkJsRegex('\\d+').ok).toBe(true)
    expect(checkJsRegex('CH(9[0-9])').ok).toBe(true)
    expect(checkJsRegex('(?<=x)y').ok).toBe(true)
  })

  it('非法正则', () => {
    expect(checkJsRegex('[unclosed').ok).toBe(false)
    expect(checkJsRegex('*start').ok).toBe(false)
    expect(checkJsRegex('a{2,1}').ok).toBe(false)
    expect(checkJsRegex('').ok).toBe(false)
  })

  it('javaMatches 全匹配语义', () => {
    expect(javaMatches('\\d+', '123')).toBe(true)
    expect(javaMatches('\\d+', 'a123')).toBe(false) // Java matches() 是全匹配
    expect(javaMatches('CH.*', 'CH01')).toBe(true)
    expect(javaMatches('CH.*', 'XCH01')).toBe(false)
  })
})
