# 03 · 配置中心设计

模块：`java/equipmock-agent` 内 `com.equipmock.agent.config` 包。职责：加载/监听/校验 `equip-mock/` 下的 json，构建**不可变**内存索引供 MockRouter 查询；只提供配置能力，某函数用配置还是写死逻辑由插件代码决定（init.md 约束）。

## 1. 内存模型（不可变 + 原子整体替换）

```
ConfigCenter
 ├─ volatile SettingsSnapshot settings      # activeGroup, mockEnabled（来自 settings.json）
 └─ volatile GroupSnapshot active           # 当前活动组的完整快照

GroupSnapshot（不可变对象，重载时整体 new + 赋值）
 ├─ String groupName
 ├─ Map<String/*subGroup*/, SubGroupFile> files
 └─ MockIndex index
      └─ Map<String/*methodId*/, List<MockEntry>>   # methodId = className#methodName#descriptor
                                                       或 className#methodName#（作用于全部重载）

MockEntry = 小分组配置中一个 "mocks[]" 元素解析后的不可变对象：
 { className, methodName, descriptor(可空), enabled, rules:List<Rule>, defaultAction(可空) }
Rule = { matchType: FULL_MATCH|PATTERN_MATCH, args / argsPattern, action }
Action = { type: VALUE|THROW|VOID, value?, exception?, message? }
```

- 查询路径无锁：`active` 是单个 volatile 引用，替换即生效（D11 first-match 语义在 §4）。
- `enabled=false` 的 MockEntry 在**构建索引时跳过**（不进入运行期判断，性能与语义都更干净）。
- 同一 methodId 出现在多个小分组文件：**全部保留**，查询时按"文件名自然序、文件内数组序"合并成一条规则链（组内可预测；工作台在保存时给出重复定义警告，见 06 §5.4）。

## 2. 启动加载流程

```
1. 读 settings.json（损坏→日志+state.lastError，按默认 {activeGroup:"default", mockEnabled:true}）
2. 枚举 config/groups/<activeGroup>/*.json（排除 *.tmp*）
3. 逐文件解析+Schema 校验（gson → MockEntry；校验规则见 §6）
   ├─ 全部成功 → 构建 GroupSnapshot，原子替换 active
   └─ 任一失败 → 整组拒绝加载：沿用上次快照（首启无快照→空组=全部 REAL），
        state.json.lastError = {file, line?, message, time}；成功的文件也不生效（组是原子单元）
4. state.json 写 activeGroup、各文件条目数、lastError
```

组级原子性是刻意决策：**宁可整组保持旧配置，也不让半新半旧的规则混跑**。

## 3. 配置组切换（settings.json 联动，D10/D11）

- 监听到 settings.json 变化且 activeGroup 指向不同组 → 按 §2 加载新组 → 成功才替换 `active` 并回写 state；失败保持旧组 + lastError。
- 组目录被删除时切组失败同上。
- 工作台侧切换语义（06 §5.2）：先确保目标组文件合法（保存时已校验），再写 settings.json，因此正常运行时切换总是成功。

## 4. 匹配引擎（ConfigDrivenHandler.decide）

输入：`methodId`（运行期真实签名）、`args[]`；输出：Action 或 null（null=REAL）。

```
entries = active.index.lookup(methodId)
         精确签名命中；否则查 className#methodName#（无签名条目）
for entry in entries:                    # 多小分组按合并序
    for rule in entry.rules:             # 数组顺序 first-match（D11）
        if rule.matchType == FULL_MATCH
              && 深度相等(rule.args, 规范化(args))      → return rule.action
        if rule.matchType == PATTERN_MATCH
              && 每参数 regex(rule.argsPattern[i]).matches(str(args[i])) → return rule.action
    if entry.defaultAction != null → return entry.defaultAction
return null                              # REAL
```

规则细节：

- **FULL_MATCH**：`rule.args` 数组长度必须等于实参数量（不等=不匹配）；逐参数深度相等（null==null、基本值按值、String equals、byte[] 逐字节、数组/List/Map 递归、POJO 走 §5 序列化串比较）。
- **PATTERN_MATCH**：`argsPattern` 长度等于实参数量；每参数先按 §5 转规范字符串再 `java.util.regex` 全匹配（`matches()`）。正则预编译缓存于 Rule 对象；非法正则在加载期校验拒绝。
- 参数中含 `null`：FULL_MATCH 写 JSON null；PATTERN_MATCH 该参数约定串为字符串 `"null"`。
- **匹配失败类型**（args 数量不符、规则 action 非法等）在**加载期**即拒绝整文件（见 §6），不留给运行期。

### 4.3 Action 执行

| type | 字段 | 执行 |
| --- | --- | --- |
| VALUE | `value`（json 字面量） | 按 Method 返回类型经 ValueConverter 反序列化（§5.2）；void 方法上配置 VALUE=加载期校验错误 |
| THROW | `exception`（FQCN，需有无参或 String 构造）、`message` | 反射实例化；实例化失败→加载期不报（类可能宿主才有），运行期失败→日志+REAL |
| VOID | — | 跳过真实调用（仅 void 方法；非 void 方法上配置 VOID=加载期校验错误） |

## 5. 类型转换器（ValueConverter，D12）

### 5.1 参数规范化字符串（供 FULL_MATCH 比较 / PATTERN_MATCH）

| 运行时类型 | 规范串 |
| --- | --- |
| null | `null` |
| Boolean/Character/Number | `String.valueOf` |
| String | 原文 |
| byte[]/char[] | hex 串（`A1B2…`） |
| enum | `name()` |
| 数组/List/Map/POJO | gson 序列化 JSON（`JsonPrimitive` 之外统一走 gson；**序列化结果做 key 排序**以保证稳定） |
| 其它（流/句柄/未知对象） | `类名@identityHash`（此类参数只适合 PATTERN_MATCH 对类名部分匹配；文档引导插件写死） |

规范串截断上限 4096 字符（防超长参数拖垮匹配）。

### 5.2 JSON 字面量 → 目标类型（返回值/FULL_MATCH 配置侧）

```
支持：8种基本类型及包装（含数字窄化：5.0→int 5）、String、enum(by name)、
     byte[]（"$hex":"A1B2" / "$b64":"…"）、char[]（"$hex"）、
     数组（元素类型取 Method.getGenericReturnType/ParameterTypes 的组件类型）、
     java.util.List/ArrayList、java.util.Map/HashMap、
     POJO：公有无参构造 + 按 json 字段名反射 setter/字段注入（递归同规则，不支持嵌套泛型）
不支持：上述之外（接口/抽象/嵌套泛型集合元素等）→ 加载期标注，
     运行期命中该条 → 日志"类型转换失败，已放行" + REAL，state.lastError 记录
```

- POJO 字段类型解析失败不阻断加载（标记该 entry 有转换告警），因为类可能由宿主提供、agent 冷启动时不可见——**延迟到首次命中时再解析并缓存**。
- byte[] 的 `$hex/$b64` 包装对象在 04 §3 Schema 中定义为「类型标签」编码。

## 6. 加载期校验（拒绝即整文件失败）

1. JSON 语法可解析（gson；错误含行列号）。
2. Schema 校验：必填字段、`matchType` 合法、FULL_MATCH 必须 `args`、PATTERN_MATCH 必须 `argsPattern` 且每个是**合法正则**、action 三选一字段完整、`signature`（若填）是合法 JVM descriptor（`Type.getDescriptor` 反向校验或手写正则）。
3. 语义校验：VALUE/VOID 与目标签名不匹配的情形无法在加载期知道（agent 不解析宿主类），仅做"VOID 不能配 value、THROW 的 exception 必须是 FQCN 格式"等静态检查；**运行期签名相关的错配在命中时按 §4.3/§5.2 放行 + 记错**。
4. 校验结果通过回写 state.json 的 `lastError`（含 file/message）暴露给工作台。

## 7. 文件监听协议（D14，两侧共同遵守）

Agent 侧实现：

```
FileWatcher
 ├─ WatchService（递归 watch config/groups/**、plugins/、settings.json 所在目录）
 ├─ 事件 → 500ms 防抖合并（同一文件多次事件只触发一次重载）
 ├─ 忽略 *.tmp* / *.bak 文件（写方临时文件协议，见 04 §7）
 ├─ ScheduledExecutorService 每 5s 兜底扫描全部契约文件 mtime+size
 │   （WatchService 在 Windows 偶发丢事件；mtime 兜底是可靠性保险）
 └─ 触发重载：settings→§3 切组；组内文件→重建该组快照（若=活动组则替换 active）；
     plugin-registry.json→通知 PluginService diff（02 §6.2）
```

写方协议（工作台/人手改文件都应遵守，agent 读取端已按此容错）：

1. 写 `<目标文件名>.tmp-<随机>` 临时文件 → `Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)`（NTFS 上原子）。
2. 禁止原地打开目标文件覆写（会产生半截 json 被监听到的竞态）。
3. Agent 解析失败时**保留旧内存配置**并记 lastError——不 crash、不部分应用。

## 8. 性能与安全边界

- 查询热路径：volatile 读 ×2 + HashMap get + 规则数组遍历（典型 1–5 条），无正则时零分配；PATTERN_MATCH 正则预编译。
- 监听线程与执行线程完全分离；所有快照对象字段 final。
- 配置文件数量级假设：每组 <20 文件、每文件 <200 mock 项（超过时工作台给出提示，不设硬限）。

## 9. 验收用例（demo-host + 插件联调时逐条过）

1. FixedValue：`readStatus` 配 VALUE 5 → 宿主拿到 5；删掉该条 → 拿到真实值。
2. FullMatch：args `[1,"CH1"]` 命中 VALUE 5，`[1,"CH2"]` 不命中走 defaultAction。
3. PatternMatch：`["\\d+","CH.*"]` 命中 THROW IOException("timeout")，宿主捕获校验 message。
4. rules 顺序 first-match：把一条更宽的 PatternMatch 放在精确 FullMatch 之前 → 宽先生效。
5. void 方法 VOID：`powerOn(int)` 配 VOID → 真实调用计数为 0（demo-host 打点验证）。
6. 组切换：fault-sim 组 → 切回 default，无重启 1s 内生效。
7. 坏配置：手动写入非法 json → 旧配置继续生效，state.json.lastError 可见。
8. mockEnabled=false 全局关：所有调用回到真实（即使规则命中）。
9. byte[] 参数 hex 匹配 + byte[] 返回 `$hex`。
10. POJO 返回值：demo-host `getDeviceStatus()` 返回 `DeviceStatus` POJO，配置按字段注入生效。
