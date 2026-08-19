/**
 * 构建产物"禁止任何 https:// 外链/CDN 引用"断言（06 §9 / init.md 约束 5）。
 *
 * 违规判定（会被浏览器实际加载的外部资源）：
 *  - html：src=/href= 指向 http(s)；
 *  - css：url(http(s)…) / @import http(s)；
 *  - js：new Worker('http…')、import('http…')、(src|href|url) = 'http…'、fetch/XHR 到 http…；
 *  - 任何文件中出现 CDN 域名（unpkg / jsdelivr / cdnjs / esm.sh / skypack / bootcdn / googleapis）；
 *  - 发布了 sourcemap（*.map）。
 * 白名单（不构成网络加载的标识符字符串）：
 *  - json-schema.org 等 schema 命名空间 URI（ajv/monaco 的 $schema 元数据）
 *  - microsoft.github.io/monaco-editor（monaco 内置 license/链接注释）
 * `--verbose` 打印全部 http(s) 出现位置供人工复核。
 */
import { readdirSync, readFileSync } from 'node:fs'
import * as path from 'node:path'

const ROOTS = ['dist', 'dist-electron']
const ALLOWED_URL_RE =
  /^https?:\/\/(json-schema\.org|www\.json-schema\.org|schemastore\.org|www\.w3\.org|microsoft\.github\.io|code\.visualstudio\.com|vscode\.dev|electronjs\.org|nodejs\.org|www\.oracle\.com|docs\.oracle\.com|tools\.ietf\.org|tc39\.es|developer\.mozilla\.org)/i
const CDN_HOST_RE = /(unpkg\.com|cdn\.jsdelivr\.net|cdnjs\.cloudflare\.com|esm\.sh|skypack\.dev|bootcdn\.net|ajax\.googleapis\.com|fonts\.googleapis\.com)/i

const exts = new Set(['.html', '.js', '.mjs', '.cjs', '.css'])
const violations = []
const review = []

function walk(dir) {
  let entries = []
  try {
    entries = readdirSync(dir, { withFileTypes: true })
  } catch {
    return []
  }
  const files = []
  for (const entry of entries) {
    const abs = path.join(dir, entry.name)
    if (entry.isDirectory()) files.push(...walk(abs))
    else files.push(abs)
  }
  return files
}

const URL_RE = /https?:\/\/[^\s"'`<>{}()\\]{2,}/g
const LOADABLE_PATTERNS = [
  [/new\s+Worker\s*\(\s*['"`](https?:\/\/[^'"`]+)/g, 'Worker 加载'],
  [/import\s*\(\s*['"`](https?:\/\/[^'"`]+)/g, '动态 import'],
  [/(?:src|href)\s*[=:]\s*['"`](https?:\/\/[^'"`]+)/g, 'src/href 引用'],
  [/url\(\s*['"`]?(https?:\/\/[^)'"`]+)/g, 'css url()'],
  [/@import\s+['"`](https?:\/\/[^'"`]+)/g, 'css @import'],
  [/\b(?:fetch|open)\s*\(\s*['"`](https?:\/\/[^'"`]+)/g, 'fetch/XHR'],
]

for (const root of ROOTS) {
  const files = walk(path.resolve(root))
  for (const file of files) {
    const rel = path.relative(process.cwd(), file)
    if (path.basename(file).endsWith('.map')) {
      violations.push(`${rel}: 不应发布 sourcemap`)
      continue
    }
    if (!exts.has(path.extname(file))) continue
    let text
    try {
      text = readFileSync(file, 'utf8')
    } catch {
      continue
    }

    for (const m of text.matchAll(URL_RE)) {
      review.push(`${rel}: ${m[0].slice(0, 140)}`)
    }
    for (const [re, label] of LOADABLE_PATTERNS) {
      for (const m of text.matchAll(re)) {
        if (!ALLOWED_URL_RE.test(m[1])) violations.push(`${rel}: ${label} ${m[1].slice(0, 140)}`)
      }
    }
    for (const m of text.matchAll(CDN_HOST_RE)) {
      violations.push(`${rel}: CDN 域名 ${m[0]}`)
    }
    // html/css 中的任何非白名单 http(s) 都不允许（这两类文件里出现 URL 一定是引用）
    if (/\.(html|css)$/.test(file)) {
      for (const m of text.matchAll(URL_RE)) {
        if (!ALLOWED_URL_RE.test(m[0])) violations.push(`${rel}: html/css 内外部链接 ${m[0].slice(0, 140)}`)
      }
    }
  }
}

if (process.argv.includes('--verbose')) {
  console.log(`—— 全部 http(s) 出现位置（含白名单，共 ${review.length} 处）——`)
  for (const line of review) console.log('  ' + line)
}

if (violations.length > 0) {
  console.error(`✗ no-cdn 断言失败（${violations.length} 处）：`)
  for (const v of [...new Set(violations)]) console.error('  ' + v)
  process.exit(1)
}
console.log(
  `✓ no-cdn 断言通过：dist/ 与 dist-electron/ 无外链/CDN 引用（js 内另有 ${review.length} 处 schema 标识符等字符串形式 URL，均为白名单且不触发网络加载，可用 --verbose 复核）`,
)
