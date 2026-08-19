# 07 · 开发路标

面向后续开发 Agent 的任务分解。每个任务自包含、有验收标准；实现前先读 `README.md` 决策记录与对应设计文档章节。约定：

- 环境基线：JDK `D:\Java\jdk1.8.0_281`、Maven 3.8+、Node 22 LTS、pnpm（工作台）；Windows。
- 每个里程碑收尾跑一次该里程碑的全部验收项并记录结果。
- 任何偏离本文档设计的实现决定，需先更新对应设计文档（docs 是唯一事实源）。

## 依赖总览

```
M0 骨架 ──► M1 Agent最小闭环 ──► M2 配置中心 ──► M3 插件框架 ──► M4 testkit+示例 ──► M7 发布
                │                                                │
                └────────────► M5 工作台骨架 ──► M6 工作台完整 ◄──┘
```

M1 完成后 M2/M5 可并行；M3 依赖 M2（配置路由）；M6 依赖 M3/M4（插件页/状态页需要真实 agent 行为）；M7 最后。

---

## M0 · 仓库与构建骨架

| 任务 | 内容 | 验收 |
| --- | --- | --- |
| M0-1 | git init；根 `.gitignore`（target/node_modules/dist/out）；`docs/` 入库 | `git status` 干净 |
| M0-2 | 父 POM `com.equipmock:equipmock-parent:1.0.0-SNAPSHOT`：UTF-8、1.8 编译级别、dependencyManagement（byte-buddy 1.9.16 / pf4j 3.12.0 / gson 2.8.9 / junit-jupiter 5.8.2）、插件版本锁定 | `mvn -v` + 空模块构建通过 |
| M0-3 | 六个 Java 模块壳（bootstrap/plugin-api/agent/testkit/plugins×2/demo-host）+ 各自 pom（依赖 scope 按 01 §2） | `mvn -pl java -amd package` 全绿（空 jar） |
| M0-4 | `workbench/` 脚手架：Electron 43.1.0 + Vite(Oxc) + React 19 + React Compiler + Tailwind v4 + Vitest；空窗口打开 | `pnpm dev` 出窗口；`pnpm test` 通过；构建产物无外链资源 |
| M0-5 | `docs/schemas/` 建 schema 文件骨架（settings/subgroup/plugin-registry/state @1，内容按 04） | ajv 可加载四个 schema |

## M1 · Agent 最小闭环（无插件框架、无文件监听）

目标：`-javaagent` 挂 demo-host，写死的单个 MockPoint 让 `readStatus` 返回固定值——**先把最难的字节码/类加载器链路打通**。

| 任务 | 内容 | 验收 |
| --- | --- | --- |
| M1-1 | bootstrap 模块：Spy/ISpyHandler/MockResult/EnterResult/SneakyThrow（02 §1）+ 单测（直接调 Spy.mock 验证 null 安全） | 编译零依赖（jar 内仅 com/equipmock/bootstrap/**） |
| M1-2 | plugin-api 模块：注解/接口/MockOutcome/MockInvocation（02 §2） | 无第三方依赖（PF4J ExtensionPoint 例外，provided） |
| M1-3 | agent premain：参数解析、home 骨架初始化、JUL 日志、HANDLER 注入、写 state.json(STARTED) | 启动 demo-host 日志/状态可见；宿主 main 正常执行 |
| M1-4 | ByteBuddy 插桩：MockAdvice/MockVoidAdvice（02 §4 全部要点：Local 传递、THROW 直抛、void 分流、基本类型拆箱）+ AgentBuilder 精确类/方法匹配 + 硬编码路由表（本任务允许写死一个 map） | demo-host 四类用例（int/boolean/String/POJO 返回、void、静态/实例、final 方法）全部按 MockResult 生效；未配置方法行为不变 |
| M1-5 | agent 打包：shade+relocate（02 §7 全表；byte-buddy/gson relocate，**pf4j 合并不 relocate**）+ MANIFEST（Premain-Class/Boot-Class-Path/Can-Retransform-Classes）+ 断言测试（扫描 jar 无 net/bytebuddy、com/google/gson 原始包名条目） | 发布 zip 结构=01 §2；`-javaagent` 用 zip 内文件可跑 |
| M1-6 | demo-host：四类方法 + 真实实现打点计数 + 循环打印 main（05 §5） | 手动验证：带/不带 agent 输出差异符合预期 |

**M1 验收（全部满足才进 M2）**：demo-host 带 agent 启动，`readStatus` 返回配置外的固定 Mock 值、`powerOn` void 吞调用打点为 0、未拦截方法原样运行；agent jar 内无 byte-buddy/pf4j/gson 原始包名；拔掉 bootstrap.jar 时启动报错信息可读（提示 Boot-Class-Path）。

## M2 · 配置中心

| 任务 | 内容 | 验收 |
| --- | --- | --- |
| M2-1 | json 读取/Schema 校验/错误定位（03 §6；错误含文件+字段路径+消息） | 坏 json 用例全部被拒且错误可读 |
| M2-2 | 内存模型：SettingsSnapshot/GroupSnapshot/MockIndex 不可变结构 + 原子替换；组级原子加载（03 §1/§2） | 单测：并发读 + 重载无异常、无半新半旧 |
| M2-3 | ValueConverter：规范串 + JSON→类型全表（03 §5，含 byte[] hex/b64、POJO 反射、转换失败放行） | 单测覆盖 03 §5 每行表格 + 验收用例 9/10 |
| M2-4 | 匹配引擎：FULL_MATCH 深度相等、PATTERN_MATCH 逐参正则、first-match、defaultAction、VOID/THROW/VALUE 执行（03 §4） | 03 §9 用例 1–5 在 demo-host 上通过 |
| M2-5 | FileWatcher：WatchService+防抖+兜底轮询+忽略 tmp+原子写读端容错（03 §7）；settings 变化（切组/总开关） | 03 §9 用例 6–8：改文件 ≤2s 生效；非法文件旧配置保留、lastError 可见 |
| M2-6 | state.json 回写时机全集（02 §8）+ lastError 结构 | 每次重载/错误后 state.json 内容正确 |

**M2 验收**：03 §9 十条用例全部通过（脚本化 `scripts/e2e-check.ps1` 可重复执行）。

## M3 · 插件框架（PF4J）

| 任务 | 内容 | 验收 |
| --- | --- | --- |
| M3-1 | PF4J 集成：清单驱动加载（未登记不加载）、ManifestPluginDescriptorFinder、relocate 后 ServicesResourceTransformer 验证 | 插件 jar 登记后加载；不登记的 jar 忽略 |
| M3-2 | @MockInterceptor 扫描 → MockPoint → MockRouter 重建；多插件同目标串联 first-match（05 §3） | 双示例插件同拦截类按序生效 |
| M3-3 | 启停=路由开关：registry.enabled 变化即时生效（02 §6.2/D8） | 运行中停用→REAL，启用→恢复，<2s |
| M3-4 | 热导入：新插件登记→load+retransform 已加载类；失败记 needsRestart（D9） | 宿主运行中导入插件：未加载类拦截生效、已加载类 retransform 生效；构造失败类（写一个 final+native 之类的对抗样例）进 needsRestart 不崩溃 |
| M3-5 | 版本硬校验：Plugin-Requires 解析+VersionManager 比对+REJECTED 状态（05 §6） | requires 过高→REJECTED 且 state 说明 |
| M3-6 | 卸载：清单删条目→stop/unload+路由移除；MISSING 状态 | 卸载后调用 REAL、字节码不回滚但无路由 |

**M3 验收**：导入→启用→停用→卸载全循环在 demo-host 运行中完成，全程不重启、无异常日志（除预期 needsRestart 样例）。

## M4 · testkit 与示例插件

| 任务 | 内容 | 验收 |
| --- | --- | --- |
| M4-1 | equipmock-testkit：EquipMockTestBase + 原子写/awaitConfigApplied/enableMock + surefire argLine 模板（05 §4） | agent 模块自身用它写集成测试 |
| M4-2 | plugin-mock-cabinet：PowerDevice 全方法 handler（null 落配置为主 + 1 个写死用例）+ 自测试（含 05 §4.3 两条用例） | `mvn test` 单模块自测通过（init.md：每模块可自测试） |
| M4-3 | plugin-mock-radar：第二个插件（不同目标类 + PATTERN_MATCH 场景 + THROW）+ 自测试 | 同上 |
| M4-4 | 端到端脚本固化：`scripts/e2e-check.ps1` 拉起 demo-host 断言输出（M2 十条 + M3 全循环） | 一条命令全绿 |

## M5 · 工作台骨架

| 任务 | 内容 | 验收 |
| --- | --- | --- |
| M5-1 | 主进程：home 定位/骨架/原子写/jar 管理 + IPC 全表（06 §3）+ 单实例锁 | IPC 契约冒烟手测清单通过 |
| M5-2 | 渲染层框架：路由三页 + zustand stores + 顶栏（home 切换/全局开关/状态灯） | 三页空壳可切换 |
| M5-3 | 配置组管理：组树、新建/复制/删除/重命名、切换生效组（含禁删生效组）（06 §5.2） | 与 agent 联动：切换后 demo-host 输出变化 ≤2s |

## M6 · 工作台完整

| 任务 | 内容 | 验收 |
| --- | --- | --- |
| M6-1 | 小分组编辑表单模式：方法卡片、FULL_MATCH 参数行、PATTERN_MATCH 正则校验、action 三组件、拖动排序（06 §5.3） | 03 §9 全部用例可仅靠工作台配置出来 |
| M6-2 | Monaco 整文件模式：json schema 智能提示、错误定位、双模式无损切换、Ctrl+S 统一保存流（ajv+语义校验→原子写） | 非法内容无法保存；保存后 agent 生效 |
| M6-3 | 插件页：导入（MANIFEST 校验）/启停/移除/状态徽章（06 §6） | 运行中 agent 下完成 M3 全循环操作 |
| M6-4 | 状态页：state 卡片/lastError 跳转/日志尾部/未运行态（06 §7） | 坏配置时可见错误并跳转 |
| M6-5 | Vitest：校验器纯函数 + stores + 关键组件 | `pnpm test` 全绿；构建产物无外链（M0-4 断言保持） |

## M7 · 发布与收尾

| 任务 | 内容 | 验收 |
| --- | --- | --- |
| M7-1 | Java 侧发布 zip：assembly 定名（equip-mock-agent/bootstrap.jar）+ 骨架 + `README-接入.md`（-javaagent/-Dequipmock.home/插件放置说明） | 干净目录解压即用 |
| M7-2 | 工作台 electron-builder：NSIS+portable、版本同步脚本（06 §9） | 两种安装形态可用 |
| M7-3 | 端到端回归：e2e-check + 工作台手测清单（M5-1/M6 各验收项合并成 checklist） | 全绿并归档结果 |
| M7-4 | 文档收尾：schemas 与实现一致性复查、决策记录补新 ADR | docs 与实现无漂移 |

## 风险与预案

| 风险 | 影响 | 预案 |
| --- | --- | --- |
| byte-buddy 1.9.16 对 void/基本类型 advice 的边界行为 | M1 插桩不生效/校验失败 | M1-4 已含四类返回用例；必要时对 void/非 void、装箱/非装箱细分 advice 模板（最多 4 个组合） |
| PF4J relocate 后 SPI/资源加载失效 | M3 插件加载失败 | M3-1 单独验证；必要时放弃 relocate pf4j 改为自定义前缀包复制（风险低，pf4j 体积小） |
| WatchService 在 Windows 丢事件 | 配置不生效 | 兜底轮询已设计（D14）；e2e 含 2s 生效断言 |
| 宿主类已被加载导致 premain 漏拦 | Mock 不生效 | premain 末尾防御性 retransform（02 §3 第8步）；needsRestart 暴露 |
| 巨型/循环引用参数序列化 | 匹配卡顿 | 规范串 4096 截断 + gson 限制递归深度（03 §5.1） |
| Monaco 内嵌体积/worker 路径 | 构建失败或运行白屏 | 仅 json language；worker `?worker` 内联；M0-4 起就带"无外链"断言 |

## 工作量粗估（供排期参考，人日）

M0:2 · M1:6 · M2:7 · M3:6 · M4:3 · M5:4 · M6:7 · M7:3 —— 合计约 38 人日（单线）；M2 与 M5 并行可压缩总周期约 1 周。
