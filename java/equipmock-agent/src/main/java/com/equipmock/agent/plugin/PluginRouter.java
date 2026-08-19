package com.equipmock.agent.plugin;

/**
 * 插件路由数据源接口（{@link PluginService} 实现）。
 * {@code CompositeRouteTable} 依赖本抽象而非具体服务，便于单测注入桩路由。
 */
public interface PluginRouter {

    /** 当前插件路由快照（volatile 语义：整体替换） */
    PluginRouting routing();
}
