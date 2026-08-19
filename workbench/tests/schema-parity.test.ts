/** SubGroupDoc TS 类型与 subgroup.schema.json 同源锁定（06 §8） */
import { describe, expect, it } from 'vitest'
import Ajv from 'ajv'
import subgroupSchema from '../src/schemas/subgroup.schema.json' with { type: 'json' }
import type { MockAction, Rule, SubGroupDoc } from '../src/lib/types'
import { SKELETON_DEFAULT_CABINET } from '../src/lib/skeletonData'

const ajv = new Ajv({ allErrors: true, strict: false })
const validate = ajv.compile(subgroupSchema as never)

describe('SubGroupDoc 与 schema 同源锁定', () => {
  it('TS 类型能表达的"最大化"文档通过 schema 校验', () => {
    const rule: Rule = {
      matchType: 'PATTERN_MATCH',
      description: 'd',
      argsPattern: ['\\d+'],
      action: { type: 'THROW', exception: 'java.io.IOException', message: 'm' },
    }
    const actions: MockAction[] = [
      { type: 'VALUE', value: 1 },
      { type: 'THROW', exception: 'a.B', message: 'm' },
      { type: 'VOID' },
    ]
    const doc: SubGroupDoc = {
      $schema: 'equipmock/subgroup@1',
      name: 'max',
      description: 'desc',
      mocks: [
        {
          class: 'a.B',
          method: 'm',
          signature: '()V',
          description: 'md',
          enabled: true,
          defaultAction: actions[0],
          rules: [
            { matchType: 'FULL_MATCH', args: [1, 's', true, null], action: actions[1] },
            rule,
          ],
        },
      ],
    }
    expect(validate(doc)).toBe(true)
  })

  it('骨架文档（TS 类型产出的真实数据）通过 schema', () => {
    expect(validate(SKELETON_DEFAULT_CABINET)).toBe(true)
  })

  it('schema 的结构关键点（锁定 additionalProperties:false 与必填）', () => {
    // 类型系统不允许的字段，schema 也不允许
    expect(validate({ name: 'x', mocks: [], unknownField: 1 })).toBe(false)
    expect(validate({ mocks: [] })).toBe(false) // 缺 name
    expect(validate({ name: 'x' })).toBe(false) // 缺 mocks
    // matchType 二选一必带对应字段
    expect(validate({ name: 'x', mocks: [{ class: 'a', method: 'b', enabled: true, rules: [{ matchType: 'FULL_MATCH', action: { type: 'VOID' } }] }] })).toBe(false)
    expect(
      validate({
        name: 'x',
        mocks: [
          {
            class: 'a',
            method: 'b',
            enabled: true,
            rules: [{ matchType: 'FULL_MATCH', args: [], action: { type: 'VOID' } }],
          },
        ],
      }),
    ).toBe(true)
    // action enum 锁定
    expect(validate({ name: 'x', mocks: [{ class: 'a', method: 'b', enabled: true, rules: [], defaultAction: { type: 'REAL' } }] })).toBe(false)
  })

  it('四份 schema 均为 draft-07 且带 $id（与 docs/schemas 契约一致）', async () => {
    for (const name of ['settings', 'subgroup', 'plugin-registry', 'state'] as const) {
      const schema = (await import(`../src/schemas/${name}.schema.json`, { with: { type: 'json' } })).default
      expect(schema.$schema).toBe('http://json-schema.org/draft-07/schema#')
      expect(schema.$id).toBe(`equipmock/${name}@1`)
    }
  })
})
