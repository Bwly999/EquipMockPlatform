package com.equipmock.bootstrap;

/**
 * Mock 决策返回值载体（02 §1）。
 *
 * <p>advice 内联字节码只读取 {@link #code} int 字段并按需拆箱 {@link #value}——
 * 用 int 常量而非枚举，避免跨类加载器传枚举的切换编译问题。
 */
public final class MockResult {

    /** code 常量：放行真实方法 */
    public static final int REAL = 0;
    /** code 常量：返回预置值（value 已完成类型转换与装箱） */
    public static final int VALUE = 1;
    /** code 常量：抛出 throwable */
    public static final int THROW = 2;
    /** code 常量：吞掉真实调用（仅 void 方法有效） */
    public static final int VOID = 3;

    public final int code;
    /** code == VALUE 时有效（类型已转换为目标返回类型的装箱形式） */
    public final Object value;
    /** code == THROW 时有效 */
    public final Throwable throwable;

    public MockResult(int code, Object value, Throwable t) {
        this.code = code;
        this.value = value;
        this.throwable = t;
    }

    /** 共享单例：放行真实方法 */
    public static final MockResult REAL_RESULT = new MockResult(REAL, null, null);
}
