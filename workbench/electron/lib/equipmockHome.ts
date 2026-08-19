/**
 * home 定位与骨架初始化（06 §2）：
 * - 上次路径记忆存在工作台自己的 userData（不写入 equip-mock 目录）；
 * - 骨架 = 04 §1 目录结构 + 示例 default / fault-sim 两个配置组。
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { SKELETON_GROUPS, SKELETON_REGISTRY, SKELETON_SETTINGS } from '../../src/lib/skeletonData'
import { atomicWriteFile, readJsonIfExists, writeJsonPretty } from './atomicWrite'
import { homePaths } from './paths'

const WORKBENCH_CONFIG = 'workbench.json'

export interface WorkbenchConfig {
  homePath: string | null
}

export function workbenchConfigPath(userDataDir: string): string {
  return path.join(userDataDir, WORKBENCH_CONFIG)
}

export function readRememberedHome(userDataDir: string): string | null {
  const cfg = readJsonIfExists(workbenchConfigPath(userDataDir)) as WorkbenchConfig | undefined
  return cfg?.homePath ?? null
}

export async function rememberHome(userDataDir: string, homePath: string | null): Promise<void> {
  fs.mkdirSync(userDataDir, { recursive: true })
  await atomicWriteFile(workbenchConfigPath(userDataDir), writeJsonPretty({ homePath }))
}

/** 校验目录可作为 home：必须是存在的目录 */
export function assertDirectory(p: string): void {
  const stat = fs.statSync(p, { throwIfNoEntry: false })
  if (!stat?.isDirectory()) throw new Error(`目录不存在：${p}`)
}

export function looksLikeHome(p: string): boolean {
  const h = homePaths(p)
  return fs.existsSync(h.settings) || fs.existsSync(h.groupsDir)
}

export interface SkeletonResult {
  homePath: string
  created: string[]
  /** 已是 equip-mock home，未做任何写入 */
  alreadyInitialized: boolean
}

/**
 * 生成骨架：已有 settings.json 的目录视为已初始化（不覆盖任何内容）；
 * 其余缺失的目录/文件逐个补齐。
 */
export async function initSkeleton(home: string): Promise<SkeletonResult> {
  assertDirectory(home)
  const h = homePaths(home)
  const alreadyInitialized = fs.existsSync(h.settings)
  const created: string[] = []
  const ensureDir = (p: string) => {
    if (!fs.existsSync(p)) {
      fs.mkdirSync(p, { recursive: true })
      created.push(path.relative(home, p))
    }
  }
  const ensureFile = (p: string, content: string) => {
    if (!fs.existsSync(p)) {
      fs.mkdirSync(path.dirname(p), { recursive: true })
      fs.writeFileSync(p, content, 'utf8')
      created.push(path.relative(home, p))
    }
  }

  ensureDir(h.pluginsDir)
  ensureDir(h.groupsDir)
  ensureDir(h.logsDir)
  ensureFile(h.settings, writeJsonPretty(SKELETON_SETTINGS))
  ensureFile(h.registry, writeJsonPretty(SKELETON_REGISTRY))
  for (const [group, files] of Object.entries(SKELETON_GROUPS)) {
    ensureDir(path.join(h.groupsDir, group))
    for (const [file, doc] of Object.entries(files)) {
      ensureFile(path.join(h.groupsDir, group, file), writeJsonPretty(doc))
    }
  }
  return { homePath: home, created, alreadyInitialized }
}
