package com.equipmock.api;

import java.lang.reflect.Method;

/**
 * 一次被拦截调用的上下文（02 §2）。由 agent 路由层构造后传给 {@link MockHandler}。
 */
public final class MockInvocation {

    /** 调用接收者；静态方法时为 null */
    public final Object self;
    /** 目标类点分 FQCN */
    public final String className;
    /** 目标方法名 */
    public final String methodName;
    /** JVM 方法描述符，如 {@code (ILjava/lang/String;)I} */
    public final String descriptor;
    /** 实参数组（已装箱） */
    public final Object[] args;
    /** 目标 {@link Method}（可用来反射调用真实逻辑） */
    public final Method reflectedMethod;

    public MockInvocation(Object self, String className, String methodName,
                          String descriptor, Object[] args, Method reflectedMethod) {
        this.self = self;
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.args = args;
        this.reflectedMethod = reflectedMethod;
    }
}
