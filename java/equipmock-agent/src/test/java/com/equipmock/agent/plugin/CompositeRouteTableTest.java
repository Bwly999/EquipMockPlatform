package com.equipmock.agent.plugin;

import com.equipmock.agent.RouteTable;
import com.equipmock.api.MockHandler;
import com.equipmock.api.MockInvocation;
import com.equipmock.api.MockOutcome;
import com.equipmock.bootstrap.MockResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CompositeRouteTable 串联语义（02 §5.2 / 05 §3）：
 * handler null→配置、passthrough→REAL、VALUE 写死优先、异常降级 REAL、
 * 多插件注册序 first-match、启停开关、目标集合并集。
 */
class CompositeRouteTableTest {

    private static final Logger LOG = Logger.getLogger("test");

    /** 可编程 handler（记录调用） */
    private static final class ScriptedHandler implements MockHandler {
        MockOutcome result;
        RuntimeException boom;
        int calls;

        @Override
        public MockOutcome handle(MockInvocation inv) {
            calls++;
            if (boom != null) {
                throw boom;
            }
            return result;
        }
    }

    /** 配置中心分支桩：命中任意 lookup 返回固定 VALUE，并记录调用 */
    private static final class ConfigStub implements RouteTable {
        int calls;
        MockResult fixed = new MockResult(MockResult.VALUE, "CONFIG", null);

        @Override
        public MockResult lookup(String className, String methodName, String descriptor) {
            calls++;
            return fixed;
        }

        @Override
        public Set<String> targetClasses() {
            return new LinkedHashSet<String>(Arrays.asList("fixture.ConfigOnly"));
        }

        @Override
        public Set<String> methodNames(String className) {
            return "fixture.ConfigOnly".equals(className)
                    ? new LinkedHashSet<String>(Arrays.asList("cfgMethod")) : Collections.<String>emptySet();
        }
    }

    private static MockPoint point(String pluginId, String className, boolean enabled,
                                   MockHandler handler, String... methods) {
        return new MockPoint(pluginId, className,
                new LinkedHashSet<String>(Arrays.asList(methods)), enabled, handler);
    }

    private static CompositeRouteTable table(List<MockPoint> points, ConfigStub config) {
        final PluginRouting routing = PluginRouting.build(points);
        return new CompositeRouteTable(new PluginRouter() {
            @Override
            public PluginRouting routing() {
                return routing;
            }
        }, config, LOG);
    }

    private static final String DESCR = "()Ljava/lang/String;";

    @Test
    void handlerNullFallsThroughToConfig() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.result = null;
        ConfigStub config = new ConfigStub();
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "fixture.Target", true, handler, "ping")),
                config);
        MockResult r = t.lookup("fixture.Target", "ping", DESCR, new Object[0], null);
        assertEquals(MockResult.VALUE, r.code);
        assertEquals("CONFIG", r.value);
        assertEquals(1, handler.calls);
        assertEquals(1, config.calls);
    }

    @Test
    void passthroughSkipsConfig() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.result = MockOutcome.passthrough();
        ConfigStub config = new ConfigStub();
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "fixture.Target", true, handler, "ping")),
                config);
        assertNull(t.lookup("fixture.Target", "ping", DESCR, new Object[0], null));
        assertEquals(1, handler.calls);
        assertEquals(0, config.calls, "passthrough 必须跳过配置中心");
    }

    @Test
    void hardcodedValueTakesPrecedenceOverConfig() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.result = MockOutcome.ofValue("PLUGIN");
        ConfigStub config = new ConfigStub();
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "fixture.Target", true, handler, "ping")),
                config);
        MockResult r = t.lookup("fixture.Target", "ping", DESCR, new Object[0], null);
        assertEquals("PLUGIN", r.value);
        assertEquals(0, config.calls, "写死逻辑命中后不再走配置");
    }

    @Test
    void throwOutcomeCarriedDirectly() {
        IllegalStateException boom = new IllegalStateException("plugin-thrown");
        ScriptedHandler handler = new ScriptedHandler();
        handler.result = MockOutcome.ofThrow(boom);
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "fixture.Target", true, handler, "ping")),
                new ConfigStub());
        MockResult r = t.lookup("fixture.Target", "ping", DESCR, new Object[0], null);
        assertEquals(MockResult.THROW, r.code);
        assertEquals(boom, r.throwable);
    }

    @Test
    void handlerExceptionFallsBackRealNotConfig() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.boom = new RuntimeException("handler bug");
        ConfigStub config = new ConfigStub();
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "fixture.Target", true, handler, "ping")),
                config);
        assertNull(t.lookup("fixture.Target", "ping", DESCR, new Object[0], null),
                "02 §5.3：handler 异常 → 本次 REAL");
        assertEquals(0, config.calls);
        // 不影响后续调用
        handler.boom = null;
        handler.result = MockOutcome.ofValue("RECOVERED");
        MockResult r = t.lookup("fixture.Target", "ping", DESCR, new Object[0], null);
        assertEquals("RECOVERED", r.value);
    }

    @Test
    void multiplePluginsChainedInRegistrationOrder() {
        ScriptedHandler first = new ScriptedHandler();
        first.result = null;
        ScriptedHandler second = new ScriptedHandler();
        second.result = MockOutcome.ofValue("SECOND");
        ConfigStub config = new ConfigStub();
        List<MockPoint> points = new ArrayList<MockPoint>();
        points.add(point("p1", "fixture.Target", true, first, "ping"));
        points.add(point("p2", "fixture.Target", true, second, "ping"));
        CompositeRouteTable t = table(points, config);

        MockResult r = t.lookup("fixture.Target", "ping", DESCR, new Object[0], null);
        assertEquals("SECOND", r.value, "p1 null → p2 生效");
        assertEquals(0, config.calls);

        // p1 返回写死 → first-match
        first.result = MockOutcome.ofValue("FIRST");
        assertEquals("FIRST",
                t.lookup("fixture.Target", "ping", DESCR, new Object[0], null).value);
        assertEquals(1, second.calls, "first-match 生效后 p2 不再被调用（第 1 阶段 1 次 + 第 2 阶段 0 次）");
    }

    @Test
    void disabledPluginSkippedFallsToConfig() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.result = MockOutcome.ofValue("PLUGIN");
        ConfigStub config = new ConfigStub();
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "fixture.Target", false, handler, "ping")),
                config);
        MockResult r = t.lookup("fixture.Target", "ping", DESCR, new Object[0], null);
        assertEquals("CONFIG", r.value, "D8：pluginEnabled=false 跳过该插件");
        assertEquals(0, handler.calls);
    }

    @Test
    void methodNotDeclaredFallsToConfig() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.result = MockOutcome.ofValue("PLUGIN");
        ConfigStub config = new ConfigStub();
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "fixture.Target", true, handler, "ping")),
                config);
        MockResult r = t.lookup("fixture.Target", "other", DESCR, new Object[0], null);
        assertEquals("CONFIG", r.value, "未声明方法不经过插件");
        assertEquals(0, handler.calls);
    }

    @Test
    void wildcardMethodsMatchAll() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.result = MockOutcome.passthrough();
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "fixture.Target", true, handler, "*")),
                new ConfigStub());
        assertTrue(t.interceptAllMethods("fixture.Target"));
        assertFalse(t.interceptAllMethods("fixture.Other"));
        assertNull(t.lookup("fixture.Target", "anything", DESCR, new Object[0], null));
        assertNull(t.lookup("fixture.Target", "otherName", DESCR, new Object[0], null));
        assertEquals(2, handler.calls);
    }

    @Test
    void targetClassesAndMethodsAreUnionWithConfig() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.result = null;
        CompositeRouteTable t = table(Arrays.asList(
                point("p1", "fixture.PluginOnly", true, handler, "a"),
                point("p2", "fixture.ConfigOnly", true, handler, "b")),
                new ConfigStub());
        Set<String> classes = t.targetClasses();
        assertTrue(classes.contains("fixture.PluginOnly"));
        assertTrue(classes.contains("fixture.ConfigOnly"), "配置目标类并入插桩集合");
        assertEquals(new LinkedHashSet<String>(Arrays.asList("b", "cfgMethod")),
                t.methodNames("fixture.ConfigOnly"), "方法名并集");
        assertEquals(new LinkedHashSet<String>(Arrays.asList("a")),
                t.methodNames("fixture.PluginOnly"));
        assertEquals(2, t.targetClassCount(), "fixture.PluginOnly + fixture.ConfigOnly（去重）");
    }

    @Test
    void valueConversionPrimitivesAndMismatch() {
        // 真实可解析目标：java.lang.String#length()I —— 返回类型解析走反射
        ScriptedHandler numeric = new ScriptedHandler();
        numeric.result = MockOutcome.ofValue(7); // Integer → int
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "java.lang.String", true, numeric, "length")),
                new ConfigStub());
        MockResult r = t.lookup("java.lang.String", "length", "()I", new Object[0], null);
        assertEquals(MockResult.VALUE, r.code);
        assertEquals(Integer.valueOf(7), r.value);

        // Long 7 → 经 M2 转换器归一化为 Integer（数字窄化）
        numeric.result = MockOutcome.ofValue(7L);
        r = t.lookup("java.lang.String", "length", "()I", new Object[0], null);
        assertEquals(MockResult.VALUE, r.code);
        assertEquals(Integer.valueOf(7), r.value);

        // 无法转换（boolean 给 int）→ 日志 + REAL
        numeric.result = MockOutcome.ofValue(Boolean.TRUE);
        assertNull(t.lookup("java.lang.String", "length", "()I", new Object[0], null));
    }

    @Test
    void voidOutcomeOnlyOnVoidMethod() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.result = MockOutcome.ofVoid();
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "fixture.Target", true, handler, "ping")),
                new ConfigStub());
        // 非 void 方法配 VOID → 日志 + REAL
        assertNull(t.lookup("fixture.Target", "ping", DESCR, new Object[0], null));
        // void 方法配 VOID → MockResult.VOID
        assertEquals(MockResult.VOID,
                t.lookup("fixture.Target", "ping", "()V", new Object[0], null).code);
    }

    @Test
    void valueOnVoidMethodFallsBackReal() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.result = MockOutcome.ofValue("X");
        CompositeRouteTable t = table(
                Collections.singletonList(point("p1", "fixture.Target", true, handler, "ping")),
                new ConfigStub());
        assertNull(t.lookup("fixture.Target", "ping", "()V", new Object[0], null));
    }

    @Test
    void noPointsAtAllFallsToConfig() {
        ConfigStub config = new ConfigStub();
        CompositeRouteTable t = table(Collections.<MockPoint>emptyList(), config);
        assertEquals("CONFIG",
                t.lookup("fixture.Anything", "m", DESCR, new Object[0], null).value);
        assertEquals(1, config.calls);
    }
}
