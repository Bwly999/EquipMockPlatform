/**
 * 最小 zip（jar）解析：只读 META-INF/MANIFEST.MF，零依赖手写 central directory 定位（06 §2 插件导入）。
 * 支持 STORED(0) 与 DEFLATE(8)；不支持 zip64（插件 jar 远小于 4G）。
 */
import { inflateRawSync } from 'node:zlib'
import type { ManifestInfo } from '../../src/lib/types'

const EOCD_SIG = 0x06054b50
const CEN_SIG = 0x02014b50
const LOC_SIG = 0x04034b50

export interface ZipEntry {
  name: string
  method: number
  compressedSize: number
  uncompressedSize: number
  localHeaderOffset: number
}

/** 解析 central directory，返回全部条目（仅元数据） */
export function listZipEntries(buf: Buffer): ZipEntry[] {
  // 从尾部找 EOCD（固定 22 字节，前面可能有注释，最长 65535）
  let eocd = -1
  const minOffset = Math.max(0, buf.length - 22 - 65535)
  for (let i = buf.length - 22; i >= minOffset; i--) {
    if (buf.readUInt32LE(i) === EOCD_SIG) {
      eocd = i
      break
    }
  }
  if (eocd === -1) throw new Error('jar 文件损坏：找不到 zip 结束记录（EOCD）')
  const entryCount = buf.readUInt16LE(eocd + 10)
  const cdOffset = buf.readUInt32LE(eocd + 16)
  if (cdOffset === 0xffffffff || entryCount === 0xffff) {
    throw new Error('暂不支持 zip64 格式的 jar')
  }

  const entries: ZipEntry[] = []
  let p = cdOffset
  for (let n = 0; n < entryCount; n++) {
    if (p + 46 > buf.length || buf.readUInt32LE(p) !== CEN_SIG) {
      throw new Error('jar 文件损坏：central directory 校验失败')
    }
    const method = buf.readUInt16LE(p + 10)
    const compressedSize = buf.readUInt32LE(p + 20)
    const uncompressedSize = buf.readUInt32LE(p + 24)
    const nameLen = buf.readUInt16LE(p + 28)
    const extraLen = buf.readUInt16LE(p + 30)
    const commentLen = buf.readUInt16LE(p + 32)
    const localHeaderOffset = buf.readUInt32LE(p + 42)
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen)
    entries.push({ name, method, compressedSize, uncompressedSize, localHeaderOffset })
    p += 46 + nameLen + extraLen + commentLen
  }
  return entries
}

/** 从 jar 中提取单个条目内容 */
export function extractZipEntry(buf: Buffer, entry: ZipEntry): Buffer {
  const lo = entry.localHeaderOffset
  if (lo + 30 > buf.length || buf.readUInt32LE(lo) !== LOC_SIG) {
    throw new Error('jar 文件损坏：local file header 校验失败')
  }
  const nameLen = buf.readUInt16LE(lo + 26)
  const extraLen = buf.readUInt16LE(lo + 28)
  const dataStart = lo + 30 + nameLen + extraLen
  const raw = buf.subarray(dataStart, dataStart + entry.compressedSize)
  if (entry.method === 0) return raw
  if (entry.method === 8) return inflateRawSync(raw)
  throw new Error(`不支持的压缩方法：${entry.method}`)
}

/** 读取 jar 内 META-INF/MANIFEST.MF 文本 */
export function readJarManifestText(buf: Buffer): string {
  const entries = listZipEntries(buf)
  const target =
    entries.find((e) => e.name === 'META-INF/MANIFEST.MF') ??
    entries.find((e) => e.name.toLowerCase() === 'meta-inf/manifest.mf')
  if (!target) throw new Error('jar 中缺少 META-INF/MANIFEST.MF（不是 EquipMock 插件）')
  return extractZipEntry(buf, target).toString('utf8')
}

/**
 * 解析 MANIFEST 主段属性。
 * 规则：`Key: Value` 行；以单个空格开头的行是上一行的续行；多个段以空行分隔，只取第一段（主属性）。
 */
export function parseManifest(text: string): Record<string, string> {
  const lines = text.split(/\r?\n/)
  const attrs: Record<string, string> = {}
  let inMain = true
  let lastKey: string | null = null

  for (const line of lines) {
    if (line === '') {
      if (lastKey !== null || Object.keys(attrs).length > 0) inMain = false
      lastKey = null
      continue
    }
    if (!inMain) continue
    if (line.startsWith(' ') || line.startsWith('\t')) {
      if (lastKey !== null) attrs[lastKey] += line.slice(1)
      continue
    }
    const idx = line.indexOf(':')
    if (idx <= 0) continue
    const key = line.slice(0, idx).trim()
    const value = line.slice(idx + 1).replace(/^\s+/, '')
    attrs[key] = value
    lastKey = key
  }
  return attrs
}

/** 提取插件清单必需属性；缺 Plugin-Id / Plugin-Version 时抛错（拦截导入） */
export function extractPluginManifest(text: string): ManifestInfo {
  const attrs = parseManifest(text)
  // Manifest 属性名大小写不敏感（规范），做一次归一查找
  const get = (suffix: string): string | undefined => {
    const hit = Object.entries(attrs).find(([k]) => k.toLowerCase() === `plugin-${suffix}`)
    return hit?.[1].trim() || undefined
  }
  const pluginId = get('id')
  const pluginVersion = get('version')
  if (!pluginId) throw new Error('MANIFEST 缺少 Plugin-Id，无法导入')
  if (!pluginVersion) throw new Error('MANIFEST 缺少 Plugin-Version，无法导入')
  if (!/^[a-z0-9][a-z0-9-]*$/.test(pluginId)) {
    throw new Error(`Plugin-Id 格式非法（应为 ^[a-z0-9][a-z0-9-]*$）：${pluginId}`)
  }
  return {
    pluginId,
    pluginVersion,
    pluginRequires: get('requires') ?? null,
    pluginDescription: get('description') ?? null,
  }
}

/** 从 jar Buffer 直接解析插件 manifest */
export function extractPluginManifestFromJar(buf: Buffer): ManifestInfo {
  return extractPluginManifest(readJarManifestText(buf))
}

/**
 * 简化语义版本比较：a 与 b 形如 1.2.3 / 1.2.0-SNAPSHOT。
 * @returns a > b → 1；a < b → -1；无法比较/相等 → 0
 */
export function compareLooseSemver(a: string, b: string): number {
  const pa = /^(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(a.trim())
  const pb = /^(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(b.trim())
  if (!pa || !pb) return 0
  for (let i = 1; i <= 3; i++) {
    const x = Number(pa[i] ?? 0)
    const y = Number(pb[i] ?? 0)
    if (x !== y) return x > y ? 1 : -1
  }
  return 0
}
