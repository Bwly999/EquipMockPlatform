# 02 · Agent 核心设计

模块：`java/equipmock-bootstrap`、`java/equipmock-plugin-api`、`java/equipmock-agent`。

## 1. bootstrap jar（equip-mock-bootstrap.jar）

零第三方依赖，只含 4 个契约类型，包名 `com.equipmock.bootstrap`。**这几个类的二进制契约一旦发布不得变更**（插桩代码、agent、插件三方共享）。

```java
public final class Spy {
    /** premain 时由 agent 反射注入；volatile 保证对宿主线程立即可见 */
    public static volatile ISpyHandler HANDLER;

    public static Object[] NO_ARGS = new Object[0];

    /** 插桩代码的唯一入口。返回 MockResult；null = 放行真实方法 */
    public static Object/*MockResult*/ mock(String className, String methodName,
                                            String descriptor, Object self, Object[] args) {
        ISpyHandler h = HANDLER;
        if (h == null) return null;                    // agent 未就绪：安全放行
        return h.mock(className, methodName, descriptor, self, args);
    }
}

public interface ISpyHandler {
    Object/*MockResult*/ mock(String className, String methodName,
                              String descriptor, Object self, Object[] args);
}

/** 返回值载体。int code: 0=REAL(放行) 1=VALUE 2=THROW 3=VOID */
public final class MockResult {
    public static final int REAL = 0, VALUE = 1, THROW = 2, VOID = 3;
    public final int code;
    public final Object value;        // code=VALUE 时有效（类型已转换为目标返回类型）
    public final Throwable throwable; // code=THROW 时有效
    public MockResult(int code, Object value, Throwable t) { ... }
    public static final MockResult REAL_RESULT = new MockResult(REAL, null, null);
}

/** advice 与 agent 共享的 int 常量（避免跨类加载器传枚举的切换编译问题） */
public final class EnterResult {
    public static final int REAL = 0, SKIP = 1;
}
```

> 说明：`Spy.mock` 与 `ISpyHandler` 使用 `Object` 而非具体类型做部分参数/返回，是为了 advice 内联字节码最小化；`MockResult` 以具体类传递（advice 侧只读取 int 字段）。

## 2. plugin-api（并入 agent.jar，不 relocate）

包名 `com.equipmock.api`，是插件作者可见的全部平台 API：

```java
/** 标在 MockHandler 实现类上，声明拦截目标 */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface MockInterceptor {
    String[] targetClasses();          // 精确 FQCN，可多个
    String[] methods();                // 方法名，可多个；{"*"} 表示类中全部方法
    boolean matchOverloads() default true; // true=同签名规则作用于全部重载
}

/** 插件实现此接口并标注 @MockInterceptor + PF4J @Extension */
public interface MockHandler extends ExtensionPoint {
    /**
     * @return null → 交给配置中心规则；非 null → 按返回的 Outcome 执行
     */
    MockOutcome handle(MockInvocation invocation);
}

public final class MockInvocation {
    public final Object self;          // 静态方法时为 null
    public final String className, methodName, descriptor;
    public final Object[] args;
    public final Method reflectedMethod; // 目标 Method（可用来反射调用真实逻辑）
}

public final class MockOutcome {
    public static MockOutcome ofValue(Object v);
    public static MockOutcome ofVoid();                  // 吞掉真实调用
    public static MockOutcome ofThrow(Throwable t);
    public static MockOutcome passthrough();             // 显式放行（跳过配置中心）
}
```

插件模块以 **provided** scope 依赖 `equipmock-plugin-api`（PF4J 的 `@Extension/@ExtensionPoint` 同样 provided，运行时均由 agent jar 提供）。

## 3. premain 启动流程

```java
public class AgentPremain {
    public static void premain(String args, Instrumentation inst) {
        // 1. 定位主目录：-Dequipmock.home，默认 ./equip-mock；不存在则创建骨架
        //    （settings.json / plugin-registry.json / config/groups/default/ / logs/）
        // 2. 初始化 JUL → logs/agent.log（滚动：1MB×5）
        // 3. 注入桥接：反射设置 Spy.HANDLER = new AgentSpyHandler(...)
        // 4. ConfigCenter 启动：加载 settings → activeGroup → 全部小分组 → 构建不可变索引
        // 5. PluginService 启动（PF4J）：按 plugin-registry.json 清单加载启用插件
        //    （解析每个插件 @MockInterceptor → MockPoint 注册进 MockRouter）
        // 6. 插桩注册（ByteBuddy AgentBuilder，见 §4）
        // 7. FileWatcher 启动（WatchService + 防抖 + 兜底轮询，见 03 §5）
        // 8. 对"已被宿主加载的目标类"调用 inst.retransformClasses(...) 补齐
        //    （premain 时通常没有，防御性执行）
        // 9. 写 state.json（STARTED）
    }
}
```

启动顺序约束：`Spy.HANDLER` 注入必须早于任何插桩类被调用（premain 阶段宿主 main 未执行，天然满足）。启动期任一步失败：记录日志、state.json 写明错误，**不抛出阻断宿主启动**（Mock 平台故障不应导致装备软件无法运行）。

## 4. ByteBuddy 插桩

### 4.1 Advice 模板（agent 内，字节码被内联复制进目标类）

```java
public final class MockAdvice {
    @Advice.OnMethodEnter(skipOn = EnterResult.REAL)
    public static int/*EnterResult 常量，int 0=REAL 表示不跳过*/ enter(
            @Advice.This(optional = true) Object self,
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#d") String descriptor,   // JVM descriptor，如 (ILjava/lang/String;)I
            @Advice.AllArguments Object[] args) {
        Object r = Spy.mock(className, methodName, descriptor, self, args);
        if (r == null) return EnterResult.REAL;
        MockResult mr = (MockResult) r;
        if (mr.code == MockResult.REAL) return EnterResult.REAL;
        if (mr.code == MockResult.THROW) {           // 内联字节码中直接抛出
            SneakyThrow.raise(mr.throwable);          // 无需声明受检异常的抛出工具（见 §4.4）
            return EnterResult.REAL;
        }
        ThreadLocal<MockResult>Holder 局部? → 用 Advice.Local 传递
        return EnterResult.SKIP;                      // VALUE/VOID：跳过原方法
    }

    @Advice.OnMethodExit
    public static void exit(@Advice.Enter int enter,
                            @Advice.Local("MR") MockResult mr,
                            @Advice.Return(readOnly = false, typing = DO_NOT_STRICT?) Object ret) {
        if (enter == EnterResult.SKIP) {
            ret = mr.value;   // VALUE；VOID（返回类型为 void）时此句无效
        }
    }
}
```

实现要点（给开发 Agent 的落地说明，**M1 实测修正后的最终语义**）：

1. 插桩采用**两段式 enter+exit**：byte-buddy 1.9.16 实测发现 `skipOn=OnNonDefaultValue` 的跳过路径只执行"返回默认值"（如 `iconst_0; ireturn`），enter 返回值**不会**作为方法返回值回传。因此：enter 返回 Object（即 MockResult；null=REAL 不跳过原方法体，非 null=跳过；THROW 在 enter 内经 SneakyThrow 直接抛出）；exit 通过 `@Advice.Enter` 读回 MockResult，按 `@Advice.Return(readOnly=false)` 写回目标返回值（Object 模板加 `typing=DYNAMIC`；Void 模板的 exit 不带 Return 参数）。exit 在跳过/放行两条路径均会执行。
2. **advice 方法体只允许引用 bootstrap 契约类**（Spy/MockResult/EnterResult/SneakyThrow-复制进 bootstrap）——字节码会被复制进宿主类，引用其它 agent 类会 NoClassDefFoundError。
3. `SneakyThrow.raise` 放入 bootstrap jar：通过 JDK7 `invokeExact`/cast 技巧或经典 `thrower` 方法实现"无声明抛出受检异常"，保持插桩对宿主异常表透明。
4. `@Advice.Return` 对 void 方法：单独一个 `VoidAdvice`（exit 不写 ret）或 `readOnly` 处理，byte-buddy 1.9.16 对 void+`@Advice.Return` 组合会校验失败，需要按返回类型是否 void 生成/选择两个 advice 模板（`MockAdvice` / `MockVoidAdvice`）。
5. 基本类型返回值：`MockResult.value` 在 agent 侧已转换为目标返回类型（装箱形式），advice 的 `ret = mr.value` 依赖 byte-buddy 对 `@Advice.Return` 的自动拆箱 cast（1.9.16 支持；以 demo-host 覆盖 int/boolean/String/对象四种用例验证）。

### 4.2 AgentBuilder 配置

```java
new AgentBuilder.Default()
  .disableClassFormatChanges()                      // retransform 兼容（只加代码不改结构）
  .withRedefinitionStrategy(RETRANSFORM)
  .with(Listener → 日志+失败计数入 state.json)
  .ignore(nameStartsWith("com.equipmock.").or(nameStartsWith("java.").or(...JDK)))
  .type(isNamed(路由表中任一目标类))                  // 精确类名集合（ElementMatchers.named 逐个）
  .transform((builder, type, cl, pd, pd2) ->
      builder.visit(MockAdvice.toMethodsMatching(
          路由表中该类的方法名集合::contains))          // 只织入已声明的目标方法
      .onSameType(...) /* preserve void/basic 分流：实现时按方法返回类型分两个 advice，
                          用 AsmVisitorWrapper.forDeclaredMethods 分派 */)
  .installOn(inst);
```

- 只对**插件声明过的类/方法**织入；一个类即使部分方法被拦截，其余方法字节码不变。
- 目标类过滤用 `named(fqcn)` 精确匹配（首版不支持通配/前缀，避免误伤；后续可扩展注解属性）。
- 拦截 static 与实例方法（`@Advice.This(optional=true)`）；final 类/final 方法天然支持（advice 内联不生成子类）。

## 5. 路由与执行（AgentSpyHandler / MockRouter）

### 5.1 数据结构

```java
final class MockPoint {
    final String pluginId;
    final String className, methodName;   // descriptor 可空 = 同名全部重载
    volatile boolean pluginEnabled;       // ← plugin-registry.json 启停，D8 路由开关
    final MockHandler handler;            // 插件 @Extension 实例；无自定义逻辑时为 ConfigOnlyHandler.INSTANCE
}

final class MockRouter {
    // key = className + "#" + methodName + "#" + descriptor；descriptor 精确匹配
    // 另有 className + "#" + methodName + "#"（无签名条目）按"同名任意签名"兜底
    volatile Map<String, List<MockPoint>> table;      // 不可变 Map，整体替换
    volatile boolean globalEnabled = settings.mockEnabled;
}
```

### 5.2 调用路径（AgentSpyHandler.mock）

```
1. globalEnabled == false            → REAL
2. 查 table 无 MockPoint             → REAL
3. 逐 MockPoint：
     pluginEnabled == false          → 下一个 / 都禁用则 REAL
     handler.handle(inv)：
       返回 null                     → ConfigDrivenHandler.decide(methodId, args)
                                        （规则匹配逻辑见 03 §4，产出 action 或 null）
       返回 passthrough()            → REAL
       返回 VALUE/THROW/VOID         → 转成 MockResult（VALUE 需类型转换，见 03 §4.3；
                                        转换失败→日志+REAL）
4. 产出 MockResult（VALUE 的 value 必须已完成类型转换与装箱）
```

### 5.3 错误语义

- handler 抛出未捕获异常：日志记录（含堆栈），本次调用放行 REAL，**不影响后续调用**。
- 配置规则匹配/转换异常：同上，放行 REAL 并把错误写入 state.json.lastError。
- Agent 未就绪（HANDLER==null）：Spy 直接返回 null → REAL（宿主无感）。

## 6. 插件装载（PF4J 集成）

### 6.1 插件描述

插件 jar 的 MANIFEST.MF（PF4J ManifestPluginDescriptorFinder）：

```
Plugin-Id: mock-cabinet
Plugin-Version: 1.0.0
Plugin-Requires: equipmock >=1.0.0        # 自定义字段：平台版本范围，agent 硬校验（D19）
Plugin-Description: 机柜电源硬件 Mock
Plugin-Provider: xxx
```

agent 侧校验：解析 `Plugin-Requires`，用 PF4J `VersionManager` 比对平台版本；不满足 → 拒绝加载，state.json.plugins[].state=REJECTED，error 写明"requires equipmock>=x.y.z, current=a.b.c"。

### 6.2 生命周期与清单驱动

- `DefaultPluginManager(pluginsDir)`；**只加载 plugin-registry.json 中登记的 jar**（自定义 PluginLoader：先查清单，未登记的文件跳过）。清单与 jar 不一致（登记了但文件缺失）：state 标 MISSING，启动不中断。
- registry 中 `enabled=false` 的插件：仍 `loadPlugin + resolve`（类已加载、MockPoint 注册），但 `MockPoint.pluginEnabled=false`（D8：全量插桩 + 路由开关）。启动即停用的类同样织入 advice，运行期只查标志位。
- **运行期导入新插件**（监听 plugin-registry.json 变更，D9）：
  1. `loadPlugin/startPlugin`，注册 MockPoint，重建路由表；
  2. 对已在 JVM 中加载的目标类：`inst.retransformClasses(...)` 逐个补插桩（AgentBuilder 的 RETRANSFORM 策略复用同一 transformer 即可）；
  3. retransform 失败的类记入 state.json（`needsRestart: [类名]`），工作台状态面板提示重启生效。
- **卸载/删除**：`stopPlugin + unloadPlugin`，路由表移除该插件全部 MockPoint，registry 删条目；已织入的类**不回滚字节码**（其 MockPoint 已消失，调用自然 REAL）。jar 文件删除由工作台执行（agent 不删文件，只读 plugins 目录，写操作仅 state.json/日志）。

### 6.3 handler 发现

`pluginManager.getExtensions(MockHandler.class)`（PF4J @Extension 扫描），逐个读类上 `@MockInterceptor` 生成 MockPoint。注解缺失/`targetClasses` 空 → 插件加载失败，写 state.json。

## 7. 依赖隔离与 shade 规则（硬要求）

agent jar 位于 system classpath，与宿主共享命名空间，因此：

| 依赖 | 版本 | 处理 |
| --- | --- | --- |
| net.bytebuddy | 1.9.16 | shade + relocate → `io.equipmock.shaded.bytebuddy` |
| org.pf4j | 3.12.0 | shade 合并但**不 relocate**：plugin-api 的 `MockHandler extends org.pf4j.ExtensionPoint` 是插件编译期契约，relocate 会导致插件侧扩展点类型与 agent 侧不同名而无法加载。装备宿主自带 PF4J 的概率极低，可接受共存；若未来冲突再评估独立 ClassLoader 方案 |
| com.google.gson | 2.8.9 | shade + relocate → `io.equipmock.shaded.gson` |
| com.equipmock.**（bootstrap/plugin-api/agent 自身） | — | **绝不 relocate**（跨类加载器契约） |
| junit / testkit | test | 不入包 |

- `equip-mock-bootstrap.jar` 单独assembly 产出，**不 shade 不 relocate**，保持零依赖。
- MANIFEST 关键项：`Premain-Class`、`Boot-Class-Path: equip-mock-bootstrap.jar`、`Can-Retransform-Classes: true`、`Implementation-Version`。
- shade 后跑 `demo-host` 全量回归（03 §9 验收），并写断言测试扫描 agent jar 条目：**不得出现** `net/bytebuddy/**` 与 `com/google/gson/**` 原始包名（`org/pf4j/**` 按上表允许保留）。

## 8. state.json 回写时机（D13）

- 启动完成 / 插件列表或状态变化 / 配置组切换 / 配置重载（成功与失败）/ retransform 补齐结果。
- 写入同样走"临时文件 + 原子替换"；工作台只读。完整 Schema 见 04 §6。
