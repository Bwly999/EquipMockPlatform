/** 校验器纯函数测试（03 §6 全规则 + 06 §5.4 辅助提示），正反例 fixture */
import { describe, expect, it } from 'vitest'
import { SKELETON_DEFAULT_CABINET, SKELETON_GROUPS } from '../src/lib/skeletonData'
import type { SubGroupDoc } from '../src/lib/types'
import { checkGroupCrossFile, checkOverloadHints } from '../src/lib/validation/groupChecks'
import { validateSubGroupDoc, validateSubGroupText } from '../src/lib/validation/validator'

const baseDoc = (): SubGroupDoc => JSON.parse(JSON.stringify(SKELETON_DEFAULT_CABINET))

describe('validateSubGroupDoc 正例', () => {
  it('04 §3 完整示例通过', () => {
    const r = validateSubGroupDoc(baseDoc())
    expect(r.ok).toBe(true)
    expect(r.errors).toEqual([])
  })

  it('骨架全部示例组通过（default / fault-sim）', () => {
    for (const [group, files] of Object.entries(SKELETON_GROUPS)) {
      for (const [file, doc] of Object.entries(files)) {
        const r = validateSubGroupDoc(doc)
        expect(r.ok, `${group}/${file}`).toBe(true)
      }
    }
  })

  it('$hex / $b64 / null / 嵌套对象值均合法', () => {
    const r = validateSubGroupDoc({
      name: 'x',
      mocks: [
        {
          class: 'a.B',
          method: 'm',
          enabled: true,
          rules: [
            {
              matchType: 'FULL_MATCH',
              args: [null, { $hex: 'A1B2FF' }, { $b64: 'QUJD' }, [1, { k: 'v' }]],
              action: { type: 'VALUE', value: { $hex: '00FF' } },
            },
          ],
        },
      ],
    })
    expect(r.ok).toBe(true)
  })
})

describe('validateSubGroupDoc 反例（Schema 层）', () => {
  const cases: { name: string; mutate: (d: SubGroupDoc) => void; path: string }[] = [
    {
      name: 'mock 缺 method',
      mutate: (d) => delete (d.mocks[0] as unknown as Record<string, unknown>).method,
      path: 'mocks[0]',
    },
    {
      name: 'FULL_MATCH 缺 args',
      mutate: (d) => delete (d.mocks[0]!.rules[0] as unknown as Record<string, unknown>).args,
      path: 'mocks[0].rules[0]',
    },
    {
      name: 'PATTERN_MATCH 缺 argsPattern',
      mutate: (d) => delete (d.mocks[0]!.rules[1] as unknown as Record<string, unknown>).argsPattern,
      path: 'mocks[0].rules[1]',
    },
    {
      name: 'VALUE 缺 value',
      mutate: (d) => {
        ;(d.mocks[0]!.rules[0]!.action as unknown as Record<string, unknown>).type = 'VALUE'
        delete (d.mocks[0]!.rules[0]!.action as unknown as Record<string, unknown>).value
      },
      path: 'mocks[0].rules[0].action',
    },
    {
      name: 'THROW 缺 exception',
      mutate: (d) => {
        d.mocks[0]!.rules[0]!.action = { type: 'THROW', message: 'x' } as never
      },
      path: 'mocks[0].rules[0].action',
    },
    {
      name: 'matchType 非法值',
      mutate: (d) => {
        ;(d.mocks[0]!.rules[0] as unknown as Record<string, unknown>).matchType = 'REGEX_MATCH'
      },
      path: 'mocks[0].rules[0]',
    },
    {
      name: '多余字段',
      mutate: (d) => {
        ;(d as unknown as Record<string, unknown>).extra = 1
      },
      path: '',
    },
    {
      name: 'name 超长',
      mutate: (d) => {
        d.name = 'a'.repeat(65)
      },
      path: 'name',
    },
    {
      name: 'enabled 非布尔',
      mutate: (d) => {
        ;(d.mocks[0] as unknown as Record<string, unknown>).enabled = 'yes'
      },
      path: 'mocks[0]',
    },
  ]

  for (const c of cases) {
    it(c.name, () => {
      const doc = baseDoc()
      c.mutate(doc)
      const r = validateSubGroupDoc(doc)
      expect(r.ok).toBe(false)
      expect(r.errors.length).toBeGreaterThan(0)
    })
  }
})

describe('validateSubGroupDoc 反例（语义层，03 §6）', () => {
  it('argsPattern 含非法正则', () => {
    const doc = baseDoc()
    doc.mocks[0]!.rules[1]!.argsPattern = ['[unclosed']
    const r = validateSubGroupDoc(doc)
    expect(r.ok).toBe(false)
    expect(r.errors[0]!.path).toBe('mocks[0].rules[1].argsPattern[0]')
    expect(r.errors[0]!.message).toMatch(/非法正则/)
  })

  it('signature 非法 JVM descriptor', () => {
    const doc = baseDoc()
    doc.mocks[0]!.signature = '((I)'
    expect(validateSubGroupDoc(doc).ok).toBe(false)

    doc.mocks[0]!.signature = '(Lcom/Foo)V' // 缺分号
    expect(validateSubGroupDoc(doc).ok).toBe(false)

    doc.mocks[0]!.signature = '(I)X' // 返回类型非法
    expect(validateSubGroupDoc(doc).ok).toBe(false)
  })

  it('VOID 不能配 value（03 §6 第 3 条）', () => {
    const doc = baseDoc()
    doc.mocks[0]!.rules[0]!.action = { type: 'VOID', value: 1 } as never
    const r = validateSubGroupDoc(doc)
    expect(r.ok).toBe(false)
    expect(r.errors[0]!.message).toMatch(/VOID 动作不能配置 value/)
  })

  it('THROW exception 非 FQCN', () => {
    const doc = baseDoc()
    doc.mocks[0]!.rules[1]!.action = { type: 'THROW', exception: 'not a fqcn!', message: 'x' }
    const r = validateSubGroupDoc(doc)
    expect(r.ok).toBe(false)
    expect(r.errors[0]!.path).toBe('mocks[0].rules[1].action.exception')
  })

  it('class 非 FQCN / method 非法', () => {
    const doc = baseDoc()
    doc.mocks[0]!.class = 'com..Bad'
    doc.mocks[0]!.method = '1bad'
    const r = validateSubGroupDoc(doc)
    expect(r.errors.map((e) => e.path)).toContain('mocks[0].class')
    expect(r.errors.map((e) => e.path)).toContain('mocks[0].method')
  })

  it('$hex 编码非法（奇数长度）', () => {
    const doc = baseDoc()
    doc.mocks[0]!.rules[0]!.args = [{ $hex: 'ABC' }]
    const r = validateSubGroupDoc(doc)
    expect(r.ok).toBe(false)
    expect(r.errors[0]!.message).toMatch(/\$hex/)
  })

  it('$hex 与其他字段混用', () => {
    const doc = baseDoc()
    doc.mocks[0]!.rules[0]!.args = [{ $hex: 'AA', extra: 1 }]
    expect(validateSubGroupDoc(doc).ok).toBe(false)
  })

  it('$b64 非法', () => {
    const doc = baseDoc()
    doc.mocks[0]!.rules[0]!.args = [{ $b64: 'a===' }]
    expect(validateSubGroupDoc(doc).ok).toBe(false)
  })
})

describe('validateSubGroupDoc 警告（非阻断，06 §5.4）', () => {
  it('同文件重复 (class,method,signature) 提示', () => {
    const doc = baseDoc()
    doc.mocks.push(JSON.parse(JSON.stringify(doc.mocks[0])))
    const r = validateSubGroupDoc(doc)
    expect(r.ok).toBe(true)
    expect(r.warnings.some((w) => w.message.includes('重复定义'))).toBe(true)
  })

  it('无 signature 提示作用于全部重载', () => {
    const warnings = checkOverloadHints(baseDoc())
    expect(warnings.some((w) => w.message.includes('powerOn'))).toBe(true)
  })

  it('跨小分组重复提示（组级）', () => {
    const warnings = checkGroupCrossFile([
      { file: 'a.json', doc: baseDoc() },
      { file: 'b.json', doc: baseDoc() },
    ])
    expect(warnings.some((w) => w.message.includes('多个小分组'))).toBe(true)
  })
})

describe('validateSubGroupText（Monaco 模式）', () => {
  it('语法错误返回行列', () => {
    const text = '{\n  "name": "x",\n  "mocks": [oops]\n}'
    const r = validateSubGroupText(text)
    expect(r.ok).toBe(false)
    expect(r.doc).toBeNull()
    expect(r.errors[0]!.line).toBeGreaterThan(0)
    expect(r.errors[0]!.message).toMatch(/JSON 语法错误/)
  })

  it('合法文本返回 doc 且错误可定位到行列', () => {
    const doc = baseDoc()
    doc.mocks[0]!.rules[1]!.argsPattern = ['[bad']
    const text = JSON.stringify(doc, null, 2)
    const r = validateSubGroupText(text)
    expect(r.ok).toBe(false)
    const issue = r.errors.find((e) => e.path === 'mocks[0].rules[1].argsPattern[0]')
    expect(issue).toBeTruthy()
    expect(issue!.line).toBeGreaterThan(0)
    expect(text.split('\n')[issue!.line! - 1]).toContain('[bad')
  })

  it('空 mock 数组与空 rules 合法', () => {
    const r = validateSubGroupText('{"name":"empty","mocks":[]}')
    expect(r.ok).toBe(true)
    expect(r.doc!.name).toBe('empty')
  })
})
