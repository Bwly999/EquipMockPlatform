# 04 · 文件契约与 JSON Schema

本文档是 **Java Agent 与工作台之间的唯一契约**。两侧对 `equip-mock/` 目录下每个文件的结构、编码、写入方式以本文为准。全部文件：UTF-8（无 BOM）、LF 或 CRLF 均可读、顶层为 JSON 对象。

json-schema 规范文件随代码维护（`docs/schemas/*.schema.json`，工作台用 ajv 加载校验；agent 侧手写校验逻辑与 schema 语义一致）。本文给出语义定义与示例。

## 1. 目录总览

```
<equipmock.home>/
├─ settings.json
├─ plugins/
│  ├─ plugin-registry.json
│  └─ <任意名>.jar
├─ config/groups/<groupName>/<subGroup>.json
├─ logs/agent.log*            # JUL 滚动日志（FileHandler 代号 .0–.4）
└─ state.json            # agent 专写，人/工作台只读
```

命名规则：`groupName`、`subGroup`、插件 `alias` 允许 `[\w\u4e00-\u9fa5-]{1,64}`（中文可用，作显示名）；作为**文件/目录名**时仅 `[A-Za-z0-9_-]{1,64}`（显示名与目录名解耦，目录名由工作台生成）。文件名（不含 .json）即 subGroup 的默认显示名。

## 2. settings.json（活动组指针 + 全局开关）

写方：工作台 / 人。读方：agent。

```json
{
  "$schema": "equipmock/settings@1",
  "activeGroup": "default",
  "mockEnabled": true
}
```

| 字段 | 类型 | 默认 | 语义 |
| --- | --- | --- | --- |
| activeGroup | string | "default" | 活动配置组（目录名）。指向不存在组 → agent 保持旧组 + lastError |
| mockEnabled | boolean | true | 全局 Mock 总开关。false=所有拦截点放行真实调用（工作台"一键恢复真实"） |

## 3. 小分组配置文件（config/groups/<组>/<小分组>.json）

写方：工作台 / 人。读方：agent。**工作台表单模式与 Monaco 模式编辑的同一实体。**

```json
{
  "$schema": "equipmock/subgroup@1",
  "name": "cabinet",
  "description": "机柜电源相关 Mock",
  "mocks": [
    {
      "class": "com.equip.demo.PowerDevice",
      "method": "readStatus",
      "signature": "(ILjava/lang/String;)I",
      "description": "读取通道状态",
      "enabled": true,
      "defaultAction": { "type": "VALUE", "value": 0 },
      "rules": [
        {
          "matchType": "FULL_MATCH",
          "description": "通道1返回满量程",
          "args": [1, "CH1"],
          "action": { "type": "VALUE", "value": 5 }
        },
        {
          "matchType": "PATTERN_MATCH",
          "description": "异常通道模拟超时",
          "argsPattern": ["\\d+", "CH(9[0-9])"],
          "action": {
            "type": "THROW",
            "exception": "java.io.IOException",
            "message": "device timeout"
          }
        }
      ]
    },
    {
      "class": "com.equip.demo.PowerDevice",
      "method": "powerOn",
      "enabled": true,
      "rules": [],
      "defaultAction": { "type": "VOID" }
    }
  ]
}
```

### 3.1 字段语义

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| name | 否 | 小分组名（显示用，缺省=文件名去扩展名） |
| description | 否 | 人读备注 |
| mocks[].class | 是 | 目标类 FQCN（精确匹配） |
| mocks[].method | 是 | 方法名 |
| mocks[].signature | 否 | JVM descriptor（区分重载）。缺省=作用于同名全部重载 |
| mocks[].enabled | 是 | false=该 mock 项整体不生效（索引期跳过） |
| mocks[].rules[] | 是(可空) | 匹配规则，**数组顺序即优先级，first-match** |
| mocks[].defaultAction | 否 | 规则全不命中时的兜底动作；无则 REAL |
| rules[].matchType | 是 | `FULL_MATCH` \| `PATTERN_MATCH` |
| rules[].args | FULL_MATCH 必填 | JSON 数组，与实参逐位深度相等比较（值编码见 3.2） |
| rules[].argsPattern | PATTERN_MATCH 必填 | 字符串数组，每元素为合法正则，对每参规范串 `matches()` |
| rules[].action / defaultAction | — | `{type:VALUE,value}` \| `{type:THROW,exception,message?}` \| `{type:VOID}` |

> `FixedValue` 在本 Schema 中表达为"空 rules + defaultAction=VALUE"（语义等价、结构统一，工作台表单里仍按三种匹配方式呈现给用户）。

### 3.2 参数/返回值 JSON 编码（与 03 §5 对应）

| Java 侧 | JSON 写法 |
| --- | --- |
| int/long/double/boolean… | 数字字面量 / true / false |
| String | 字符串字面量 |
| null | `null` |
| enum | 字符串（name） |
| byte[] | `{ "$hex": "A1B2FF" }` 或 `{ "$b64": "…" }` |
| char[] | `{ "$hex": "0041…" }`（UTF-16 码元 hex） |
| 数组 / List | JSON 数组 |
| Map | JSON 对象（key 转 String） |
| 简单 POJO | JSON 对象（字段名=属性名） |

校验规则（两侧一致，agent 加载期 / 工作台保存期都执行）：见 03 §6。**工作台保存时必须先过同一套校验再落盘**，保证 agent 读到的文件永远合法（agent 侧校验仍保留，容错手动编辑）。

## 4. plugins/plugin-registry.json（插件清单，唯一事实源）

写方：工作台 / 人。读方：agent。

```json
{
  "$schema": "equipmock/plugin-registry@1",
  "plugins": [
    {
      "id": "mock-cabinet",
      "alias": "机柜电源Mock",
      "jar": "mock-cabinet-1.0.0.jar",
      "enabled": true,
      "importedAt": "2026-08-19T10:00:00+08:00",
      "note": "随平台示例"
    }
  ]
}
```

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| id | 是 | 必须与 jar MANIFEST `Plugin-Id` 一致（工作台导入时从 jar 读取校验） |
| jar | 是 | 相对 plugins/ 的文件名；文件缺失 → state 标 MISSING |
| enabled | 是 | 路由开关。true=启用（已加载插件的规则参与路由） |
| alias / note / importedAt | 否 | 展示用 |

语义（D8/D9/D15）：

- 目录中**不在清单内的 jar 一律不加载**。
- `enabled` 变化 → agent 改 MockPoint.pluginEnabled（即时生效，无字节码操作）。
- 新增条目 → agent 热导入（retransform 补齐，见 02 §6.2）；删除条目 → 卸载路由（不回滚字节码）。

## 5. settings/registry/小分组 共同的写协议（原子写）

1. 写 `<file>.tmp-<6位随机>`；
2. `Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE)`（Windows/NTFS 原子）；
3. 任何情况下不得直接覆写目标文件；
4. agent 忽略 `*.tmp*` 与 `*.bak`。

## 6. state.json（Agent 专写，只读展示）

```json
{
  "$schema": "equipmock/state@1",
  "agentVersion": "1.0.0",
  "pid": 12345,
  "startedAt": "2026-08-19T09:00:00+08:00",
  "lastWriteAt": "2026-08-19T11:20:03+08:00",
  "activeGroup": "default",
  "mockEnabled": true,
  "instrumentedClasses": 12,
  "groupFiles": { "cabinet": 6, "radar": 3 },
  "plugins": [
    {
      "id": "mock-cabinet",
      "version": "1.0.0",
      "state": "STARTED",
      "mockPoints": 6,
      "error": null
    }
  ],
  "lastError": {
    "time": "2026-08-19T11:20:03+08:00",
    "file": "config/groups/default/cabinet.json",
    "message": "rules[1].argsPattern[0] 非法正则: ..."
  },
  "needsRestart": []
}
```

- `plugins[].state`: `STARTED` | `RESOLVED`（加载未启用）| `DISABLED` | `MISSING`（清单有 jar 无）| `REJECTED`（版本硬校验失败，error 说明）| `FAILED`（异常，error 含原因）。
- `needsRestart`: 字符串数组，热导入 retransform 失败需重启宿主才生效的类名。
- 工作台状态面板轮询此文件（1s）+ `logs/` 下 `agent.log*`（滚动文件带代号）尾部（见 06 §7）。
- agent 对 state.json 自身同样走原子写；若文件被占用写失败，重试 3 次后仅记日志（绝不影响宿主运行）。

## 7. 变更语义总表（Agent 监听反应）

| 文件/事件 | Agent 反应 | 生效时机 |
| --- | --- | --- |
| settings.json（activeGroup 变） | 加载新组快照，成功才切换 | 防抖 500ms 内 |
| settings.json（mockEnabled 变） | 改 MockRouter.globalEnabled | 即时 |
| 活动组内任一 json | 重建整组快照（组级原子） | 防抖 500ms 内 |
| 非活动组内 json | 仅文件保存（切换时才加载） | — |
| plugin-registry.json | diff 增/删/启停 → 加载/卸载/开关 | 防抖 500ms 内 |
| plugins/ 下 jar 增删 | 不直接反应（以清单为准） | — |
| 任意解析失败 | 保留旧内存状态 + state.lastError | — |

## 8. 兜底轮询

WatchService 之外，agent 每 5s 扫描上述文件 mtime+size 兜底（防 Windows 偶发丢事件）；两通道触发同一重载入口（幂等：快照未变不替换、state 不重写）。
