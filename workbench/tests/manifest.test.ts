/** 主进程纯函数测试：jar/manifest 解析（零依赖 central-directory 解析器） */
import { describe, expect, it } from 'vitest'
import {
  compareLooseSemver,
  extractPluginManifest,
  extractPluginManifestFromJar,
  extractZipEntry,
  listZipEntries,
  parseManifest,
  readJarManifestText,
} from '../electron/lib/manifest'
import { SAMPLE_MANIFEST, makePluginJar, makeZip } from './helpers/makeJar'

describe('parseManifest', () => {
  it('解析主段属性（CRLF）', () => {
    const attrs = parseManifest(SAMPLE_MANIFEST)
    expect(attrs['Plugin-Id']).toBe('mock-cabinet')
    expect(attrs['Plugin-Version']).toBe('1.0.0')
    expect(attrs['Plugin-Requires']).toBe('1.0.0')
    expect(attrs['Plugin-Description']).toBe('机柜电源 Mock 示例插件')
  })

  it('续行（行首单空格）拼接长值', () => {
    const text = ['Manifest-Version: 1.0', 'Plugin-Description: 机柜', ' 电源插件', ' 支持多行', ''].join('\r\n')
    expect(parseManifest(text)['Plugin-Description']).toBe('机柜电源插件支持多行')
  })

  it('只取第一段（主属性），忽略个体段', () => {
    const text = [
      'Manifest-Version: 1.0',
      'Plugin-Id: a',
      '',
      'Name: com/Foo.class',
      'Plugin-Id: should-not-appear',
      '',
    ].join('\n')
    const attrs = parseManifest(text)
    expect(attrs['Plugin-Id']).toBe('a')
    expect(attrs['Name']).toBeUndefined()
  })
})

describe('extractPluginManifest', () => {
  it('缺 Plugin-Id 报错拦截', () => {
    expect(() => extractPluginManifest('Manifest-Version: 1.0\r\nPlugin-Version: 1.0.0\r\n')).toThrow(/Plugin-Id/)
  })

  it('缺 Plugin-Version 报错拦截', () => {
    expect(() => extractPluginManifest('Manifest-Version: 1.0\r\nPlugin-Id: x\r\n')).toThrow(/Plugin-Version/)
  })

  it('id 格式非法报错', () => {
    expect(() => extractPluginManifest('Plugin-Id: Bad_Id\r\nPlugin-Version: 1.0.0\r\n')).toThrow(/格式非法/)
  })

  it('属性名大小写不敏感', () => {
    const m = extractPluginManifest('plugin-id: mock-radar\r\nplugin-version: 2.1.0\r\n')
    expect(m.pluginId).toBe('mock-radar')
    expect(m.pluginVersion).toBe('2.1.0')
    expect(m.pluginRequires).toBeNull()
  })
})

describe('zip 解析（jar fixture）', () => {
  it('列出条目并解压 DEFLATE 的 MANIFEST', () => {
    const jar = makePluginJar(SAMPLE_MANIFEST)
    const entries = listZipEntries(jar)
    expect(entries.map((e) => e.name)).toContain('META-INF/MANIFEST.MF')
    const text = readJarManifestText(jar)
    expect(text).toContain('Plugin-Id: mock-cabinet')
  })

  it('STORED 条目读取', () => {
    const zip = makeZip([{ name: 'a.txt', data: 'hello 机柜' }])
    const entries = listZipEntries(zip)
    const a = entries.find((e) => e.name === 'a.txt')!
    expect(a.method).toBe(0)
    expect(extractZipEntry(zip, a).toString('utf8')).toBe('hello 机柜')
  })

  it('中文文件名（UTF-8 flag）', () => {
    const zip = makeZip([{ name: '中文/说明.txt', data: '内容' }])
    expect(listZipEntries(zip).map((e) => e.name)).toContain('中文/说明.txt')
  })

  it('无 MANIFEST 的 jar 报错', () => {
    const zip = makeZip([{ name: 'com/A.class', data: Buffer.from([1, 2, 3]) }])
    expect(() => readJarManifestText(zip)).toThrow(/MANIFEST/)
  })

  it('损坏（截断）的 jar 报 EOCD 错误', () => {
    const jar = makePluginJar(SAMPLE_MANIFEST)
    const truncated = jar.subarray(0, Math.floor(jar.length / 2))
    expect(() => listZipEntries(truncated)).toThrow(/EOCD|central/)
  })

  it('extractPluginManifestFromJar 端到端', () => {
    const m = extractPluginManifestFromJar(makePluginJar(SAMPLE_MANIFEST))
    expect(m).toMatchObject({
      pluginId: 'mock-cabinet',
      pluginVersion: '1.0.0',
      pluginRequires: '1.0.0',
      pluginDescription: '机柜电源 Mock 示例插件',
    })
  })
})

describe('compareLooseSemver', () => {
  it('常规比较', () => {
    expect(compareLooseSemver('1.0.0', '1.0.0')).toBe(0)
    expect(compareLooseSemver('1.2.0', '1.10.0')).toBe(-1)
    expect(compareLooseSemver('2.0', '1.9.9')).toBe(1)
    expect(compareLooseSemver('1.0.0-SNAPSHOT', '1.0.0')).toBe(0) // 后缀不参与硬比较
  })
})

