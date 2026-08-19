package com.equipmock.agent;

import com.equipmock.bootstrap.MockResult;

import java.util.Set;

/**
 * Mock 路由数据源抽象（M2/M3 替换点）。
 *
 * <p>M1 由 {@link HardcodedRouteTable} 提供写死数据；M2 接入配置中心
 * （GroupSnapshot/MockIndex，03 文档）、M3 接入插件 MockPoint 后，将实现本接口的
 * 组合数据源注入 {@link AgentSpyHandler}，advice 与插桩注册逻辑无需变更。
 */
public interface RouteTable {

    /**
     * 查询一次调用的 Mock 决策。
     *
     * @param className 目标类名（点分 FQCN）
     * @param methodName 方法名
     * @param descriptor JVM 方法描述符
     * @return MockResult；null = 放行真实方法
     */
    MockResult lookup(String className, String methodName, String descriptor);

    /** 全部目标类名集合（用于插桩注册的精确类匹配） */
    Set<String> targetClasses();

    /** 某目标类上需要织入 advice 的方法名集合（只织入已声明方法，其余方法字节码不变） */
    Set<String> methodNames(String className);
}
