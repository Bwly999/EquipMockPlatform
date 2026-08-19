/**
 * 版本同步（06 §9：icon/版本与 Java 侧同源）：
 * 读取根 pom.xml 的父模块版本，写入 workbench/package.json。
 */
import { readFileSync, writeFileSync } from 'node:fs'
import * as path from 'node:path'

const root = path.resolve(import.meta.dirname, '..')
const pomPath = path.join(root, '..', 'pom.xml')
const pkgPath = path.join(root, 'package.json')

let mavenVersion = null
try {
  const pom = readFileSync(pomPath, 'utf8')
  const m = /<parent>[\s\S]*?<artifactId>equipmock-parent<\/artifactId>\s*<version>([^<]+)<\/version>/.exec(pom) ?? /<artifactId>equipmock-parent<\/artifactId>\s*<version>([^<]+)<\/version>/.exec(pom)
  mavenVersion = m?.[1] ?? null
} catch {
  console.log('未找到 ../pom.xml，跳过版本同步')
  process.exit(0)
}

if (!mavenVersion) {
  console.log('pom.xml 中未解析到 equipmock-parent 版本，跳过')
  process.exit(0)
}

const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'))
if (pkg.version === mavenVersion) {
  console.log(`版本已同步：${mavenVersion}`)
  process.exit(0)
}
pkg.version = mavenVersion
writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n')
console.log(`package.json 版本已同步为 ${mavenVersion}（electron-builder 产物名随之变化）`)
