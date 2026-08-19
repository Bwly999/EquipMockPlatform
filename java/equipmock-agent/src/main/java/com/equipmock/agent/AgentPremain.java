package com.equipmock.agent;

import com.google.gson.JsonObject;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * agent premain 入口（02 §3）。M1 范围：参数解析 → home 骨架 → JUL 日志 →
 * bootstrap 兜底加载 → Spy.HANDLER 注入 → ByteBuddy 插桩注册（硬编码路由）→
 * 写 state.json。配置中心/FileWatcher/PF4J 插件装载属 M2/M3。
 *
 * <p>启动失败语义：任一步失败记录日志并写入 state.json.lastError，
 * <b>绝不抛出异常阻断宿主启动</b>（Mock 平台故障不应导致装备软件无法运行）。
 */
public final class AgentPremain {

    /** MANIFEST 未提供版本时的兜底常量 */
    private static final String FALLBACK_VERSION = "1.0.0-SNAPSHOT";

    private AgentPremain() {
    }

    /**
     * JVM -javaagent 入口。
     *
     * @param agentArgs -javaagent:xxx.jar=args（M1 未定义参数，忽略）
     * @param inst Instrumentation 实例
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        Logger consoleFallback = Logger.getLogger(AgentLogging.LOGGER_NAME);
        try {
            // 1. 定位并初始化 home 骨架（-Dequipmock.home，默认 ./equip-mock）
            AgentHome home = AgentHome.prepare(System.getProperty("equipmock.home"));

            // 2. 初始化 JUL → logs/agent.log（1MB x 5 滚动，UTF-8）
            Logger log = AgentLogging.initialize(home.logsDir());

            // 3. 读取 settings（损坏文件时 readSettings 内部降级为默认值）
            AgentHome.Settings settings = home.readSettings();
            JsonObject lastError = null;

            log.info("equipmock agent starting: home=" + home.root()
                    + ", activeGroup=" + settings.activeGroup
                    + ", mockEnabled=" + settings.mockEnabled);

            // 4. bootstrap 兜底加载（对 02 §3 的增强，见 BootstrapLoader 说明）
            boolean contractVisible = BootstrapLoader.ensureContractVisible(inst, log);

            int instrumentedClasses = 0;
            if (!contractVisible) {
                // 契约类不可见：无法注入 handler、无法插桩——降级为纯观察模式，不阻断宿主
                lastError = StateWriter.errorObject("equip-mock-bootstrap.jar",
                        BootstrapLoader.readableMissingJarMessage());
                log.severe("agent degraded: mock disabled, host continues un-instrumented");
            } else {
                // 5. 硬编码路由表（M1 数据源；M2/M3 替换为配置中心/插件 MockPoint 组合）
                HardcodedRouteTable routeTable = new HardcodedRouteTable(log);

                // 6. 反射注入 Spy.HANDLER（必须早于任何插桩类被调用——premain 阶段宿主 main 未执行）
                injectSpyHandler(log, routeTable, settings.mockEnabled);

                // 7. 插桩注册（精确类匹配 + 按返回类型分派 advice）
                Map<String, Set<String>> targets = new LinkedHashMap<String, Set<String>>();
                for (String className : routeTable.targetClasses()) {
                    targets.put(className, routeTable.methodNames(className));
                }
                InstrumentationListener listener =
                        InstrumentationRegistrar.register(inst, log, targets);
                instrumentedClasses = targets.size(); // M1 语义=注册数；实际织入数见日志
            }

            // 8. 写 state.json（STARTED 视图）
            StateWriter stateWriter = new StateWriter(home.stateFile(), log, resolveVersion());
            stateWriter.write(settings.activeGroup, settings.mockEnabled,
                    instrumentedClasses, lastError);
            log.info("equipmock agent premain completed");
        } catch (Throwable t) {
            // 兜底：任何未预期失败——日志 + stderr 提示 + 尽力写 state，绝不向上抛
            consoleFallback.severe("equipmock agent premain failed (host continues): " + t);
            t.printStackTrace();
            tryWriteDegradedState(t);
        }
    }

    /** 反射设置 Spy.HANDLER = handler（Spy 由 bootstrap loader 加载） */
    private static void injectSpyHandler(Logger log, RouteTable routeTable, boolean enabled)
            throws Exception {
        Class<?> spyClass = Class.forName("com.equipmock.bootstrap.Spy");
        Object handler = new AgentSpyHandler(log, routeTable, enabled);
        Field handlerField = spyClass.getField("HANDLER");
        handlerField.set(null, handler);
        log.info("Spy.HANDLER injected: " + handler.getClass().getName()
                + " (globalEnabled=" + enabled + ")");
    }

    /** agent 版本：优先 MANIFEST Implementation-Version */
    private static String resolveVersion() {
        try {
            Package pkg = AgentPremain.class.getPackage();
            String version = pkg != null ? pkg.getImplementationVersion() : null;
            if (version != null && version.length() > 0) {
                return version;
            }
        } catch (Throwable ignored) {
            // 无清单信息时走兜底
        }
        return FALLBACK_VERSION;
    }

    /** premain 整体失败后的兜底 state 写入（home 可能尚未就绪） */
    private static void tryWriteDegradedState(Throwable cause) {
        try {
            AgentHome home = AgentHome.prepare(System.getProperty("equipmock.home"));
            Logger log = AgentLogging.initialize(home.logsDir());
            StateWriter writer = new StateWriter(home.stateFile(), log, resolveVersion());
            writer.write("default", true, 0,
                    StateWriter.errorObject(null, "premain failed: " + cause));
        } catch (Throwable ignored) {
            // 连兜底都失败：只剩 stderr（已打印），不再传播
        }
    }
}
