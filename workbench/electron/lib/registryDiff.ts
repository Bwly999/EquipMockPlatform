/**
 * 插件清单 diff（纯函数）：工作台改动 registry 后用于报告/日志，
 * 与 agent 侧 PluginService diff 语义对齐（02 §6.2：新增→加载、删除→卸载、enabled→路由开关）。
 */
import type { PluginEntry, PluginRegistryDoc } from '../../src/lib/types'

export interface RegistryDiff {
  added: PluginEntry[]
  removed: PluginEntry[]
  toggled: { id: string; enabled: boolean }[]
  jarChanged: { id: string; from: string; to: string }[]
  aliasChanged: { id: string; alias: string }[]
}

export function diffRegistry(before: PluginRegistryDoc, after: PluginRegistryDoc): RegistryDiff {
  const beforeById = new Map(before.plugins.map((p) => [p.id, p]))
  const afterById = new Map(after.plugins.map((p) => [p.id, p]))

  const added = after.plugins.filter((p) => !beforeById.has(p.id))
  const removed = before.plugins.filter((p) => !afterById.has(p.id))

  const toggled: RegistryDiff['toggled'] = []
  const jarChanged: RegistryDiff['jarChanged'] = []
  const aliasChanged: RegistryDiff['aliasChanged'] = []
  for (const next of after.plugins) {
    const prev = beforeById.get(next.id)
    if (!prev) continue
    if (prev.enabled !== next.enabled) toggled.push({ id: next.id, enabled: next.enabled })
    if (prev.jar !== next.jar) jarChanged.push({ id: next.id, from: prev.jar, to: next.jar })
    if ((prev.alias ?? '') !== (next.alias ?? '')) aliasChanged.push({ id: next.id, alias: next.alias ?? '' })
  }
  return { added, removed, toggled, jarChanged, aliasChanged }
}
