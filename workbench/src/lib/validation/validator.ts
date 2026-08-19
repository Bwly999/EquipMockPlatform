/**
 * 小分组文档校验器（保存前渲染层与主进程共用，与 03 §6 / docs/schemas/subgroup.schema.json 对齐）。
 *
 * 两层：
 *  1. ajv draft-07 Schema 校验（结构、必填、enum、pattern）；
 *  2. 语义校验（正则可编译、descriptor 可解析、FQCN 格式、VOID 不带 value、$hex/$b64 编码合法、
 *     组内重复方法提示等——见 03 §6 第 3 条与 06 §5.4）。
 *
 * 校验失败一律阻止落盘（04 §3：工作台保存的文件永远合法）。
 */
import Ajv from 'ajv'
import subgroupSchema from '../../schemas/subgroup.schema.json' with { type: 'json' }
import type { MockAction, SubGroupDoc, ValidationIssue, ValidationResult } from '../types'
import { indexJsonPositions, normalizeAjvPath, parseErrorPosition } from '../jsonLocate'
import { parseDescriptor } from './descriptor'
import { checkJsRegex } from './regex'

const ajv = new Ajv({ allErrors: true, strict: false })
const validateSchema = ajv.compile(subgroupSchema as never)

const FQCN_RE = /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$/
const METHOD_RE = /^[A-Za-z_$][A-Za-z0-9_$]*$/
const HEX_RE = /^([0-9A-Fa-f]{2})+$/
const B64_RE = /^[A-Za-z0-9+/]*={0,2}$/

export function validateSubGroupDoc(doc: unknown): ValidationResult {
  const errors: ValidationIssue[] = []
  const warnings: ValidationIssue[] = []

  // ---------- 第 1 层：Schema ----------
  if (!validateSchema(doc)) {
    for (const e of validateSchema.errors ?? []) {
      errors.push({
        path: normalizeAjvPath(e.instancePath ?? ''),
        message: e.message ? `${e.message}${e.params && Object.keys(e.params).length ? `（${formatParams(e.params)}）` : ''}` : 'Schema 校验失败',
      })
    }
  }
  // Schema 失败后语义层仍尽量跑（结构性防御），但 mocks 非数组时直接返回
  if (typeof doc !== 'object' || doc === null || !Array.isArray((doc as SubGroupDoc).mocks)) {
    return { ok: errors.length === 0, errors, warnings }
  }

  // ---------- 第 2 层：语义 ----------
  const d = doc as SubGroupDoc
  const seenMethodIds = new Map<string, number>()

  d.mocks.forEach((mock, mi) => {
    if (typeof mock !== 'object' || mock === null) return
    const base = `mocks[${mi}]`

    if (typeof mock.class === 'string' && !FQCN_RE.test(mock.class)) {
      errors.push({ path: `${base}.class`, message: `类名不是合法 FQCN：${mock.class}` })
    }
    if (typeof mock.method === 'string' && !METHOD_RE.test(mock.method)) {
      errors.push({ path: `${base}.method`, message: `方法名不合法：${mock.method}` })
    }
    if (typeof mock.signature === 'string' && mock.signature.length > 0) {
      const r = parseDescriptor(mock.signature)
      if (!r.ok) {
        errors.push({ path: `${base}.signature`, message: `JVM 描述符非法：${r.error}` })
      }
    }

    const methodId = `${mock.class}#${mock.method}#${mock.signature ?? ''}`
    const prev = seenMethodIds.get(methodId)
    if (prev !== undefined) {
      warnings.push({
        path: base,
        message: `与 mocks[${prev}] 重复定义同一目标（${methodId}），后者规则不会生效`,
      })
    } else {
      seenMethodIds.set(methodId, mi)
    }

    mock.rules?.forEach((rule, ri) => {
      if (typeof rule !== 'object' || rule === null) return
      const rBase = `${base}.rules[${ri}]`

      if (rule.matchType === 'FULL_MATCH') {
        if (rule.argsPattern !== undefined) {
          warnings.push({ path: `${rBase}.argsPattern`, message: 'FULL_MATCH 规则中的 argsPattern 不会参与匹配' })
        }
        if (Array.isArray(rule.args)) {
          rule.args.forEach((arg, ai) => checkArgValue(arg, `${rBase}.args[${ai}]`, errors))
        }
      } else if (rule.matchType === 'PATTERN_MATCH') {
        if (rule.args !== undefined) {
          warnings.push({ path: `${rBase}.args`, message: 'PATTERN_MATCH 规则中的 args 不会参与匹配' })
        }
        if (Array.isArray(rule.argsPattern)) {
          rule.argsPattern.forEach((p, pi) => {
            if (typeof p !== 'string') return
            const check = checkJsRegex(p)
            if (!check.ok) {
              errors.push({ path: `${rBase}.argsPattern[${pi}]`, message: check.error ?? '非法正则' })
            }
          })
        }
      }

      checkAction(rule.action, `${rBase}.action`, errors)
    })

    if (mock.defaultAction !== undefined) {
      checkAction(mock.defaultAction, `${base}.defaultAction`, errors)
    }
  })

  return { ok: errors.length === 0, errors, warnings }
}

function checkAction(action: unknown, path: string, errors: ValidationIssue[]): void {
  if (typeof action !== 'object' || action === null) return
  const a = action as MockAction & { value?: unknown; exception?: unknown }
  if (a.type === 'VOID') {
    if ('value' in (a as object)) {
      errors.push({ path, message: 'VOID 动作不能配置 value' })
    }
  } else if (a.type === 'THROW') {
    if (typeof a.exception === 'string' && !FQCN_RE.test(a.exception)) {
      errors.push({ path: `${path}.exception`, message: `异常类名不是合法 FQCN：${a.exception}` })
    }
  } else if (a.type === 'VALUE') {
    checkArgValue(a.value, `${path}.value`, errors)
  }
}

/** FULL_MATCH 参数 / VALUE 值的类型标签对象编码检查（04 §3.2） */
function checkArgValue(value: unknown, path: string, errors: ValidationIssue[]): void {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return
  const keys = Object.keys(value)
  if (keys.includes('$hex') || keys.includes('$b64')) {
    if (keys.length > 1) {
      errors.push({ path, message: '类型标签对象（$hex/$b64）不能与其他字段混用' })
      return
    }
    if (keys.includes('$hex')) {
      const v = (value as { $hex: unknown }).$hex
      if (typeof v !== 'string' || !HEX_RE.test(v)) {
        errors.push({ path, message: '$hex 必须是非空偶数长度的十六进制字符串（如 "A1B2FF"）' })
      }
    } else {
      const v = (value as { $b64: unknown }).$b64
      if (typeof v !== 'string' || !B64_RE.test(v) || v.length % 4 !== 0) {
        errors.push({ path, message: '$b64 必须是合法 Base64 字符串' })
      }
    }
  }
}

export interface TextValidationResult extends ValidationResult {
  doc: SubGroupDoc | null
}

/** Monaco 文本模式入口：parse → 定位 → Schema → 语义 */
export function validateSubGroupText(text: string): TextValidationResult {
  let doc: unknown
  try {
    doc = JSON.parse(text)
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e)
    const pos = parseErrorPosition(text, e)
    return {
      ok: false,
      doc: null,
      errors: [{ path: '', message: `JSON 语法错误：${message}`, ...pos }],
      warnings: [],
    }
  }
  const result = validateSubGroupDoc(doc)
  if (result.ok && result.warnings.length === 0) {
    return { ...result, doc: doc as SubGroupDoc }
  }
  // 为 issue 补充行列
  const index = indexJsonPositions(text)
  const locate = (issues: ValidationIssue[]): ValidationIssue[] =>
    issues.map((issue) => {
      const pos = index.get(issue.path)
      return pos ? { ...issue, ...pos } : issue
    })
  return {
    ...result,
    errors: locate(result.errors),
    warnings: locate(result.warnings),
    doc: result.ok ? (doc as SubGroupDoc) : null,
  }
}

function formatParams(params: Record<string, unknown>): string {
  return Object.entries(params)
    .map(([k, v]) => (k === 'additionalProperty' ? `多余字段 ${String(v)}` : `${k}=${JSON.stringify(v)}`))
    .join(' ')
}
