/**
 * home 目录布局与命名规则（04 §1）。
 * groupName / subGroup 文件名：[A-Za-z0-9_-]{1,64}；alias 显示名：[\w\u4e00-\u9fa5-]{1,64}。
 */
import * as path from 'node:path'

export const GROUP_DIR_RE = /^[A-Za-z0-9_-]{1,64}$/
export const ALIAS_RE = /^[\w\u4e00-\u9fa5-]{1,64}$/
export const PLUGIN_ID_RE = /^[a-z0-9][a-z0-9-]*$/
export const SUBGROUP_FILE_RE = /^[A-Za-z0-9_-]{1,64}\.json$/

export const FILE_NAME = 'equip-mock'

export interface HomePaths {
  root: string
  settings: string
  state: string
  agentLog: string
  pluginsDir: string
  registry: string
  groupsDir: string
  logsDir: string
}

export function homePaths(home: string): HomePaths {
  const root = home
  return {
    root,
    settings: path.join(root, 'settings.json'),
    state: path.join(root, 'state.json'),
    agentLog: path.join(root, 'logs', 'agent.log'),
    pluginsDir: path.join(root, 'plugins'),
    registry: path.join(root, 'plugins', 'plugin-registry.json'),
    groupsDir: path.join(root, 'config', 'groups'),
    logsDir: path.join(root, 'logs'),
  }
}

export function groupDir(home: string, group: string): string {
  return path.join(homePaths(home).groupsDir, group)
}

export function subGroupFile(home: string, group: string, file: string): string {
  return path.join(groupDir(home, group), file)
}

export function assertValidGroupName(name: string): void {
  if (!GROUP_DIR_RE.test(name)) {
    throw new Error(`组名只能包含字母、数字、下划线、中划线，长度 1-64：${name}`)
  }
}

export function assertValidSubGroupFileName(file: string): void {
  if (!SUBGROUP_FILE_RE.test(file) || file.includes('.tmp')) {
    throw new Error(`小分组文件名非法：${file}`)
  }
}

export function assertValidAlias(alias: string): void {
  if (!ALIAS_RE.test(alias)) {
    throw new Error(`别名只能包含字母、数字、下划线、中划线、中文，长度 1-64：${alias}`)
  }
}

/** state.lastError.file（如 config/groups/default/cabinet.json）→ {group, file} */
export function parseErrorFileRef(ref: string): { group: string; file: string } | null {
  const m = /^config[/\\]groups[/\\]([^/\\]+)[/\\]([^/\\]+\.json)$/.exec(ref.replace(/\\/g, '/'))
  if (!m) return null
  return { group: m[1], file: m[2] }
}
