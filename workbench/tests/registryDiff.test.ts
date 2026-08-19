/** 主进程纯函数测试：插件清单 diff */
import { describe, expect, it } from 'vitest'
import { diffRegistry } from '../electron/lib/registryDiff'
import type { PluginRegistryDoc } from '../src/lib/types'

const reg = (plugins: PluginRegistryDoc['plugins']): PluginRegistryDoc => ({ plugins })

describe('diffRegistry', () => {
  it('无变化', () => {
    const a = reg([{ id: 'p1', jar: 'p1.jar', enabled: true }])
    expect(diffRegistry(a, reg([...a.plugins]))).toEqual({
      added: [],
      removed: [],
      toggled: [],
      jarChanged: [],
      aliasChanged: [],
    })
  })

  it('新增 / 删除', () => {
    const before = reg([{ id: 'p1', jar: 'p1.jar', enabled: true }])
    const after = reg([{ id: 'p2', jar: 'p2.jar', enabled: false }])
    const d = diffRegistry(before, after)
    expect(d.added.map((p) => p.id)).toEqual(['p2'])
    expect(d.removed.map((p) => p.id)).toEqual(['p1'])
  })

  it('启停切换', () => {
    const before = reg([{ id: 'p1', jar: 'p1.jar', enabled: true }])
    const after = reg([{ id: 'p1', jar: 'p1.jar', enabled: false }])
    expect(diffRegistry(before, after).toggled).toEqual([{ id: 'p1', enabled: false }])
  })

  it('jar 文件名替换与别名变更', () => {
    const before = reg([{ id: 'p1', jar: 'old.jar', enabled: true, alias: '旧' }])
    const after = reg([{ id: 'p1', jar: 'new.jar', enabled: true, alias: '新' }])
    const d = diffRegistry(before, after)
    expect(d.jarChanged).toEqual([{ id: 'p1', from: 'old.jar', to: 'new.jar' }])
    expect(d.aliasChanged).toEqual([{ id: 'p1', alias: '新' }])
  })
})
