# 05 · 插件开发指南与自测试（testkit）

面向两类读者：插件作者（用配置/写死逻辑完成 Mock）与平台开发 Agent（实现 plugin-api、PF4J 装载、testkit）。

## 1. 一个插件模块的结构

以示例插件 `java/plugins/plugin-mock-cabinet` 为例（Maven 模块）：

```xml
<artifactId>plugin-mock-cabinet</artifactId>
<dependencies>
  <!-- 关键依赖一律 provided：运行时由 agent jar 提供，不打进插件包（init.md：provide 作用域） -->
  <dependency>equipmock-plugin-api  <scope>provided</scope></dependency>
  <dependency>equipmock-testkit     <scope>test</scope></dependency>
  <dependency>pf4j                  <scope>provided</scope></dependency>
  <dependency>junit-jupiter         <scope>test</scope></dependency>
</dependencies>
<build>
  <!-- maven-jar-plugin 追加 MANIFEST：
       Plugin-Id / Plugin-Version / Plugin-Requires: equipmock >=1.0.0 /
       Plugin-Description；插件自身第三方依赖（如有）正常 compile 打入 -->
</build>
```

源码只需两类文件：

```java
// 1. PF4J 插件入口（可选，需要生命周期钩子时才写；无钩子可省——PF4J 支持 extension-only 插件）
public class CabinetPlugin extends Plugin {
    public CabinetPlugin(PluginWrapper wrapper) { super(wrapper); }
}

// 2. MockHandler：一个拦截目标一个（或多个）handler
@Extension
@MockInterceptor(
    targetClasses = "com.equip.demo.PowerDevice",
    methods = {"readStatus", "powerOn", "getDeviceStatus"})
public class PowerDeviceHandler implements MockHandler {

    @Override
    public MockOutcome handle(MockInvocation inv) {
        // 写死逻辑优先：例如用真实算法仿真
        if ("powerOn".equals(inv.methodName)) {
            return MockOutcome.ofVoid();
        }
        if ("readStatus".equals(inv.methodName) && simulatedBusy) {
            return MockOutcome.ofThrow(new IOException("cabinet busy"));
        }
        return null;   // ← 交给配置中心规则（默认路径，最常用）
    }
}
```

编译产出即插件 jar，复制到 `<equipmock.home>/plugins/` 并在 plugin-registry.json 登记后生效（通常由工作台完成导入动作）。

## 2. 开发决策：配置驱动 vs 写死逻辑

| 场景 | 建议 |
| --- | --- |
| 返回值/异常随参数变化、联调时常调 | handler 返回 **null**，全部交给配置中心规则（表单改完即生效） |
| 需要状态/算法仿真（如自增序号、故障注入逻辑） | handler 内写死，返回 `ofValue/ofThrow/ofVoid` |
| 同一方法部分场景写死、部分场景可调 | 写死逻辑判断后不满足时返回 null 落配置 |
| 明确要求跳过配置 | 返回 `passthrough()` |

init.md 约束原文：「只能提供配置中心功能；具体函数 Mock 用配置还是写死以用户代码为准」——即平台不替作者做选择，`return null` 与否就是选择本身。

## 3. 注解语义与限制

- `targetClasses`：精确 FQCN，首版不支持通配（防误伤；由 agent 侧 `ElementMatchers.named` 精确匹配）。
- `methods = {"*"}`：类中全部方法（含继承自父类的？——首版只匹配**声明于该类**的方法，`declaredOnly` 语义，文档明示）。
- 一个目标类可被多个插件声明；路由按插件注册顺序串联，**第一个返回非 null 结果的插件生效**（跨插件的 first-match，与组内规则 first-match 语义对齐）。
- `@MockInterceptor` 缺失或 targetClasses 为空的 @Extension(MockHandler)：插件加载失败（state.json 记 REJECTED/FAILED）。

## 4. equipmock-testkit（自测试，D17）

形态：**工具包 + surefire argLine 进程内挂载**——测试 JVM 自身带着 `-javaagent` 运行，测试代码直接调用目标类断言 Mock 生效，与真实运行方式一致。

### 4.1 提供物

```java
// EquipMockTestBase：JUnit5 扩展
public abstract class EquipMockTestBase {
    @BeforeAll static void init() {
        // 1. 定位 agent：系统属性 -Dequipmock.agent.jar（缺省 ../equipmock-agent/target/equip-mock-agent.jar）
        // 2. 创建临时 home：target/equipmock-test-home/<testClass>，写入骨架
        // 3. 写测试用小分组 json / plugin-registry.json（registerPlugin 帮助方法把本插件 jar 登记启用）
    }
    // 帮助方法：
    protected void writeSubGroup(String group, String file, String json);   // 原子写
    protected void setActiveGroup(String group);                            // 改 settings.json
    protected void awaitConfigApplied(long timeoutMs);                      // 轮询 state.json 直到 lastError==null && lastWriteAt 更新
    protected void enableMock(boolean on);                                  // settings.mockEnabled
}
```

自测试用 **failsafe（integration-test 阶段）**而非 surefire——插件自测必须 `registerPlugin` 本模块已打包的 jar，而 surefire test 阶段 jar 尚未生成；handler 纯逻辑单测仍走 surefire（不挂 agent）。两个插件模块的 pom 是该模式的模板：

```xml
<!-- failsafe: forkCount=1, reuseForks=false, 每测试类独立 JVM/home -->
<argLine>
  -javaagent:${equipmock.agent.jar}
  -Dequipmock.bootstrap.jar=${equipmock.bootstrap.jar}
  -Dequipmock.home=${project.build.directory}/equipmock-test-home/f${surefire.forkNumber}
</argLine>
```

关键事实（M3/M4 实测）：
- `META-INF/extensions.idx` 由 pf4j 3.12 自带的注解处理器在插件编译期自动生成（provided 依赖即可），无需手工维护。
- premain 时 registry 尚不存在（测试代码后跑）——依赖 agent 热导入在测试中生效：`registerPlugin` 后轮询 `state.plugins[].state==STARTED`。
- 测试与插件 handler 分属不同类加载器，静态标志不共享：跨加载器开关用系统属性（如 cabinet 的 `mock.cabinet.busy`）。

### 4.2 测试分层

| 层 | 目标 | 方式 |
| --- | --- | --- |
| agent 单元测试 | 转换器/匹配引擎/校验器 | 纯 JVM 单测，不起 agent |
| agent 集成测试 | 插桩/路由/配置重载/插件装载 | testkit 进程内挂载（agent 模块自身） |
| 插件自测试 | 本插件 handler + 目标类 | testkit 进程内挂载（每个插件模块） |
| 端到端 | 全链路 | demo-host 子进程 + 脚本断言输出（见 §5） |

### 4.3 插件自测试示例（plugin-mock-cabinet）

```java
class PowerDeviceHandlerTest extends EquipMockTestBase {
    @Test
    void fullMatchRule_returnsConfiguredValue() throws Exception {
        writeSubGroup("default", "cabinet.json", """
            { "name":"cabinet", "mocks":[ {
                "class":"com.equip.demo.PowerDevice", "method":"readStatus",
                "enabled":true, "rules":[
                  {"matchType":"FULL_MATCH","args":[1,"CH1"],
                   "action":{"type":"VALUE","value":5}} ] } ] }""");
        awaitConfigApplied(2000);
        assertEquals(5, new PowerDevice().readStatus(1, "CH1"));   // 真实实现返回 -1
    }

    @Test
    void handlerHardcodedLogic_takesPrecedenceOverConfig() { ... }
}
```

> 目标类（如 `PowerDevice`）对插件模块只是编译期依赖（provided/系统依赖）——测试 JVM 里它必须出现在 classpath；demo 场景直接 provided 依赖 demo-host 模块。

## 5. demo-host（示例宿主）

`java/demo-host`：无第三方依赖的控制台程序，模拟装备软件：

- `com.equip.demo.PowerDevice`：`readStatus(int,String):int`、`powerOn(int):void`、`getDeviceStatus():DeviceStatus(POJO)`、`send(byte[]):byte[]`（真实实现返回错误值/打印"真实硬件调用"，便于肉眼区分 Mock 生效）。
- `main`：循环（1s）调用上述方法并打印结果；支持 `-Dequipmock.home`；作为端到端验收载体（03 §9 十条用例的执行体，`scripts/e2e-check.ps1` 断言输出行）。
- 打点计数器：真实方法执行次数（验证 VOID 吞调用）。

## 6. 插件兼容性（D19）

- `Plugin-Requires: equipmock >=1.0.0 <2.0.0`（语义版本区间，语法 `>=,<=,<,>` 组合，空格 AND）。
- agent 启动/热导入时硬校验：不满足 → REJECTED + state.json error 写明"插件要求 x，平台为 y"。工作台导入时也读 MANIFEST 预校验并提示（提前拦截，工作台内不阻塞 agent 运行）。
- 平台 API 二进制兼容承诺：`com.equipmock.api.*` 与 `com.equipmock.bootstrap.*` 主版本内不变。
