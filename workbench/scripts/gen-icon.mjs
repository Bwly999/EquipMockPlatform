/**
 * 生成应用图标（无设计资产时的零依赖方案）：
 * 256x256 PNG（深蓝底 + 白色 M 字造型色块）→ 包装为 ico（PNG-in-ICO，Vista+ 均支持）。
 */
import { deflateSync } from 'node:zlib'
import { writeFileSync, mkdirSync } from 'node:fs'
import * as path from 'node:path'

const SIZE = 256
const BG = [29, 78, 216, 255] // brand-700
const FG = [255, 255, 255, 255]
const px = new Uint8Array(SIZE * SIZE * 4)

const setPx = (x, y, [r, g, b, a]) => {
  if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return
  const i = (y * SIZE + x) * 4
  px[i] = r
  px[i + 1] = g
  px[i + 2] = b
  px[i + 3] = a
}

// 圆角背景
for (let y = 0; y < SIZE; y++) {
  for (let x = 0; x < SIZE; x++) {
    const r = 44
    const cx = Math.max(r, Math.min(SIZE - 1 - r, x))
    const cy = Math.max(r, Math.min(SIZE - 1 - r, y))
    const d = Math.hypot(x - cx, y - cy)
    setPx(x, y, d <= r ? BG : [0, 0, 0, 0])
  }
}

// "M" 字造型：两条斜线 + 中间谷底（用矩形笔画近似）
const stroke = 34
const top = 74
const bottom = 182
const leftX = 64
const rightX = 192
const midX = 128
const drawLine = (x1, y1, x2, y2) => {
  const steps = Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)) * 2)
  for (let s = 0; s <= steps; s++) {
    const t = s / steps
    const cx = x1 + (x2 - x1) * t
    const cy = y1 + (y2 - y1) * t
    for (let oy = -stroke / 2; oy < stroke / 2; oy++) {
      for (let ox = -stroke / 2; ox < stroke / 2; ox++) {
        setPx(Math.round(cx + ox), Math.round(cy + oy), FG)
      }
    }
  }
}
drawLine(leftX, top, midX, bottom)
drawLine(midX, bottom, rightX, top)

// PNG 编码
const crcTable = new Uint32Array(256)
for (let n = 0; n < 256; n++) {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  crcTable[n] = c >>> 0
}
const crc32 = (buf) => {
  let c = 0xffffffff
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const chunk = (type, data) => {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}

const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(SIZE, 0)
ihdr.writeUInt32BE(SIZE, 4)
ihdr[8] = 8 // bit depth
ihdr[9] = 6 // color type RGBA
// 每行前加 filter 字节 0
const raw = Buffer.alloc(SIZE * (SIZE * 4 + 1))
for (let y = 0; y < SIZE; y++) {
  raw[y * (SIZE * 4 + 1)] = 0
  Buffer.from(px.buffer, y * SIZE * 4, SIZE * 4).copy(raw, y * (SIZE * 4 + 1) + 1)
}
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
])

// ICO（单张 PNG）
const ico = Buffer.alloc(22 + png.length)
ico.writeUInt16LE(0, 0)
ico.writeUInt16LE(1, 2) // type icon
ico.writeUInt16LE(1, 4) // count
ico[6] = 0 // 0 = 256
ico[7] = 0
ico[8] = 0 // colors
ico[9] = 0
ico.writeUInt16LE(1, 10) // planes
ico.writeUInt16LE(32, 12) // bpp
ico.writeUInt32LE(png.length, 14)
ico.writeUInt32LE(22, 18)
png.copy(ico, 22)

const outDir = path.resolve(import.meta.dirname, '..', 'resources')
mkdirSync(outDir, { recursive: true })
writeFileSync(path.join(outDir, 'icon.png'), png)
writeFileSync(path.join(outDir, 'icon.ico'), ico)
console.log(`✓ 已生成 resources/icon.png (${png.length} B) 与 resources/icon.ico (${ico.length} B)`)
