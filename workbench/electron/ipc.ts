/**
 * IPC 契约注册（06 §3）。渲染层经 preload 的类型化桥调用；
 * 错误统一转成带 message 的 Error 拒绝值。
 */
import { BrowserWindow, dialog, ipcMain } from 'electron'
import * as fs from 'node:fs'
import * as path from 'node:path'
import type {
  ImportResult,
  ManifestInfo,
  PluginEntry,
  PluginRow,
  StateSnapshot,
  SubGroupDoc,
  WriteResult,
} from '../src/lib/types'
import {
  assertDirectory,
  initSkeleton,
  readRememberedHome,
  rememberHome,
} from './lib/equipmockHome'
import { compareLooseSemver, extractPluginManifestFromJar } from './lib/manifest'
import { assertValidAlias, homePaths } from './lib/paths'
import { diffRegistry } from './lib/registryDiff'
import * as store from './lib/store'
import { HomeError } from './lib/store'
import { buildSnapshot, StateWatcher } from './lib/stateWatcher'

export const STATE_CHANGED_CHANNEL = 'state:changed'

export class WorkbenchIpc {
  private home: string | null = null
  private readonly watcher: StateWatcher

  constructor(private readonly getUserDataDir: () => string) {
    this.watcher = new StateWatcher((snapshot) => this.broadcast(snapshot))
  }

  /** app ready 后调用：恢复上次 home */
  bootstrap(): void {
    this.home = readRememberedHome(this.getUserDataDir())
    if (this.home) {
      try {
        assertDirectory(this.home)
        this.watcher.start(this.home)
      } catch {
        this.home = null
      }
    }
  }

  private mustHome(): string {
    if (!this.home) throw new HomeError('尚未选择 equip-mock 主目录')
    return this.home
  }

  private broadcast(snapshot: StateSnapshot): void {
    for (const win of BrowserWindow.getAllWindows()) {
      if (!win.isDestroyed()) win.webContents.send(STATE_CHANGED_CHANNEL, snapshot)
    }
  }

  private focusedWindow(): BrowserWindow | null {
    return BrowserWindow.getFocusedWindow() ?? BrowserWindow.getAllWindows()[0] ?? null
  }

  private setHome(next: string | null): void {
    this.home = next
    if (next) this.watcher.start(next)
    else this.watcher.stop()
  }

  register(): void {
    const wrap = <T>(fn: () => T): T => {
      try {
        return fn()
      } catch (e) {
        if (e instanceof HomeError) throw new Error(e.message)
        throw new Error(e instanceof Error ? e.message : String(e))
      }
    }

    // ---------- home ----------
    ipcMain.handle('home:get', () => wrap(() => ({ homePath: this.home })))

    ipcMain.handle('home:select', async () => {
      const win = this.focusedWindow()
      const r = await dialog.showOpenDialog(win!, {
        title: '选择 equip-mock 主目录',
        properties: ['openDirectory', 'createDirectory'],
      })
      return r.canceled || !r.filePaths[0] ? null : r.filePaths[0]
    })

    ipcMain.handle('home:set', (_e, path: string) =>
      wrap(async () => {
        assertDirectory(path)
        await rememberHome(this.getUserDataDir(), path)
        this.setHome(path)
        return { homePath: this.home }
      }) as Promise<{ homePath: string | null }>,
    )

    ipcMain.handle('home:initSkeleton', (_e, targetPath?: string) =>
      wrap(async () => {
        let p = targetPath
        if (!p) {
          const win = this.focusedWindow()
          const r = await dialog.showOpenDialog(win!, {
            title: '选择（或新建）equip-mock 主目录',
            properties: ['openDirectory', 'createDirectory'],
          })
          if (r.canceled || !r.filePaths[0]) throw new Error('已取消')
          p = r.filePaths[0]
        }
        const result = await initSkeleton(p)
        await rememberHome(this.getUserDataDir(), p)
        this.setHome(p)
        return { homePath: p, created: result.created, alreadyInitialized: result.alreadyInitialized }
      }) as Promise<{ homePath: string; created: string[]; alreadyInitialized: boolean }>,
    )

    // ---------- groups ----------
    ipcMain.handle('groups:list', () => wrap(() => store.listGroups(this.mustHome())))

    ipcMain.handle('group:create', (_e, name: string) =>
      wrap(() => {
        store.createGroup(this.mustHome(), name)
      }),
    )

    ipcMain.handle('group:copy', (_e, from: string, to: string) =>
      wrap(() => {
        store.copyGroup(this.mustHome(), from, to)
      }),
    )

    ipcMain.handle('group:delete', (_e, name: string) =>
      wrap(() => {
        store.deleteGroup(this.mustHome(), name)
      }),
    )

    ipcMain.handle('group:rename', (_e, from: string, to: string) =>
      wrap(async () => {
        await store.renameGroup(this.mustHome(), from, to)
      }) as Promise<void>,
    )

    ipcMain.handle('group:readAll', (_e, group: string) =>
      wrap(() => store.readAllSubGroups(this.mustHome(), group)),
    )

    // ---------- subgroup ----------
    ipcMain.handle('subgroup:read', (_e, group: string, file: string) =>
      wrap(() => store.readSubGroup(this.mustHome(), group, file)),
    )

    ipcMain.handle('subgroup:write', (_e, group: string, file: string, doc: SubGroupDoc) =>
      wrap(async () => {
        const result: WriteResult = await store.writeSubGroup(this.mustHome(), group, file, doc)
        return result
      }) as Promise<WriteResult>,
    )

    ipcMain.handle('subgroup:create', (_e, group: string, name: string) =>
      wrap(() => store.createSubGroup(this.mustHome(), group, name)),
    )

    // ---------- settings ----------
    ipcMain.handle('settings:getActive', () => wrap(() => store.readSettings(this.mustHome())))

    ipcMain.handle('settings:setActive', (_e, group: string) =>
      wrap(async () => {
        await store.setActiveGroup(this.mustHome(), group)
      }) as Promise<void>,
    )

    ipcMain.handle('settings:setMockEnabled', (_e, enabled: boolean) =>
      wrap(async () => {
        const home = this.mustHome()
        const settings = store.readSettings(home)
        await store.writeSettings(home, { ...settings, mockEnabled: enabled })
      }) as Promise<void>,
    )

    // ---------- plugins ----------
    ipcMain.handle('plugins:list', () => wrap(() => store.listPluginRows(this.mustHome())))

    ipcMain.handle(
      'plugin:import',
      (_e, jarPath: string | null | undefined, opts?: { overwrite?: boolean }): Promise<ImportResult> =>
        wrap(async () => this.importPlugin(jarPath, opts)) as Promise<ImportResult>,
    )

    ipcMain.handle('plugin:enable', (_e, id: string, enabled: boolean) =>
      wrap(async () => {
        await this.updateRegistryEntry(id, (entry) => {
          entry.enabled = enabled
        })
      }) as Promise<void>,
    )

    ipcMain.handle('plugin:setAlias', (_e, id: string, alias: string) =>
      wrap(async () => {
        assertValidAlias(alias)
        await this.updateRegistryEntry(id, (entry) => {
          entry.alias = alias
        })
      }) as Promise<void>,
    )

    ipcMain.handle('plugin:remove', (_e, id: string, deleteJar: boolean) =>
      wrap(async () => {
        const home = this.mustHome()
        const before = store.readRegistry(home)
        const target = before.plugins.find((p) => p.id === id)
        if (!target) throw new Error(`插件不存在：${id}`)
        const after = { plugins: before.plugins.filter((p) => p.id !== id) }
        await store.writeRegistry(home, after)
        if (deleteJar) {
          const jarAbs = path.join(homePaths(home).pluginsDir, path.basename(target.jar))
          if (fs.existsSync(jarAbs)) fs.rmSync(jarAbs, { force: true })
        }
        void diffRegistry(before, after)
      }) as Promise<void>,
    )

    // ---------- state ----------
    ipcMain.handle('state:get', () => wrap(() => buildSnapshot(this.mustHome())))

    ipcMain.handle('state:subscribe', () =>
      wrap(() => {
        // 订阅即确保 watcher 在跑（home 已设置时）
        if (this.home) this.watcher.start(this.home)
        return buildSnapshot(this.mustHome())
      }) as Promise<StateSnapshot>,
    )
  }

  private async updateRegistryEntry(
    id: string,
    mutate: (entry: PluginEntry) => void,
  ): Promise<void> {
    const home = this.mustHome()
    const before = store.readRegistry(home)
    const entry = before.plugins.find((p) => p.id === id)
    if (!entry) throw new Error(`插件不存在：${id}`)
    mutate(entry)
    await store.writeRegistry(home, before)
  }

  private async importPlugin(
    jarPath: string | null | undefined,
    opts?: { overwrite?: boolean },
  ): Promise<ImportResult> {
    let jar = jarPath ?? null
    if (!jar) {
      const win = this.focusedWindow()
      const r = await dialog.showOpenDialog(win!, {
        title: '选择插件 jar',
        filters: [{ name: 'Java 插件 jar', extensions: ['jar'] }],
        properties: ['openFile'],
      })
      if (r.canceled || !r.filePaths[0]) return { status: 'cancelled' }
      jar = r.filePaths[0]
    }

    const home = this.mustHome()
    const buf = fs.readFileSync(jar)
    const manifest: ManifestInfo = extractPluginManifestFromJar(buf)

    const registry = store.readRegistry(home)
    const existing = registry.plugins.find((p) => p.id === manifest.pluginId)
    if (existing && !opts?.overwrite) {
      return {
        status: 'needs-overwrite-confirm',
        jarPath: jar,
        manifest,
        existing: this.rowFor(home, existing.id),
      }
    }

    const jarName = path.basename(jar)
    if (!/^[A-Za-z0-9._-]+\.jar$/.test(jarName)) {
      throw new Error(`jar 文件名不合法：${jarName}`)
    }
    const nameOwner = registry.plugins.find((p) => p.jar === jarName && p.id !== manifest.pluginId)
    if (nameOwner) {
      throw new Error(`jar 文件名 ${jarName} 已被插件 ${nameOwner.id} 登记，请先移除或重命名文件`)
    }

    // Requires 与平台版本比较：工作台只警告不拦截（agent 硬校验兜底，D19）
    const warnings: string[] = []
    const agentVersion = store.readState(home)?.agentVersion
    if (manifest.pluginRequires && agentVersion) {
      if (compareLooseSemver(manifest.pluginRequires, agentVersion) > 0) {
        warnings.push(
          `Plugin-Requires=${manifest.pluginRequires} 高于当前 agent 版本 ${agentVersion}，agent 启动时会以 REJECTED 拒绝加载`,
        )
      }
    } else if (manifest.pluginRequires && !agentVersion) {
      warnings.push(`未检测到运行中的 agent（无法比对 Plugin-Requires=${manifest.pluginRequires}），由 agent 侧硬校验兜底`)
    }

    // 拷 jar（临时文件 + rename，避免半截文件被 agent 看到）
    const pluginsDir = homePaths(home).pluginsDir
    fs.mkdirSync(pluginsDir, { recursive: true })
    const tmp = path.join(pluginsDir, `${jarName}.tmp-${Date.now().toString(36)}`)
    fs.copyFileSync(jar, tmp)
    fs.renameSync(tmp, path.join(pluginsDir, jarName))

    const now = new Date().toISOString()
    if (existing) {
      // 覆盖：换 jar 文件 + 清 note，保留 alias（06 §6）
      existing.jar = jarName
      existing.enabled = true
      existing.importedAt = now
      delete existing.note
    } else {
      registry.plugins.push({
        id: manifest.pluginId,
        jar: jarName,
        enabled: true,
        importedAt: now,
        note: manifest.pluginDescription ?? '',
      })
    }
    const before = store.readRegistry(home)
    await store.writeRegistry(home, registry)
    void diffRegistry(before, registry)

    const row = this.rowFor(home, manifest.pluginId)
    return existing ? { status: 'overwritten', plugin: row, warnings } : { status: 'imported', plugin: row, warnings }
  }

  private rowFor(home: string, id: string): PluginRow {
    const row = store.listPluginRows(home).rows.find((r) => r.id === id)
    if (!row) throw new Error(`插件不存在：${id}`)
    return row
  }
}
