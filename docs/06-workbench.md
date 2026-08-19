# 06 · 桌面工作台设计

独立 Electron 应用，**唯一职责是帮人方便地维护 `equip-mock/` 目录下的 json 文件与插件 jar**（init.md：无工作台时 Agent 完全独立运行，工作台不是运行时依赖）。

## 1. 技术栈与工程结构

- Electron **43.1.0**（主进程 ESM；`contextIsolation: true`、`nodeIntegration: false`、渲染进程零 Node API）。
- React 19 + **React Compiler**（`babel-plugin-react-compiler`，随 Vite 构建启用）。
- Vite（**Oxc 工具链**，构建时以仓库锁定版本的 Vite+Oxc 插件组合为准）+ **Vitest**（单测渲染逻辑与校验器）。
- Tailwind CSS **v4**（`@tailwindcss/vite`）。
- **monaco-editor**：npm 安装、编译打包内嵌（`import` 静态引入 + worker 以 `?worker` 内联），**禁止 CDN/运行期下载**。
- ajv（加载 `docs/schemas/*.schema.json` 与 agent 同源校验）、zustand（状态）、electron-builder（NSIS + portable）。
- 界面语言：中文。

```
workbench/
├─ electron/            # 主进程：全部 fs 操作唯一入口
│  ├─ main.ts           # 窗口/单实例锁/托盘(可选)
│  ├─ ipc.ts            # IPC channel 注册（§3 契约）
│  └─ equipmockHome.ts  # home 定位/骨架初始化/原子写实现
├─ src/                 # 渲染进程（React）
│  ├─ app/  pages/  components/  stores/  ipc/  schemas/
├─ resources/icon
└─ package.json  electron-builder.yml  vite.config.ts
```

单实例锁：`app.requestSingleInstanceLock()`，二次启动聚焦已有窗口（避免两个工作台同时写同一目录）。

## 2. 主目录（home）定位

设置项存工作台自己的 userData（不写入 equip-mock 目录）：

- 首次：默认展示空状态页，引导选择/创建 home（`dialog.showOpenDialog`）；"创建"= 主进程生成骨架目录（04 §1 结构 + 示例 default 组）。
- 之后：记住上次路径；顶栏常显当前 home 路径，可切换（切换时重载全部状态）。
- 主进程负责全部读/写/原子替换/拷 jar/删 jar；渲染进程通过 IPC 调用（§3）。

## 3. IPC 契约（typed invoke）

主进程实现，渲染进程经 preload 暴露的类型化桥调用（`ipcRenderer.invoke` 封装，错误统一 `{message}` 拒绝值）：

| Channel | 入参 → 出参 | 语义 |
| --- | --- | --- |
| `home:get` / `home:set` / `home:initSkeleton` | path | 读/选/建主目录 |
| `groups:list` | → `[{name, subGroups:[{file,name,mockCount}]}]` | 扫描 config/groups |
| `group:create/copy/delete/rename` | name, {from} | 目录级操作（copy=递归复制目录；delete 有确认） |
| `subgroup:read` | group,file → SubGroupDoc(json) | 读单文件 |
| `subgroup:write` | group,file,doc → `{ok\|{errors[]}}` | **先 ajv+语义校验（与 03 §6 同规则）再原子写**；校验失败拒绝写盘并返回错误列表（行内定位） |
| `settings:getActive/setActive`、`settings:setMockEnabled` | — | settings.json 读写 |
| `plugins:list` | → registry + jar 目录对照（含 state.json 联合视图） | |
| `plugin:import` | (dialog 选 jar) → 校验 MANIFEST(Plugin-Id/Version/Requires) → 拷入 plugins/ → 登记清单 | |
| `plugin:enable/setAlias/remove` | id | 改清单（remove 可选同时删 jar，有确认） |
| `state:get` + `state:subscribe` | → state.json + agent.log 尾 200 行 | 主进程 fs.watch + 1s 轮询兜底，推送给渲染层 |

## 4. 页面结构（D16：三页，无首启向导）

```
┌───────────────────────────────────────────────────────────────┐
│ 顶栏：EquipMock · [home 路径 ▾] · [全局Mock开关] · 状态灯(agent 活跃?) │
├──────────┬────────────────────────────────────────────────────┤
│ 左侧导航  │  右侧内容区（三页切换）                               │
│ ▸ 配置中心 │   配置页：组管理 + 小分组编辑（§5）                   │
│ ▸ 插件    │   插件页：清单表格 + 导入按钮（§6）                   │
│ ▸ 状态    │   状态页：state.json + 日志尾部（§7）                │
└──────────┴────────────────────────────────────────────────────┘
```

顶栏"状态灯"：state.json 的 `lastWriteAt` 在 N 秒内更新过且 pid 存活（主进程探测）→ 绿（agent 在跑）；否则灰（agent 未运行，仅文件维护模式）。

## 5. 配置页（核心）

### 5.1 布局

```
┌───────────────┬────────────────────────────────────────────┐
│ 配置组列表      │ 当前小分组：<cabinet.json>  [表单|JSON源码] 保存 │
│ ● default(生效)│ ┌─ 方法卡片: readStatus ──────────────────┐ │
│ ○ fault-sim   │ │ 类名/方法/签名/启用 开关/描述              │ │
│ ─────────────│ │ 规则列表（可拖动排序 = 优先级）            │ │
│ [+新建][复制]  │ │  1 FULL_MATCH [1,"CH1"] → VALUE 5      │ │
│ [重命名][删除] │ │  2 PATTERN  [\d+,CH.*] → THROW …       │ │
│ 小分组列表：    │ │ [+ 添加规则] [默认动作: VALUE 0 ▾]       │ │
│ · cabinet     │ └────────────────────────────────────────┘ │
│ · radar       │ ┌─ 方法卡片: powerOn … (VOID)             ─┐ │
│ [+ 新建小分组] │ └──────────────────────────────────────────┘ │
└───────────────┴────────────────────────────────────────────┘
```

- 左列两级：组（含"●当前生效"标记与一键切换）> 小分组。选中组≠生效组时给醒目提示条"正在编辑非生效组"。
- 右侧一次只编辑**一个小分组**（init.md：聚焦、内容不过量），侧边可快速切换；未保存修改切走时弹确认（丢失/保存/留置）。

### 5.2 配置组操作

| 操作 | 行为 | 联动 |
| --- | --- | --- |
| 切换生效组 | 写 settings.json.activeGroup | 状态灯转黄→绿；若正编辑旧组，保持编辑不跳转（提示"生效组已变更"） |
| 新建/复制组 | 建目录/递归复制（复制可输新名，默认 `<原名>-copy`） | 不自动切换生效 |
| 删除/重命名组 | 目录级操作；**删除"当前生效组"禁止**（先切走）；重命名同步 settings.json 引用 | — |

### 5.3 编辑双模式（表单 ⇄ Monaco）

- **表单模式**（默认）：按匹配方式渲染不同组件——
  - FULL_MATCH：参数行编辑器，每行一个参数值（依已有 JSON 值类型推断控件：数字/字符串/布尔/枚举下拉=输入提示/null；支持 `{"$hex":…}` 类型标签对象的专用小控件）；行数可增删（=args 数量）。
  - PATTERN_MATCH：每行正则输入 + **即时正则合法性校验**（非法红框禁保存）+ "测试匹配"输入框（前端 JS RegExp 语义同 Java 常用子集，提示极少数方言差异）。
  - action 三选一：VALUE（类型感知的 JSON 值输入，含 `$hex` 辅助生成）/ THROW（异常 FQCN + message，FQCN 格式校验）/ VOID。
  - 方法卡片：类名/方法名必填、签名可选（提供 descriptor 语法即时校验与"从剪贴板粘贴签名"辅助）、enabled 开关、规则拖动排序。
- **JSON 源码模式**：整文件 Monaco（json language + 自定义 schema 智能提示，加载 `docs/schemas/subgroup.schema.json`）；两模式同一状态模型，切换无损。
- **保存**：`Ctrl+S` 或保存按钮 → 渲染层 ajv+语义校验（错误行内列表，含 Monaco 模式下的行列定位）→ 通过后 IPC 原子写 → 轻提示"已保存"；agent 侧随后重载（状态页可确认）。**校验失败一律不落盘**，保证契约文件永远合法。

### 5.4 辅助校验（保存前提示，非阻断）

- 同一 (class,method,signature) 出现在本组多个小分组 → 提示合并顺序=文件名序。
- signature 为空且同名重载存在可能 → 提示"作用于全部重载"。

## 6. 插件页

表格列：启用开关（→ plugin:enable，即时写清单）| id | alias（可编辑）| 版本 | 状态徽章（取 state.json：STARTED/DISABLED/REJECTED/MISSING/FAILED/未知=未运行）| jar 文件名 | 操作（移除=清清单条目，勾选"同时删除 jar 文件"需确认）。

导入流程：`dialog` 选 jar → 主进程读 MANIFEST 校验（缺 Plugin-Id/Version/Requires → 报错拦截；Requires 与平台版本不满足 → 警告仍允许导入，由 agent 硬校验兜底）→ 同名 id 已存在 → 提示覆盖（换 jar 文件+清 note）→ 拷贝 jar + 登记清单（enabled=true）→ 表格即时刷新，agent 热导入后状态徽章变 STARTED。

## 7. 状态页

- 卡片：agent 版本/pid/启动时间、生效组、mockEnabled、instrumentedClasses、needsRestart（红字：需重启宿主才生效的类）。
- 插件状态表（同 §6 徽章数据源）。
- lastError（若有）：文件+消息+时间，点击跳转配置页对应小分组。
- 日志尾部：`logs/agent.log` 最后 200 行，自动滚动+暂停按钮（`state:subscribe` 推送）。
- 说明文案：agent 未运行时此页展示"未检测到 agent 心跳（state.json 未更新）"，数据仍可看（最后一次快照）。

## 8. 状态管理

- zustand stores：`homeStore`（路径/骨架状态）、`configStore`（组树/当前编辑文档/脏标记）、`pluginStore`、`stateStore`（agent 状态订阅）。
- 文档模型：SubGroupDoc（TS 类型与 `docs/schemas/subgroup.schema.json` 同源生成）；脏标记驱动顶栏保存态与关闭确认。
- React Compiler 自动记忆化，组件不手写 memo。

## 9. 测试与构建

- Vitest：校验器（与 03 §6 规则一致的纯函数）、stores、匹配方式表单组件（Testing Library）。
- 主进程薄 IPC 层不测 fs 细节，仅契约冒烟（Electron 侧手测清单见 07 M6 验收）。
- 构建：`vite build`（渲染）+ electron-builder：NSIS 安装包 + portable zip 双目标（D18），icon/版本与 Java 侧同源（`scripts/sync-version`）。
- Monaco 体积控制：仅启用 json language service + 基础主题，worker 内联；构建产物断言无 `https://` 外链资源（CI 检查，落实"禁止 CDN"）。
