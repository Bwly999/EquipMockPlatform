package com.equipmock.agent;

import com.equipmock.agent.config.ConfigCenter;
import com.equipmock.agent.config.GroupSnapshot;
import com.equipmock.agent.config.MatchEngine;
import com.equipmock.bootstrap.MockResult;

import java.util.Set;

/**
 * 配置中心路由表（M2-6）：以 {@link ConfigCenter} 的活动组快照为唯一数据源，
 * 替换 M1 的 HardcodedRouteTable。
 *
 * <ul>
 *   <li>{@link #lookup}：委托 {@link MatchEngine}（FULL_MATCH/PATTERN_MATCH/
 *       defaultAction/first-match，03 §4）；VALUE 已在产出 MockResult 前完成类型转换
 *       与装箱，转换失败 → 日志 + REAL。</li>
 *   <li>{@link #targetClasses()} / {@link #methodNames(String)}：从活动组配置的
 *       enabled mocks 派生——InstrumentationRegistrar 的插桩 matcher 动态查询本表，
 *       配置中新增的未加载类首次加载即被织入；已被加载的类由
 *       TargetClassChangeMonitor 记 info 日志（M3 retransform 解决）。</li>
 * </ul>
 *
 * <p>M3 预留：插件 MockPoint 将以「插件 handler（写死逻辑优先）→ 本表（配置规则）」
 * 的组合 RouteTable 形式接入（02 §5.2 调用路径），本类保持为配置分支的委托实现。
 */
public final class ConfigCenterRouteTable implements RouteTable {

    private final ConfigCenter configCenter;
    private final MatchEngine engine;

    public ConfigCenterRouteTable(ConfigCenter configCenter, MatchEngine engine) {
        this.configCenter = configCenter;
        this.engine = engine;
    }

    @Override
    public MockResult lookup(String className, String methodName, String descriptor) {
        return lookup(className, methodName, descriptor, new Object[0]);
    }

    @Override
    public MockResult lookup(String className, String methodName, String descriptor,
                             Object[] args) {
        GroupSnapshot snapshot = configCenter.activeGroup();
        return engine.decide(snapshot, className, methodName, descriptor, args);
    }

    @Override
    public Set<String> targetClasses() {
        return configCenter.activeGroup().index().targetClasses();
    }

    @Override
    public Set<String> methodNames(String className) {
        return configCenter.activeGroup().index().methodNames(className);
    }

    /** 目标类计数（state.json 的 instrumentedClasses 数据源，M1 语义=注册数） */
    public int targetClassCount() {
        return targetClasses().size();
    }
}
