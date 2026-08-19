# 01 · 总体架构

## 1. 系统组成

```
┌────────────────────────────────────────────────────────────────────┐
│  装备软件宿主 JVM（JDK 1.8, Windows, java -javaagent:equip-mock-agent.jar -jar app.jar）│
│                                                                    │
│  Bootstrap ClassLoader  ←── Boot-Class-Path: equip-mock-bootstrap.jar│
│    └─ com.equipmock.bootstrap.*  (Spy, ISpyHandler, MockResult…)   │
│                                                                    │
│  System(App) ClassLoader                                           │
│    ├─ 装备应用类（被插桩，方法体头部多了一行 Spy 调用）                 │
│    └─ equip-mock-agent.jar（shaded+relocated：byte-buddy/pf4j/gson） │
│         ├─ AgentCore（premain、插桩、路由）                          │
│         ├─ ConfigCenter（配置中心、文件监听）                        │
│         ├─ PluginManager（PF4J）                                    │
│         │    └─ PluginClassLoader × N                               │
│         │         ├─ mock-cabinet.jar（插件A，provided 依赖 plugin-api）│
│         │         └─ mock-radar.jar（插件B）                         │
│         └─ equipmock home: equip-mock/ （json 文件契约目录）          │
└────────────────────────────────────────────────────────────────────┘
                              ▲ 只读写 json 文件（无进程间耦合）
┌────────────────────────────────────────────────────────────────────┐
│  EquipMock 工作台（独立 Electron 进程，可选存在）                     │
│    配置组/小分组管理 · 插件导入启停 · Agent 状态只读展示               │
└────────────────────────────────────────────────────────────────────┘
```

三个关键隔离/桥接设计：

1. **双 jar 隔离**：`equip-mock-bootstrap.jar` 只含跨类加载器契约类型（Spy、ISpyHandler、MockResult、EnterResult），通过 agent jar 清单 `Boot-Class-Path` 进入 Bootstrap ClassLoader；agent 主体与其全部第三方依赖留在 system classpath 且 relocate 改包名，宿主应用不可见、零污染。
2. **Spy 双向桥**：插桩代码（宿主类加载器）→ `Spy.mock(...)`（bootstrap 可见）→ 静态字段持有的 `ISpyHandler`（premain 时由 agent 反射注入）→ agent 侧路由 → 插件 handler / 配置中心。返回值沿方法调用链自然返回，无需序列化。
3. **插件类隔离**：每个插件一个 PF4J `PluginClassLoader`（parent = agent 所在 system classloader）。插件仅通过 `equipmock-plugin-api`（由 agent jar 携带、**不 relocate**）与平台交互，插件实现类互相隔离，支持卸载。

## 2. Monorepo 模块划分

```
EquipMockPlatform/
├─ pom.xml                        # com.equipmock:equipmock-parent:1.0.0-SNAPSHOT
├─ java/
│  ├─ equipmock-bootstrap/        # → equip-mock-bootstrap.jar（零依赖，仅契约类）
│  ├─ equipmock-plugin-api/       # → 并入 agent.jar 的公共 API（注解/接口），插件以 provided 引用
│  ├─ equipmock-agent/            # → equip-mock-agent.jar（shade+relocate 全部依赖）
│  ├─ equipmock-testkit/          # 插件自测试工具包（test scope 供插件模块使用）
│  ├─ plugins/
│  │  ├─ plugin-mock-cabinet/     # 示例插件 A：机柜电源类硬件
│  │  └─ plugin-mock-radar/       # 示例插件 B：雷达/伺服类硬件
│  └─ demo-host/                  # 示例宿主：模拟装备软件，供端到端验证
├─ workbench/                     # Electron 43 + React 19 工作台（见 06）
├─ docs/                          # 本文档集
└─ scripts/                       # 构建/打包脚本（assembly zip、开发环境自检）
```

Maven 要点：

- 父 POM 统一管理版本：`byte-buddy 1.9.16`、`pf4j 3.12.0`、`gson 2.8.9`、`junit-jupiter 5.8.2`、`maven-shade-plugin 3.2.4`（JDK8 兼容版本线），编译级别 1.8，编码 UTF-8。
- `equipmock-plugin-api` 以 **provided** 依赖出现在所有插件模块（运行时由 agent jar 的 system classpath 提供，插件 jar 内不打它）。
- `equipmock-agent` 打包：shade 合并 byte-buddy/pf4j/gson 并 relocate（规则见 02 §7），`Premain-Class: com.equipmock.agent.AgentPremain`，`Boot-Class-Path: equip-mock-bootstrap.jar`，`Can-Retransform-Classes: true`。
- 发布 zip（`scripts/` + maven-assembly）：`equip-mock-agent.jar` + `equip-mock-bootstrap.jar`（去版本号定名）+ `plugins/`（含 plugin-registry.json 骨架）+ `config/groups/default/` 骨架 + `README-接入.md`。

## 3. 端到端运行时序（一次 Mock 调用）

```
宿主: PowerDevice.readStatus(1, "CH1")        ← 方法已被插桩
  └─ inline advice(enter): 组装 [class|method|descriptor|self|args]
      └─ Spy.mock(...)                        (Bootstrap CL)
          └─ AgentCore.SpyHandler.mock(...)   (system CL，静态字段委托)
              ├─ 全局开关 settings.mockEnabled=false → REAL
              ├─ MockRouter 查 MockPoint：插件未启用 → REAL
              ├─ MockPoint.handler ≠ null（插件写死逻辑）
              │    handler.handle(invocation)
              │      ├─ 返回 MockOutcome → 按其执行（VALUE/THROW/VOID）
              │      └─ 返回 null → 交给配置中心 ↓
              └─ ConfigDrivenHandler：activeGroup 索引中该方法的 rules 逐条匹配
                   ├─ 命中 FULL_MATCH/PATTERN_MATCH → action(VALUE/THROW/VOID)
                   ├─ 未命中 → defaultAction（若有）
                   └─ 仍无 → REAL（放行真实方法）
  └─ advice(exit): REAL→执行原方法体；VALUE→写返回值；THROW→抛出；VOID→直接返回
```

要点：**未配置/未启用的目标方法调用开销 ≈ 一次 HashMap 查找 + 一次 volatile 读**；任何路径失败（handler 抛异常除外）都安全落回 REAL。handler 自身抛出的非受检异常按"Mock 指定 THROW"以外的意外错误处理：记录日志并放行真实调用（见 02 §5.3）。

## 4. 数据目录（两侧唯一契约）

```
<equipmock.home>/                 # -Dequipmock.home 指定，默认 ./equip-mock/
├─ settings.json                  # 活动配置组 + 全局 Mock 开关
├─ plugins/
│  ├─ plugin-registry.json        # 插件清单（唯一事实源）
│  └─ *.jar                       # 已导入插件包
├─ config/
│  └─ groups/
│     └─ <组名>/                   # 一个配置组 = 一个目录
│        └─ <小分组>.json          # 一个小分组 = 一个文件（如 cabinet.json）
├─ logs/
│  └─ agent.log                   # 滚动日志（工作台"状态面板"读尾部）
└─ state.json                     # Agent 回写的运行状态（工作台只读）
```

所有文件的 Schema、原子写协议、监听协议详见 [04-file-contract.md](04-file-contract.md)。

## 5. 技术栈清单

| 层 | 技术 | 版本基线 |
| --- | --- | --- |
| Java 编译/运行 | JDK | 1.8（D:\Java\jdk1.8.0_281），Windows |
| 字节码增强 | byte-buddy | **1.9.16**（锁定） |
| 插件框架 | PF4J | 3.12.0（JDK8 兼容线） |
| JSON | gson | 2.8.9（shade+relocate） |
| 日志 | java.util.logging | JDK 自带（agent 不引第三方日志，避免依赖面） |
| 构建 | Maven | 3.8+，shade + assembly |
| 工作台 | Electron | **43.1.0** |
| 工作台 UI | React + React Compiler + Vite(Oxc) + Vitest + Tailwind CSS v4 | React 19 |
| 工作台编辑器 | monaco-editor | 编译期内嵌，禁 CDN |
| 工作台校验 | ajv（json-schema 本地校验） | 随 npm |
| 打包 | electron-builder | NSIS + portable zip |

## 6. 非目标（首版明确不做）

- 运行期 attach 到已运行 JVM（仅 `-javaagent` 启动挂载）。
- 构造器拦截、参数改写、返回延迟模拟。
- 远程/多机配置分发、鉴权、审计。
- 工作台自动更新、首启向导。
- Linux/macOS 适配（按 Windows 设计，但不主动引入 Win 专有 API 于 Java 侧）。
