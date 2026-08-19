package com.equipmock.bootstrap;

/**
 * advice 与 agent 共享的 int 常量（02 §1）：表示 {@code @Advice.OnMethodEnter}
 * enter 方法的返回语义。
 *
 * <p>M1 实现说明：M1 的 advice 模板使用 byte-buddy 的
 * {@code skipOn = Advice.OnNonDefaultValue.class} 语义（enter 返回非默认值即跳过原方法），
 * 因此 advice 字节码不直接引用本类；保留本类是为了兼容 02 §4 伪代码风格的两段式
 * （enter 返回 int 常量 + exit 写回）实现，作为 M2/M3 若需回退时的既有契约。
 */
public final class EnterResult {

    /** enter 返回值常量：不跳过，执行真实方法体 */
    public static final int REAL = 0;
    /** enter 返回值常量：跳过原方法体 */
    public static final int SKIP = 1;

    private EnterResult() {
    }
}
