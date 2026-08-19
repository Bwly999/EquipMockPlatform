/** 主进程 fs 数据层测试（真实临时目录）：骨架初始化 / 原子写 / 滚动日志合并 / state 半截读容错 */
import { mkdtempSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync, utimesSync } from 'node:fs'
import { tmpdir } from 'node:os'
import * as path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { initSkeleton } from '../electron/lib/equipmockHome'
import { atomicWriteFile } from '../electron/lib/atomicWrite'
import { homePaths, subGroupFile } from '../electron/lib/paths'
import * as store from '../electron/lib/store'

const tmpRoot = mkdtempSync(path.join(tmpdir(), 'equipmock-wb-'))
let home = ''

beforeEach(() => {
  home = path.join(tmpRoot, `home-${Math.random().toString(36).slice(2, 8)}`)
  mkdirSync(home, { recursive: true })
})

afterEach(() => {
  try {
    rmSync(tmpRoot, { recursive: true, force: true })
  } catch {
    /* Windows 句柄延迟释放，忽略 */
  }
  mkdirSync(tmpRoot, { recursive: true })
})

describe('骨架初始化', () => {
  it('生成 04 §1 结构与两个示例组；重复初始化不覆盖', async () => {
    const r1 = await initSkeleton(home)
    expect(r1.alreadyInitialized).toBe(false)
    const h = homePaths(home)
    expect(() => readFileSync(h.settings, 'utf8')).not.toThrow()
    expect(() => readFileSync(path.join(h.pluginsDir, 'plugin-registry.json'), 'utf8')).not.toThrow()
    for (const g of ['default', 'fault-sim']) {
      expect(() => readFileSync(subGroupFile(home, g, 'cabinet.json'), 'utf8')).not.toThrow()
      expect(() => readFileSync(subGroupFile(home, g, 'radar.json'), 'utf8')).not.toThrow()
    }

    // 幂等：已有 settings.json 的目录不再写入
    writeFileSync(subGroupFile(home, 'default', 'cabinet.json'), '{"name":"touched","mocks":[]}', 'utf8')
    const r2 = await initSkeleton(home)
    expect(r2.alreadyInitialized).toBe(true)
    expect(JSON.parse(readFileSync(subGroupFile(home, 'default', 'cabinet.json'), 'utf8')).name).toBe('touched')
  })

  it('骨架内容过校验且组树可列出', async () => {
    await initSkeleton(home)
    const groups = store.listGroups(home)
    expect(groups.map((g) => g.name).sort()).toEqual(['default', 'fault-sim'])
    const cabinet = groups.find((g) => g.name === 'default')!.subGroups.find((s) => s.file === 'cabinet.json')!
    expect(cabinet.mockCount).toBe(2)
    expect(store.readSettings(home)).toMatchObject({ activeGroup: 'default', mockEnabled: true })
  })
})

describe('原子写', () => {
  it('tmp+rename 落盘最终内容且不残留 tmp 文件', async () => {
    await initSkeleton(home)
    const target = subGroupFile(home, 'default', 'radar.json')
    await atomicWriteFile(target, '{"name":"atomic","mocks":[]}')
    expect(readFileSync(target, 'utf8')).toBe('{"name":"atomic","mocks":[]}')
    expect(readdirSync(path.dirname(target)).some((f) => f.includes('.tmp'))).toBe(false)
  })

  it('subgroup:write 非法文档被拒、合法文档落盘并补 $schema', async () => {
    await initSkeleton(home)
    const bad = await store.writeSubGroup(home, 'default', 'radar.json', {
      name: 'bad',
      mocks: [{ class: 'a', method: 'm', enabled: true, rules: [{ matchType: 'PATTERN_MATCH', argsPattern: ['[oops'], action: { type: 'VOID' } }] }],
    })
    expect(bad.ok).toBe(false)
    expect(bad.errors.length).toBeGreaterThan(0)
    // 原文件保持骨架内容（未落盘）
    expect(JSON.parse(readFileSync(subGroupFile(home, 'default', 'radar.json'), 'utf8')).name).toBe('radar')

    const good = await store.writeSubGroup(home, 'default', 'radar.json', { name: 'ok', mocks: [] })
    expect(good.ok).toBe(true)
    expect(JSON.parse(readFileSync(subGroupFile(home, 'default', 'radar.json'), 'utf8')).$schema).toBe('equipmock/subgroup@1')
  })
})

describe('组目录操作', () => {
  it('复制/重命名/删除/禁删生效组', async () => {
    await initSkeleton(home)
    store.copyGroup(home, 'default', 'default-copy')
    expect(store.listSubGroupNames(home, 'default-copy')).toEqual(['cabinet.json', 'radar.json'])

    await store.renameGroup(home, 'default-copy', 'renamed')
    expect(store.readSettings(home).activeGroup).toBe('default') // 非生效组重命名不动 settings

    // 生效组禁止删除
    expect(() => store.deleteGroup(home, 'default')).toThrow(/生效组/)
    await store.setActiveGroup(home, 'renamed')
    await store.deleteGroup(home, 'default')
    expect(store.listGroups(home).map((g) => g.name)).toEqual(['fault-sim', 'renamed'])

    // 重命名生效组同步 settings
    await store.renameGroup(home, 'renamed', 'final')
    expect(store.readSettings(home).activeGroup).toBe('final')
  })
})

describe('滚动日志合并（JUL agent.log.0 … .4）', () => {
  it('按 mtime 合并多个 agent.log* 取尾部', async () => {
    await initSkeleton(home)
    const logsDir = homePaths(home).logsDir
    const now = Date.now() / 1000
    writeFileSync(path.join(logsDir, 'agent.log.0'), 'L1\nL2\nL3\n', 'utf8')
    writeFileSync(path.join(logsDir, 'agent.log.1'), 'L4\nL5\n', 'utf8')
    // .1 更新（模拟轮转推进）
    utimesSync(path.join(logsDir, 'agent.log.1'), now, now + 10)
    utimesSync(path.join(logsDir, 'agent.log.0'), now, now)
    writeFileSync(path.join(logsDir, 'agent.log.4'), 'L6\n', 'utf8')
    utimesSync(path.join(logsDir, 'agent.log.4'), now, now + 20)
    const tail = store.readLogTail(home, 4)
    expect(tail).toEqual(['L3', 'L4', 'L5', 'L6'])
    const tail2 = store.readLogTail(home, 2)
    expect(tail2).toEqual(['L5', 'L6'])
    // 无关文件不参与
    writeFileSync(path.join(logsDir, 'other.log'), 'NOPE\n', 'utf8')
    expect(store.readLogTail(home, 1)).toEqual(['L6'])
  })
})

describe('state.json 半截读容错', () => {
  it('解析失败置 parseFailed=true 而不是抛错', async () => {
    await initSkeleton(home)
    const statePath = homePaths(home).state
    writeFileSync(statePath, '{"$schema":"equipmock/state@1","agentVersion":"1.0.0","pid":1,"startedAt"', 'utf8')
    const r = store.readStateTolerant(home)
    expect(r.doc).toBeNull()
    expect(r.parseFailed).toBe(true)

    writeFileSync(statePath, JSON.stringify({ agentVersion: '1.0.0', pid: 2, startedAt: 't', activeGroup: 'default', mockEnabled: true, plugins: [] }), 'utf8')
    const r2 = store.readStateTolerant(home)
    expect(r2.parseFailed).toBe(false)
    expect(r2.doc!.pid).toBe(2)
  })
})

