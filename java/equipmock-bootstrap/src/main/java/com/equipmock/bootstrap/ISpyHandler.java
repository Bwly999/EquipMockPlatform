package com.equipmock.bootstrap;

/**
 * agent 侧路由处理器契约（02 §1）。
 *
 * <p>agent premain 时反射注入到 {@link Spy#HANDLER}。参数/返回值用 {@code Object} 而非
 * 具体类型，是为了 advice 内联字节码最小化（返回值实际为 {@link MockResult} 或 null）。
 */
public interface ISpyHandler {

    /**
     * 对一次被拦截的方法调用给出 Mock 决策。
     *
     * @param className 目标类名（点分 FQCN）
     * @param methodName 方法名
     * @param descriptor JVM 方法描述符，如 {@code (ILjava/lang/String;)I}
     * @param self 调用接收者；静态方法时为 null
     * @param args 实参数组（已装箱），无参时为空数组
     * @return {@link MockResult}；null = 放行真实方法
     */
    Object/* MockResult */ mock(String className, String methodName,
                                String descriptor, Object self, Object[] args);
}
