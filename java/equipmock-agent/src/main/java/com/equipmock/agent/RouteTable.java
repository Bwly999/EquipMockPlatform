package com.equipmock.agent;

import com.equipmock.bootstrap.MockResult;

import java.util.Set;

/**
 * Mock 路由数据源抽象（M2/M3 替换点）。
 *
 * <p>M1 由硬编码表提供数据；M2 由 {@link ConfigCenterRouteTable} 提供配置中心数据
 * （GroupSnapshot/MockIndex + 匹配引擎，03 文档）；M3 接入插件 MockPoint 后，将实现本接口的
 * 组合数据源注入 {@link AgentSpyHandler}，advice 与插桩注册逻辑无需变更。
 */
public interface RouteTable {

    /**
     * 查询一次调用的 Mock 决策（无实参形式；默认委托本方法）。
     *
     * @param className 目标类名（点分 FQCN）
     * @param methodName 方法名
     * @param descriptor JVM 方法描述符
     * @return MockResult；null = 放行真实方法
     */
    MockResult lookup(String className, String methodName, String descriptor);

    /**
     * 查询一次调用的 Mock 决策（带实参，M2 匹配引擎需要）。
     * VALUE 的类型转换必须在返回前完成并装箱（02 §5.2 第 4 步）。
     *
     * @param args 运行期实参（@Advice.AllArguments 装箱形式；无参方法为空数组）
     */
    default MockResult lookup(String className, String methodName, String descriptor,
                              Object[] args) {
        return lookup(className, methodName, descriptor);
    }

    /** 全部目标类名集合（用于插桩注册的精确类匹配；动态查询——新目标类首次加载即被织入） */
    Set<String> targetClasses();

    /** 某目标类上需要织入 advice 的方法名集合（只织入已声明方法，其余方法字节码不变） */
    Set<String> methodNames(String className);
}
