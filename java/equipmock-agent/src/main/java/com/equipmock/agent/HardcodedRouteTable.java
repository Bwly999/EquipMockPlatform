package com.equipmock.agent;

import com.equipmock.bootstrap.MockResult;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * M1 硬编码路由表（M1-4）：以 {@code 类名#方法名#描述符} 为键的写死 Mock 预设。
 *
 * <p>仅用于打通"字节码插桩 → bootstrap Spy → agent 路由 → MockResult"链路；
 * M2 配置中心（FixedValue/FullMatch/PatternMatch）与 M3 插件 MockHandler 将以
 * {@link RouteTable} 新实现替换本数据源（见 07-roadmap M1-4 "本任务允许写死一个 map"）。
 *
 * <p>键格式：{@code className#methodName#descriptor}，descriptor 为 JVM 描述符
 * （斜杠形式，如 {@code (ILjava/lang/String;)I}）。
 */
public final class HardcodedRouteTable implements RouteTable {

    private static final String KEY_SEPARATOR = "#";

    private final Map<String, MockResult> routes = new LinkedHashMap<String, MockResult>();
    private final Map<String, Set<String>> classToMethods = new LinkedHashMap<String, Set<String>>();

    public HardcodedRouteTable(Logger log) {
        // ---- M1 demo 预设（对应 docs/05 §5 demo-host）----
        // 基本类型/字符串/数组直接预构建
        add("com.equip.demo.PowerDevice", "readStatus", "(ILjava/lang/String;)I",
                new MockResult(MockResult.VALUE, Integer.valueOf(5), null));
        add("com.equip.demo.PowerDevice", "isOnline", "()Z",
                new MockResult(MockResult.VALUE, Boolean.TRUE, null));
        add("com.equip.demo.PowerDevice", "getName", "()Ljava/lang/String;",
                new MockResult(MockResult.VALUE, "MOCK-DEVICE", null));
        add("com.equip.demo.PowerDevice", "send", "([B)[B",
                new MockResult(MockResult.VALUE, new byte[]{1, 2, 3}, null));
        add("com.equip.demo.PowerDevice", "powerOn", "(I)V",
                new MockResult(MockResult.VOID, null, null));
        // POJO 返回值：agent 不编译依赖 demo-host，反射构造（运行期目标类在宿主 classpath 上）
        MockResult deviceStatus = reflectPojoValue(log, "com.equip.demo.DeviceStatus",
                Boolean.TRUE, Integer.valueOf(220), Integer.valueOf(11));
        if (deviceStatus != null) {
            add("com.equip.demo.PowerDevice", "getDeviceStatus", "()Lcom/equip/demo/DeviceStatus;",
                    deviceStatus);
        }
        log.info("HardcodedRouteTable initialized: " + routes.size() + " routes, "
                + classToMethods.size() + " target classes");
    }

    /** 注册一条路由；结果为 null 的条目跳过（保持 REAL） */
    private void add(String className, String methodName, String descriptor, MockResult result) {
        if (result == null) {
            return;
        }
        routes.put(routeKey(className, methodName, descriptor), result);
        Set<String> methods = classToMethods.get(className);
        if (methods == null) {
            methods = new LinkedHashSet<String>();
            classToMethods.put(className, methods);
        }
        methods.add(methodName);
    }

    /** 反射构造 POJO Mock 值；目标类不存在（如 agent 自测试环境）时返回 null 降级 */
    private static MockResult reflectPojoValue(Logger log, String fqcn, Object... ctorArgs) {
        try {
            Class<?> type = Class.forName(fqcn);
            for (Constructor<?> ctor : type.getConstructors()) {
                if (ctor.getParameterTypes().length == ctorArgs.length) {
                    Object value = ctor.newInstance(ctorArgs);
                    return new MockResult(MockResult.VALUE, value, null);
                }
            }
            log.warning("no matching constructor for hardcoded POJO mock: " + fqcn);
            return null;
        } catch (Throwable t) {
            // 目标类不在 classpath（如单测环境）属预期：该点保持 REAL
            log.info("hardcoded POJO mock unavailable (target class not on classpath?): " + fqcn
                    + " - " + t);
            return null;
        }
    }

    static String routeKey(String className, String methodName, String descriptor) {
        return className + KEY_SEPARATOR + methodName + KEY_SEPARATOR + descriptor;
    }

    @Override
    public MockResult lookup(String className, String methodName, String descriptor) {
        return routes.get(routeKey(className, methodName, descriptor));
    }

    @Override
    public Set<String> targetClasses() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(classToMethods.keySet()));
    }

    @Override
    public Set<String> methodNames(String className) {
        Set<String> methods = classToMethods.get(className);
        return methods == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(methods));
    }
}
