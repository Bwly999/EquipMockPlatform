/**
 * 与 docs/schemas/*.schema.json（@1）同源的 TypeScript 文档模型。
 * 一致性由 tests/schema-parity.test.ts 锁定。
 */

// ---------- subgroup.schema.json ----------

export type MatchType = 'FULL_MATCH' | 'PATTERN_MATCH'

export interface ActionValue {
  type: 'VALUE'
  value: unknown
}

export interface ActionThrow {
  type: 'THROW'
  exception: string
  message?: string
}

export interface ActionVoid {
  type: 'VOID'
}

export type MockAction = ActionValue | ActionThrow | ActionVoid

export interface Rule {
  matchType: MatchType
  description?: string
  /** FULL_MATCH 必填：JSON 数组，逐位深度相等比较 */
  args?: unknown[]
  /** PATTERN_MATCH 必填：字符串数组，每元素为合法正则 */
  argsPattern?: string[]
  action: MockAction
}

export interface MockEntry {
  class: string
  method: string
  /** JVM 方法描述符，缺省=作用于同名全部重载 */
  signature?: string
  description?: string
  enabled: boolean
  defaultAction?: MockAction
  rules: Rule[]
}

export interface SubGroupDoc {
  $schema?: string
  name: string
  description?: string
  mocks: MockEntry[]
}

// ---------- settings.schema.json ----------

export interface Settings {
  $schema?: string
  activeGroup: string
  mockEnabled: boolean
}

// ---------- plugin-registry.schema.json ----------

export interface PluginEntry {
  id: string
  alias?: string
  jar: string
  enabled: boolean
  importedAt?: string
  note?: string
}

export interface PluginRegistryDoc {
  $schema?: string
  plugins: PluginEntry[]
}

// ---------- state.schema.json ----------

export type PluginRuntimeState =
  | 'STARTED'
  | 'RESOLVED'
  | 'DISABLED'
  | 'MISSING'
  | 'REJECTED'
  | 'FAILED'

export interface PluginStateEntry {
  id: string
  version: string
  state: PluginRuntimeState
  mockPoints?: number
  error?: string | null
}

export interface StateLastError {
  time: string
  file: string
  message: string
}

export interface StateDoc {
  $schema?: string
  agentVersion: string
  pid: number
  startedAt: string
  lastWriteAt?: string
  activeGroup: string
  mockEnabled: boolean
  instrumentedClasses?: number
  plugins?: PluginStateEntry[]
  lastError?: StateLastError | null
  needsRestart?: string[]
}

// ---------- IPC 视图类型 ----------

export interface SubGroupInfo {
  /** 文件名（含 .json） */
  file: string
  /** 显示名（doc.name 或文件名去扩展） */
  name: string
  /** mocks 数量；文件不可解析时为 -1 */
  mockCount: number
}

export interface GroupInfo {
  name: string
  subGroups: SubGroupInfo[]
}

export interface ValidationIssue {
  /** 归一化路径，如 mocks[0].rules[1].argsPattern[0]；根级为 '' */
  path: string
  message: string
  line?: number
  column?: number
}

export interface WriteResult {
  ok: boolean
  errors: ValidationIssue[]
}

export interface PluginRow {
  id: string
  alias: string
  jar: string
  jarExists: boolean
  jarSizeBytes: number
  enabled: boolean
  importedAt: string
  note: string
  /** 来自 state.json 的运行时状态；null=agent 未运行 */
  runtimeState: PluginRuntimeState | null
  runtimeVersion: string | null
  runtimeError: string | null
}

export interface OrphanJar {
  jar: string
  sizeBytes: number
}

export interface ManifestInfo {
  pluginId: string
  pluginVersion: string
  pluginRequires: string | null
  pluginDescription: string | null
}

export type ImportResult =
  | { status: 'cancelled' }
  | { status: 'imported'; plugin: PluginRow; warnings: string[] }
  | { status: 'overwritten'; plugin: PluginRow; warnings: string[] }
  | { status: 'needs-overwrite-confirm'; jarPath: string; manifest: ManifestInfo; existing: PluginRow }

export interface StateSnapshot {
  state: StateDoc | null
  logTail: string[]
  /** lastWriteAt 在 3s 内更新过且 pid 存活 */
  agentAlive: boolean
  /** pid 是否存活（可能存活但心跳超时） */
  pidAlive: boolean
  snapshotAt: string
}

export const SUBGROUP_SCHEMA_ID = 'equipmock/subgroup@1'
