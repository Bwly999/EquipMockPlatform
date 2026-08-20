# EquipMock 装备软件 Mock 平台 — 设计文档集

本目录是 EquipMock 平台的完整方案设计，供后续开发 Agent 直接照此实现。所有文档基于 `init.md` 的需求与一轮逐项决策访谈（决策记录见文末）产出。

## 文档索引

| 文档 | 内容 | 读者 |
| --- | --- | --- |
| [01-architecture.md](01-architecture.md) | 总体架构、模块划分、运行流程、类加载器模型 | 所有开发者，先读 |
| [02-agent-core.md](02-agent-core.md) | Agent 核心：premain 流程、ByteBuddy 插桩、Spy 桥接、插件装载、启停语义 | Java 侧开发 |
| [03-config-center.md](03-config-center.md) | 配置中心：目录布局、内存模型、文件监听重载、匹配引擎、类型转换器 | Java 侧开发 |
| [04-file-contract.md](04-file-contract.md) | 文件契约：全部 JSON Schema（settings / plugin-registry / 小分组配置 / state）、原子写协议 | Java + 工作台开发，**双方的唯一契约** |
| [05-plugin-dev-guide.md](05-plugin-dev-guide.md) | 插件开发指南：注解 API、MockHandler、示例、testkit 自测试 | 插件作者 + Java 侧开发 |
| [06-workbench.md](06-workbench.md) | 桌面工作台：Electron 架构、IPC 契约、页面设计、Monaco 集成、构建打包 | 工作台开发 |
| [07-roadmap.md](07-roadmap.md) | 开发路标：M0–M7 里程碑、任务分解、验收标准、依赖关系 | 项目推进 / 任务分派 |

## 一句话架构

- **Java Agent 侧**（JDK 1.8，byte-buddy 1.9.16，PF4J，Maven 多模块）：
  `-javaagent:equip-mock-agent.jar` 非侵入插桩装备软件中与硬件相关的函数；插桩代码仅调用 bootstrap 级 `Spy` 桥接类；`Spy` 静态委托回 agent 侧路由：**插件自定义 MockHandler（写死逻辑）优先，返回空则落配置中心规则（FixedValue / FullMatch / PatternMatch，first-match）**，均未命中则放行真实方法。
- **桌面工作台**（Electron 43 + React 19 + Vite/Oxc + Tailwind v4 + Monaco，离线内嵌）：
  纯文件契约的配套工具——配置组/小分组管理、插件导入启停、Agent 状态只读展示。工作台不在场时 Agent 完全独立运行。
- **两侧唯一通信媒介 = `equip-mock/` 数据目录下的 json 文件**（Agent 用 WatchService + 防抖 + 兜底轮询实时应用，写侧一律"临时文件 + 原子替换"）。

## 关键约束（来自 init.md，不可违背）

1. JDK 1.8（`D:\Java\jdk1.8.0_281`），byte-buddy 固定 **1.9.16**，Windows 平台。
2. agent 对工作台**零依赖**；无工作台时 Agent + 手改 json 一切照常。
3. Agent jar 落在 system classpath 上，**所有第三方依赖必须 shade + relocate**，不得污染宿主应用。
4. 配置中心只提供"配置能力"；某函数用配置还是写死逻辑，由插件作者代码决定。
5. 工作台：Electron **43.1.0**、React 19 + React Compiler + Vite + Vitest + Oxc + Tailwind CSS **v4**；Monaco **编译期内嵌**，禁止 CDN / 运行期下载。

## 决策记录（ADR 摘要）

| # | 决策点 | 结论 | 理由 |
| --- | --- | --- | --- |
| D1 | 仓库/构建 | Monorepo；`java/` Maven 多模块 + `workbench/` Electron | provided 术语即 Maven；统一版本管理 |
| D2 | 宿主形态 | 普通 Java SE 应用（AppClassLoader）；仅 premain 启动挂载 | 最简类加载器策略；不做运行期 attach |
| D3 | Mock 能力 | 返回值 + void 拦截 + 抛异常；**不做延迟、不拦构造器** | 控制首版复杂度 |
| D4 | 双 jar 加载 | `Boot-Class-Path` 清单引用 `equip-mock-bootstrap.jar` | JLS 标准机制，无临时文件/解压 |
| D5 | 跨类加载器通信 | bootstrap 级 `Spy` 静态委托（agent premain 注入 handler） | SkyWalking 同款，热路径零反射 |
| D6 | 插件框架 | **PF4J 3.x**（生命周期/依赖/隔离/卸载） | 成熟、JDK8 兼容、贴合"引入/卸载"需求 |
| D7 | 目标声明 | 注解驱动 `@MockInterceptor`（handler 即 PF4J @Extension） | 编译期可查、自测试简单 |
| D8 | 启停语义 | 启动时全量插桩；启停=路由标志位，**不回滚字节码** | 零 retransform 风险、立即生效 |
| D9 | 运行期导入 | 对已加载类 `retransformClasses` 增量补齐；失败提示重启 | 兼顾热导入体验与可靠性 |
| D10 | 目录布局 | `equip-mock/{settings.json, plugins/, config/groups/<组>/<小分组>.json, logs/, state.json}` | 组=目录、小分组=文件，见 04 文档 |
| D11 | 规则优先级 | `rules` 数组顺序 **first-match**；`defaultAction` 兜底；未命中放行真实方法 | 所见即所得，工作台拖动排序即优先级 |
| D12 | 类型转换 | 内置常用类型转换器集（基本类型/String/enum/byte[]/数组/List/Map/简单 POJO） | 覆盖装备软件常见 DTO；复杂场景引导插件写死 |
| D13 | 状态回写 | Agent 写 `state.json` + `logs/agent.log`，工作台只读展示 | 保持纯文件契约、双向解耦 |
| D14 | 监听协议 | WatchService + 500ms 防抖 + 5s 兜底轮询 mtime；解析成功才原子替换内存 | Windows 可靠性 + 防丢事件 |
| D15 | 插件事实源 | `plugin-registry.json` 清单驱动；未登记 jar 不加载 | 可控、与工作台单一真相 |
| D16 | 工作台首版 | 配置组管理+小分组编辑、插件管理页、Agent 状态面板；**不做首启向导** | 聚焦核心价值 |
| D17 | 自测试 | `equipmock-testkit` 工具包 + surefire argLine 进程内挂 agent | 与真实运行方式一致、最短路径 |
| D18 | 发布 | Agent 侧 zip；工作台 electron-builder NSIS + 便携 zip | 覆盖内网分发与便携两种场景 |
| D19 | API 兼容 | 插件 descriptor 声明平台版本要求，agent **硬校验拒绝**并写 state.json | 避免"加载了但行为诡异" |

## 实施修订记录（实现期间对设计的修正，均已回写对应文档）

| 修正 | 涉及文档 | 原因 |
| --- | --- | --- |
| advice 采用两段式 enter+exit（skipOn 返回值不回传） | 02 §4 | byte-buddy 1.9.16 实测（javap 验证） |
| PF4J shade 合并但不 relocate | 02 §7 | 扩展点契约需与插件编译期同包名 |
| JUL 滚动日志文件名带代号 agent.log.0–.4 | 04 §1、06 §7 | FileHandler count>1 强制编号 |
| state.json 增加 groupFiles、小分组 name 可缺省 | 04 §3/§6 + schemas | M2 反馈的契约内部矛盾 |
| 插件自测试走 failsafe（IT 阶段）而非 surefire | 05 §4、07 M4-1 | test 阶段插件 jar 尚未打包 |
| extensions.idx 由 pf4j 注解处理器自动生成 | 02 §6.2、05 §4 | PF4J 3.12 无 classgraph |
| 缺 Plugin-Requires → REJECTED；版本比较自写（仅比三段数字） | 02 §6.1 | fail-closed + VersionManager 能力限制 |
| enabled=false 仍 startPlugin（RESOLVED/DISABLED 两态语义） | 02 §6.2 | AbstractExtensionFinder 要求 STARTED |
| Java 发布 zip 由 scripts/make-release.sh 组装 | 07 M7-1 | 免引入 assembly 插件，产物一致 |

## 术语表

- **宿主应用**：被 Mock 的装备软件（普通 Java SE 程序）。
- **Agent**：`equip-mock-agent.jar`，premain 入口，含全部平台逻辑。
- **Bootstrap jar**：`equip-mock-bootstrap.jar`，仅含 Spy 桥接类与跨类加载器契约类型。
- **插件（Plugin）**：一个 Maven 模块产出的 jar，含若干 `MockHandler`，声明拦截目标。
- **配置组（Group）**：`config/groups/` 下一个目录，是一套完整预设。
- **小分组（SubGroup）**：配置组内一个 json 文件，对应一类业务（如"机柜"）。
- **Mock 点（MockPoint）**：路由表中 `(类, 方法, 签名)` 的一个可拦截条目。
- **规则（Rule）**：小分组内某方法的一条匹配+动作配置（FixedValue/FullMatch/PatternMatch）。
