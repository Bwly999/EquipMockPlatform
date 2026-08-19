package com.equipmock.agent;

import com.equipmock.agent.config.ConfigCenter;
import com.equipmock.agent.config.FileWatcher;
import com.equipmock.agent.config.MatchEngine;
import com.equipmock.agent.plugin.CompositeRouteTable;
import com.equipmock.agent.plugin.PluginService;
import com.google.gson.JsonObject;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.logging.Logger;

/**
 * agent premain 入口（02 §3）。M2 范围：参数解析 → home 骨架 → JUL 日志 →
 * bootstrap 兜底加载 → 配置中心（settings + 活动组快照，03 §2）→ Spy.HANDLER 注入 →
 * ByteBuddy 插桩注册（RouteTable 动态 matcher）→ FileWatcher 启动（03 §7）→
 * 写 state.json。PF4J 插件装载属 M3。
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
     * @param agentArgs -javaagent:xxx.jar=args（未定义参数，忽略）
     * @param inst Instrumentation 实例
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        Logger consoleFallback = Logger.getLogger(AgentLogging.LOGGER_NAME);
        try {
            // 1. 定位并初始化 home 骨架（-Dequipmock.home，默认 ./equip-mock）
            AgentHome home = AgentHome.prepare(System.getProperty("equipmock.home"));

            // 2. 初始化 JUL → logs/agent.log（1MB x 5 滚动，UTF-8）
            Logger log = AgentLogging.initialize(home.logsDir());

            // 3. 配置中心：加载 settings + 活动组 → 不可变快照（03 §2）
            StateWriter stateWriter = new StateWriter(home.stateFile(), log, resolveVersion());
            ConfigCenter configCenter = new ConfigCenter(home, log, stateWriter);
            configCenter.start();
            JsonObject lastError = configCenter.lastError();

            log.info("equipmock agent starting: home=" + home.root()
                    + ", activeGroup=" + configCenter.settings().activeGroup
                    + ", mockEnabled=" + configCenter.settings().mockEnabled);

            // 4. bootstrap 兜底加载（对 02 §3 的增强，见 BootstrapLoader 说明）
            boolean contractVisible = BootstrapLoader.ensureContractVisible(inst, log);

            int instrumentedClasses = 0;
            if (!contractVisible) {
                // 契约类不可见：无法注入 handler、无法插桩——降级为纯观察模式，不阻断宿主
                if (lastError == null) {
                    lastError = StateWriter.errorObject("equip-mock-bootstrap.jar",
                            BootstrapLoader.readableMissingJarMessage());
                }
                log.severe("agent degraded: mock disabled, host continues un-instrumented");
            } else {
                // 5. 插件服务（M3，02 §3 第 5 步）：清单驱动 PF4J 装载 + MockPoint 注册；
                //    state 的 plugins[]/needsRestart 数据源在装载前注入
                PluginService pluginService = new PluginService(home, log, resolveVersion(),
                        configCenter::refreshState, configCenter::reportRuntimeError);
                stateWriter.setPluginsSupplier(pluginService::statuses);
                stateWriter.setNeedsRestartSupplier(pluginService::needsRestart);
                pluginService.setInstrumentation(inst);
                pluginService.start();

                // 6. 组合路由表（M3，02 §5.2）：插件 handler（写死优先）→ 配置中心规则
                MatchEngine engine = new MatchEngine(log, configCenter::reportRuntimeError);
                ConfigCenterRouteTable configTable =
                        new ConfigCenterRouteTable(configCenter, engine);
                CompositeRouteTable routeTable =
                        new CompositeRouteTable(pluginService, configTable, log);
                configCenter.setInstrumentedClassCount(routeTable::targetClassCount);

                // 7. 反射注入 Spy.HANDLER（必须早于任何插桩类被调用——premain 阶段宿主 main 未执行）
                //    注意：本方法签名不得出现 AgentSpyHandler（其实现 bootstrap 的 ISpyHandler，
                //    签名引用会在 premain 类加载期触发解析，拔 bootstrap 场景将 NoClassDefFoundError）
                injectSpyHandler(log, routeTable, configCenter,
                        configCenter.settings().mockEnabled);

                // 8. 插桩注册（RouteTable 动态 matcher：配置/插件新增的未加载类首次加载即织入）
                InstrumentationRegistrar.register(inst, log, routeTable);
                instrumentedClasses = routeTable.targetClassCount();

                // 9. 目标类变更监控（已加载类记 info：重启生效，M3 retransform 解决）
                TargetClassChangeMonitor monitor =
                        new TargetClassChangeMonitor(inst, routeTable, log);
                monitor.initBaseline();
                configCenter.setGroupReloadListener(monitor);

                // 10. FileWatcher 启动（03 §7：settings 切组/开关、活动组重建、registry diff）
                FileWatcher watcher = new FileWatcher(home.root(), new FileWatcher.Listener() {
                    @Override
                    public void onSettingsChanged(java.nio.file.Path settingsFile) {
                        configCenter.onSettingsFileChanged();
                    }

                    @Override
                    public void onGroupFileChanged(String group, java.nio.file.Path file) {
                        configCenter.onGroupFileChanged(group);
                    }

                    @Override
                    public void onPluginRegistryChanged(java.nio.file.Path registryFile) {
                        pluginService.onRegistryChanged();
                    }
                }, log);
                watcher.start();
            }

            // 10. 写 state.json（02 §8：启动完成视图）
            stateWriter.writeState(configCenter.settings().activeGroup,
                    configCenter.settings().mockEnabled,
                    instrumentedClasses,
                    configCenter.activeGroup().entryCounts(),
                    lastError);
            log.info("equipmock agent premain completed");
        } catch (Throwable t) {
            // 兜底：任何未预期失败——日志 + stderr 提示 + 尽力写 state，绝不向上抛
            consoleFallback.severe("equipmock agent premain failed (host continues): " + t);
            t.printStackTrace();
            tryWriteDegradedState(t);
        }
    }

    /**
     * 反射设置 Spy.HANDLER = handler（Spy 由 bootstrap loader 加载），
     * 同时装配 mockEnabled 即时开关回调。方法体执行时 bootstrap 契约已确认可见。
     */
    private static void injectSpyHandler(Logger log, RouteTable routeTable,
                                         ConfigCenter configCenter, boolean enabled)
            throws Exception {
        Class<?> spyClass = Class.forName("com.equipmock.bootstrap.Spy");
        AgentSpyHandler handler = new AgentSpyHandler(log, routeTable, enabled);
        Field handlerField = spyClass.getField("HANDLER");
        handlerField.set(null, handler);
        configCenter.setGlobalEnabledCallback(handler::setGlobalEnabled);
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
