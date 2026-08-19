/**
 * 组级辅助校验（06 §5.4，保存前提示，非阻断）：
 * - 同一 (class,method,signature) 出现在本组多个小分组 → 合并顺序=文件名序提示
 * - signature 为空且同名重载存在 → "作用于全部重载"提示
 */
import type { SubGroupDoc, ValidationIssue } from '../types'

export interface SubGroupRef {
  file: string
  doc: SubGroupDoc
}

export function checkGroupCrossFile(subGroups: SubGroupRef[]): ValidationIssue[] {
  const warnings: ValidationIssue[] = []
  const byMethodId = new Map<string, { file: string; hasSignature: boolean }[]>()

  for (const { file, doc } of subGroups) {
    for (const mock of doc.mocks ?? []) {
      if (typeof mock?.class !== 'string' || typeof mock?.method !== 'string') continue
      const methodId = `${mock.class}#${mock.method}#${mock.signature ?? ''}`
      const list = byMethodId.get(methodId) ?? []
      list.push({ file, hasSignature: Boolean(mock.signature) })
      byMethodId.set(methodId, list)
    }
  }

  for (const [methodId, list] of byMethodId) {
    if (list.length > 1) {
      const files = [...new Set(list.map((l) => l.file))].sort()
      if (files.length > 1) {
        warnings.push({
          path: '',
          message: `${methodId} 在多个小分组重复定义（${files.join('、')}），agent 按文件名自然序合并规则链`,
        })
      }
    }
  }
  return warnings
}

export function checkOverloadHints(doc: SubGroupDoc): ValidationIssue[] {
  const warnings: ValidationIssue[] = []
  const counts = new Map<string, number>()
  for (const mock of doc.mocks ?? []) {
    if (mock?.signature) continue
    const key = `${mock?.class}#${mock?.method}`
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  for (const [key, n] of counts) {
    if (n > 0) {
      warnings.push({
        path: '',
        message: `${key} 未填写 signature，作用于同名全部重载${n > 1 ? `（本文件内有 ${n} 条无签名条目）` : ''}`,
      })
    }
  }
  return warnings
}
