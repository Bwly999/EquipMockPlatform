package com.equipmock.api;

import org.pf4j.ExtensionPoint;

/**
 * 插件 Mock 处理器契约（02 §2）。
 *
 * <p>插件实现本接口并同时标注 PF4J {@code @Extension} 与 {@link MockInterceptor}；
 * agent 通过 {@code pluginManager.getExtensions(MockHandler.class)} 发现全部实现（02 §6.3）。
 *
 * <p>继承 {@code org.pf4j.ExtensionPoint}（不 relocate）：这是插件编译期二进制契约，
 * agent jar 必须以同包名提供该接口（02 §7）。
 */
public interface MockHandler extends ExtensionPoint {

    /**
     * 处理一次被拦截的方法调用。
     *
     * @param invocation 调用上下文
     * @return null → 交给配置中心规则；非 null → 按返回的 {@link MockOutcome} 执行
     */
    MockOutcome handle(MockInvocation invocation);
}
