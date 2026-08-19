/** 测试用最小 zip/jar 生成器：STORED 与 DEFLATE 条目 + 正确 CRC（验证主进程解析器的读写两端） */
import { deflateRawSync } from 'node:zlib'

const CRC_TABLE = new Uint32Array(256)
for (let n = 0; n < 256; n++) {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  CRC_TABLE[n] = c >>> 0
}

export function crc32(buf: Buffer): number {
  let c = 0xffffffff
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

export interface ZipEntrySpec {
  name: string
  data: Buffer | string
  /** 0=STORED（默认），8=DEFLATE */
  method?: 0 | 8
}

interface Staged {
  name: string
  method: number
  crc: number
  compressed: Buffer
  uncompressedSize: number
  offset: number
}

export function makeZip(entries: ZipEntrySpec[]): Buffer {
  const parts: Buffer[] = []
  const staged: Staged[] = []
  let offset = 0

  for (const entry of entries) {
    const raw = typeof entry.data === 'string' ? Buffer.from(entry.data, 'utf8') : entry.data
    const method = entry.method ?? 0
    const compressed = method === 8 ? deflateRawSync(raw) : raw
    const crc = crc32(raw)
    const nameBuf = Buffer.from(entry.name, 'utf8')

    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4) // version needed
    local.writeUInt16LE(0x0800, 6) // flags: UTF-8 names
    local.writeUInt16LE(method, 8)
    local.writeUInt16LE(0, 10) // time
    local.writeUInt16LE(0x21, 12) // date（合法固定值）
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(compressed.length, 18)
    local.writeUInt32LE(raw.length, 22)
    local.writeUInt16LE(nameBuf.length, 26)
    local.writeUInt16LE(0, 28)

    staged.push({ name: entry.name, method, crc, compressed, uncompressedSize: raw.length, offset })
    parts.push(local, nameBuf, compressed)
    offset += local.length + nameBuf.length + compressed.length
  }

  const cdStart = offset
  for (const s of staged) {
    const nameBuf = Buffer.from(s.name, 'utf8')
    const cd = Buffer.alloc(46)
    cd.writeUInt32LE(0x02014b50, 0)
    cd.writeUInt16LE(20, 4) // version made by
    cd.writeUInt16LE(20, 6) // version needed
    cd.writeUInt16LE(0x0800, 8)
    cd.writeUInt16LE(s.method, 10)
    cd.writeUInt16LE(0, 12)
    cd.writeUInt16LE(0x21, 14)
    cd.writeUInt32LE(s.crc, 16)
    cd.writeUInt32LE(s.compressed.length, 20)
    cd.writeUInt32LE(s.uncompressedSize, 24)
    cd.writeUInt16LE(nameBuf.length, 28)
    cd.writeUInt16LE(0, 30)
    cd.writeUInt16LE(0, 32)
    cd.writeUInt16LE(0, 34)
    cd.writeUInt16LE(0, 36)
    cd.writeUInt32LE(0, 38)
    cd.writeUInt32LE(s.offset, 42)
    parts.push(cd, nameBuf)
    offset += cd.length + nameBuf.length
  }

  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(0, 4)
  eocd.writeUInt16LE(0, 6)
  eocd.writeUInt16LE(staged.length, 8)
  eocd.writeUInt16LE(staged.length, 10)
  eocd.writeUInt32LE(offset - cdStart, 12)
  eocd.writeUInt32LE(cdStart, 16)
  eocd.writeUInt16LE(0, 20)
  parts.push(eocd)

  return Buffer.concat(parts)
}

/** 标准插件 jar fixture（可带自定义 manifest 行） */
export function makePluginJar(manifest: string): Buffer {
  return makeZip([
    { name: 'META-INF/', data: '' },
    { name: 'META-INF/MANIFEST.MF', data: manifest, method: 8 },
    { name: 'com/equipmock/plugin/Dummy.class', data: Buffer.from([0xca, 0xfe, 0xba, 0xbe]) },
  ])
}

export const SAMPLE_MANIFEST = [
  'Manifest-Version: 1.0',
  'Plugin-Id: mock-cabinet',
  'Plugin-Version: 1.0.0',
  'Plugin-Requires: 1.0.0',
  'Plugin-Description: 机柜电源 Mock 示例插件',
  '',
].join('\r\n')
