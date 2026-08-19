/**
 * equip-mock 目录的数据访问层：主进程全部 fs 操作的唯一实现（06 §1）。
 * 读：settings / groups / subgroup / registry / state / agent.log 尾部。
 * 写：settings、subgroup（先校验）、registry、组目录级操作、jar 拷贝/删除。
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import type {
  GroupInfo,
  PluginEntry,
  PluginRegistryDoc,
  PluginRow,
  PluginRuntimeState,
  OrphanJar,
  Settings,
  StateDoc,
  StateLastError,
  SubGroupDoc,
  ValidationIssue,
  WriteResult,
} from '../../src/lib/types'
import { SUBGROUP_SCHEMA_ID } from '../../src/lib/types'
import { validateSubGroupDoc } from '../../src/lib/validation/validator'
import { atomicWriteFile, readJsonIfExists, writeJsonPretty } from './atomicWrite'
import {
  assertValidGroupName,
  assertValidSubGroupFileName,
  groupDir,
  homePaths,
  subGroupFile,
} from './paths'

const DEFAULT_SETTINGS: Settings = { activeGroup: 'default', mockEnabled: true }

export class HomeError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'HomeError'
  }
}

function mustHome(home: string | null): string {
  if (!home) throw new HomeError('尚未设置 equip-mock 主目录')
  return home
}

// ---------------- settings ----------------

export function readSettings(home: string): Settings {
  mustHome(home)
  const raw = readJsonIfExists(homePaths(home).settings) as Partial<Settings> | undefined
  if (raw === undefined) return { ...DEFAULT_SETTINGS }
  return {
    $schema: 'equipmock/settings@1',
    activeGroup: typeof raw.activeGroup === 'string' ? raw.activeGroup : DEFAULT_SETTINGS.activeGroup,
    mockEnabled: typeof raw.mockEnabled === 'boolean' ? raw.mockEnabled : true,
  }
}

export async function writeSettings(home: string, next: Settings): Promise<void> {
  mustHome(home)
  await atomicWriteFile(homePaths(home).settings, writeJsonPretty({ $schema: 'equipmock/settings@1', ...next }))
}

export async function setActiveGroup(home: string, group: string): Promise<void> {
  assertValidGroupName(group)
  mustHome(home)
  if (!fs.existsSync(groupDir(home, group))) {
    throw new HomeError(`配置组不存在：${group}`)
  }
  const settings = readSettings(home)
  await writeSettings(home, { ...settings, activeGroup: group })
}

// ---------------- groups ----------------

const SUBGROUP_EXT = '.json'

function listSubGroupFiles(dir: string): string[] {
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .filter(
      (e) =>
        e.isFile() &&
        e.name.endsWith(SUBGROUP_EXT) &&
        !e.name.includes('.tmp') &&
        !e.name.includes('.bak'),
    )
    .map((e) => e.name)
    .sort((a, b) => a.localeCompare(b, 'en'))
}

export function listGroups(home: string): GroupInfo[] {
  mustHome(home)
  const groupsDir = homePaths(home).groupsDir
  if (!fs.existsSync(groupsDir)) return []
  return fs
    .readdirSync(groupsDir, { withFileTypes: true })
    .filter((e) => e.isDirectory() && !e.name.includes('.tmp'))
    .map((e) => e.name)
    .sort((a, b) => a.localeCompare(b, 'en'))
    .map((name) => {
      const files = listSubGroupFiles(groupDir(home, name))
      const subGroups = files.map((file) => {
        let mockCount = -1
        let displayName = file.slice(0, -SUBGROUP_EXT.length)
        try {
          const doc = readJsonIfExists(subGroupFile(home, name, file)) as SubGroupDoc | undefined
          if (doc && typeof doc === 'object') {
            if (Array.isArray(doc.mocks)) mockCount = doc.mocks.length
            if (typeof doc.name === 'string' && doc.name.length > 0) displayName = doc.name
          }
        } catch {
          mockCount = -1
        }
        return { file, name: displayName, mockCount }
      })
      return { name, subGroups }
    })
}

export function createGroup(home: string, name: string): void {
  assertValidGroupName(name)
  mustHome(home)
  const dir = groupDir(home, name)
  if (fs.existsSync(dir)) throw new HomeError(`配置组已存在：${name}`)
  fs.mkdirSync(dir, { recursive: true })
}

export function copyGroup(home: string, from: string, to: string): void {
  assertValidGroupName(from)
  assertValidGroupName(to)
  mustHome(home)
  const src = groupDir(home, from)
  if (!fs.existsSync(src)) throw new HomeError(`源配置组不存在：${from}`)
  const dst = groupDir(home, to)
  if (fs.existsSync(dst)) throw new HomeError(`目标配置组已存在：${to}`)
  fs.cpSync(src, dst, { recursive: true })
}

export function deleteGroup(home: string, name: string): void {
  assertValidGroupName(name)
  mustHome(home)
  const dir = groupDir(home, name)
  if (!fs.existsSync(dir)) throw new HomeError(`配置组不存在：${name}`)
  if (readSettings(home).activeGroup === name) {
    throw new HomeError('不能删除当前生效组，请先切换到其他组')
  }
  fs.rmSync(dir, { recursive: true, force: true })
}

export async function renameGroup(home: string, from: string, to: string): Promise<void> {
  assertValidGroupName(from)
  assertValidGroupName(to)
  mustHome(home)
  const src = groupDir(home, from)
  if (!fs.existsSync(src)) throw new HomeError(`配置组不存在：${from}`)
  const dst = groupDir(home, to)
  if (fs.existsSync(dst)) throw new HomeError(`目标名称已存在：${to}`)
  fs.renameSync(src, dst)
  // 同步 settings 引用（06 §5.2）
  const settings = readSettings(home)
  if (settings.activeGroup === from) {
    await writeSettings(home, { ...settings, activeGroup: to })
  }
}

// ---------------- subgroup ----------------

export function readSubGroup(home: string, group: string, file: string): SubGroupDoc {
  assertValidSubGroupFileName(file)
  mustHome(home)
  const abs = subGroupFile(home, group, file)
  let doc: unknown
  try {
    doc = readJsonIfExists(abs)
  } catch (e) {
    throw new HomeError(`读取小分组失败：${(e as Error).message}`)
  }
  if (doc === undefined) throw new HomeError(`小分组不存在：${group}/${file}`)
  return doc as SubGroupDoc
}

export async function writeSubGroup(
  home: string,
  group: string,
  file: string,
  doc: unknown,
): Promise<WriteResult> {
  assertValidSubGroupFileName(file)
  mustHome(home)
  // 主进程兜底校验：渲染层已校验过，这里保证契约文件永远合法（04 §3）
  const result = validateSubGroupDoc(doc)
  if (!result.ok) {
    return { ok: false, errors: result.errors }
  }
  const normalized: SubGroupDoc = { ...(doc as SubGroupDoc), $schema: SUBGROUP_SCHEMA_ID }
  const abs = subGroupFile(home, group, file)
  await atomicWriteFile(abs, writeJsonPretty(normalized))
  return { ok: true, errors: [] }
}

export function createSubGroup(home: string, group: string, name: string): { file: string } {
  const file = `${name}${SUBGROUP_EXT}`
  assertValidSubGroupFileName(file)
  mustHome(home)
  if (!fs.existsSync(groupDir(home, group))) throw new HomeError(`配置组不存在：${group}`)
  const abs = subGroupFile(home, group, file)
  if (fs.existsSync(abs)) throw new HomeError(`小分组已存在：${name}`)
  const doc: SubGroupDoc = { $schema: SUBGROUP_SCHEMA_ID, name, mocks: [] }
  fs.writeFileSync(abs, writeJsonPretty(doc), 'utf8')
  return { file }
}

export function readAllSubGroups(home: string, group: string): { file: string; doc: SubGroupDoc }[] {
  mustHome(home)
  return listSubGroupFiles(groupDir(home, group)).map((file) => ({
    file,
    doc: readSubGroup(home, group, file),
  }))
}

// ---------------- plugin registry ----------------

export function readRegistry(home: string): PluginRegistryDoc {
  mustHome(home)
  const raw = readJsonIfExists(homePaths(home).registry) as Partial<PluginRegistryDoc> | undefined
  const plugins = Array.isArray(raw?.plugins) ? raw!.plugins : []
  return { $schema: 'equipmock/plugin-registry@1', plugins: plugins as PluginEntry[] }
}

export async function writeRegistry(home: string, doc: PluginRegistryDoc): Promise<void> {
  mustHome(home)
  await atomicWriteFile(
    homePaths(home).registry,
    writeJsonPretty({ $schema: 'equipmock/plugin-registry@1', plugins: doc.plugins }),
  )
}

export function readState(home: string): StateDoc | null {
  mustHome(home)
  try {
    return (readJsonIfExists(homePaths(home).state) as StateDoc | undefined) ?? null
  } catch {
    return null
  }
}

export function readLogTail(home: string, lines = 200): string[] {
  mustHome(home)
  const logPath = homePaths(home).agentLog
  try {
    const stat = fs.statSync(logPath)
    // 只读最后 512KB，避免日志膨胀拖慢 UI
    const readFrom = Math.max(0, stat.size - 512 * 1024)
    const fd = fs.openSync(logPath, 'r')
    try {
      const length = stat.size - readFrom
      const buf = Buffer.alloc(length)
      fs.readSync(fd, buf, 0, length, readFrom)
      const text = buf.toString('utf8')
      const all = text.split(/\r?\n/)
      const tail = all.slice(-lines - 1, all.at(-1) === '' ? -1 : undefined)
      return tail.slice(-lines)
    } finally {
      fs.closeSync(fd)
    }
  } catch {
    return []
  }
}

export interface PluginListView {
  rows: PluginRow[]
  orphanJars: OrphanJar[]
}

export function listPluginRows(home: string): PluginListView {
  mustHome(home)
  const h = homePaths(home)
  const registry = readRegistry(home)
  const state = readState(home)
  const stateById = new Map((state?.plugins ?? []).map((p) => [p.id, p]))

  const rows: PluginRow[] = registry.plugins.map((entry) => {
    const jarAbs = path.join(h.pluginsDir, entry.jar)
    let jarExists = false
    let jarSizeBytes = 0
    try {
      const stat = fs.statSync(jarAbs)
      jarExists = stat.isFile()
      jarSizeBytes = stat.size
    } catch {
      jarExists = false
    }
    const runtime = stateById.get(entry.id)
    let runtimeState: PluginRuntimeState | null = runtime?.state ?? null
    if (!jarExists) runtimeState = 'MISSING'
    return {
      id: entry.id,
      alias: entry.alias ?? '',
      jar: entry.jar,
      jarExists,
      jarSizeBytes,
      enabled: entry.enabled,
      importedAt: entry.importedAt ?? '',
      note: entry.note ?? '',
      runtimeState,
      runtimeVersion: runtime?.version ?? null,
      runtimeError: runtime?.error ?? null,
    }
  })

  const registered = new Set(registry.plugins.map((p) => p.jar))
  const orphanJars: OrphanJar[] = []
  try {
    for (const name of fs.readdirSync(h.pluginsDir)) {
      if (!name.toLowerCase().endsWith('.jar')) continue
      if (registered.has(name)) continue
      if (name.includes('.tmp')) continue
      let sizeBytes = 0
      try {
        sizeBytes = fs.statSync(path.join(h.pluginsDir, name)).size
      } catch {
        /* ignore */
      }
      orphanJars.push({ jar: name, sizeBytes })
    }
  } catch {
    /* plugins 目录不存在 */
  }
  return { rows, orphanJars }
}

// ---------------- lastError ----------------

export function readLastError(home: string): StateLastError | null {
  return readState(home)?.lastError ?? null
}

export type { ValidationIssue }
