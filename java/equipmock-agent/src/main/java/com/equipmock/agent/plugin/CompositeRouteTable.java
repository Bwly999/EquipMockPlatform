package com.equipmock.agent.plugin;

import com.equipmock.agent.RouteTable;
import com.equipmock.api.MockInvocation;
import com.equipmock.api.MockOutcome;
import com.equipmock.bootstrap.MockResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 组合路由表（M3，02 §5.2 调用路径）：插件 handler（写死逻辑优先）→
 * 配置中心规则（first-match）。
 *
 * <ul>
 *   <li>逐 MockPoint 按插件注册序串联（05 §3 跨插件 first-match）：
 *       {@code pluginEnabled=false} 跳过；{@code handle} 返回 null → 下一个插件，
 *       全部 null 时落 {@code configRoute}；返回 {@code passthrough()} → 直接 REAL
 *       （跳过配置中心）；返回 VALUE/THROW/VOID → 经 {@link OutcomeConverter} 转换；</li>
 *   <li>handler 抛异常 → 日志 + <b>本次 REAL</b>，不影响后续调用（02 §5.3）；</li>
 *   <li>targetClasses/methodNames：插件声明 ∪ 配置派生——插桩 matcher 动态查询本表，
 *       插件新增的未加载类首次加载即被织入，已加载类由 PluginService retransform 补齐。</li>
 * </ul>
 */
public final class CompositeRouteTable implements RouteTable {

    private final PluginRouter pluginRouter;
    private final RouteTable configRoute;
    private final OutcomeConverter converter;
    private final Logger log;

    public CompositeRouteTable(PluginRouter pluginRouter, RouteTable configRoute,
                               Logger log) {
        this.pluginRouter = pluginRouter;
        this.configRoute = configRoute;
        this.converter = new OutcomeConverter(log);
        this.log = log;
    }

    @Override
    public MockResult lookup(String className, String methodName, String descriptor,
                             Object[] args, Object self) {
        PluginRouting routing = pluginRouter.routing();
        List<MockPoint> points = routing.points(className);
        if (points != null && !points.isEmpty()) {
            Object[] safeArgs = args == null ? new Object[0] : args;
            for (MockPoint point : points) {
                if (!point.matchesMethod(methodName)) {
                    continue;
                }
                MockOutcome outcome;
                try {
                    outcome = point.handler.handle(new MockInvocation(self, className,
                            methodName, descriptor, safeArgs,
                            converter.resolveMethod(className, methodName, descriptor)));
                } catch (Throwable t) {
                    // 02 §5.3：handler 异常 → 本次放行 REAL，不影响后续调用
                    log.warning("plugin handler " + point.pluginId + " threw on "
                            + className + "#" + methodName + descriptor
                            + ", falling back to REAL: " + t);
                    return null;
                }
                if (outcome == null) {
                    continue; // 交给下一个插件 → 最终配置中心规则
                }
                if (outcome.getType() == MockOutcome.Type.PASSTHROUGH) {
                    return null; // 显式放行真实方法（跳过配置中心）
                }
                MockResult result = converter.convert(outcome, className, methodName,
                        descriptor);
                if (result == null) {
                    return null; // 转换失败/签名错配：日志 + REAL
                }
                return result;
            }
        }
        return configRoute.lookup(className, methodName, descriptor, args);
    }

    @Override
    public MockResult lookup(String className, String methodName, String descriptor) {
        return lookup(className, methodName, descriptor, new Object[0], null);
    }

    @Override
    public MockResult lookup(String className, String methodName, String descriptor,
                             Object[] args) {
        return lookup(className, methodName, descriptor, args, null);
    }

    @Override
    public Set<String> targetClasses() {
        PluginRouting routing = pluginRouter.routing();
        Set<String> union = new LinkedHashSet<String>(routing.targetClasses());
        union.addAll(configRoute.targetClasses());
        return union;
    }

    @Override
    public Set<String> methodNames(String className) {
        PluginRouting routing = pluginRouter.routing();
        Set<String> union = new LinkedHashSet<String>(routing.methodNames(className));
        union.addAll(configRoute.methodNames(className));
        return union;
    }

    @Override
    public boolean interceptAllMethods(String className) {
        return pluginRouter.routing().allMethods(className);
    }

    /** 目标类计数（state.instrumentedClasses 数据源 = 插件声明 ∪ 配置派生） */
    public int targetClassCount() {
        return targetClasses().size();
    }
}
