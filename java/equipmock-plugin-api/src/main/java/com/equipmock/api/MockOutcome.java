package com.equipmock.api;

/**
 * {@link MockHandler#handle} 的返回决策（02 §2）。
 *
 * <p>四类决策：{@code ofValue} 返回预置值、{@code ofVoid} 吞掉真实调用（仅 void 方法）、
 * {@code ofThrow} 抛出异常、{@code passthrough} 显式放行真实方法并跳过配置中心规则。
 */
public final class MockOutcome {

    /** 决策类型 */
    public enum Type {
        /** 返回预置值 */
        VALUE,
        /** 吞掉真实调用 */
        VOID,
        /** 抛出异常 */
        THROW,
        /** 显式放行（跳过配置中心） */
        PASSTHROUGH
    }

    private final Type type;
    private final Object value;
    private final Throwable throwable;

    private MockOutcome(Type type, Object value, Throwable throwable) {
        this.type = type;
        this.value = value;
        this.throwable = throwable;
    }

    /** 返回预置值 */
    public static MockOutcome ofValue(Object v) {
        return new MockOutcome(Type.VALUE, v, null);
    }

    /** 吞掉真实调用（void 方法） */
    public static MockOutcome ofVoid() {
        return new MockOutcome(Type.VOID, null, null);
    }

    /** 抛出给定异常 */
    public static MockOutcome ofThrow(Throwable t) {
        return new MockOutcome(Type.THROW, null, t);
    }

    /** 显式放行真实方法（跳过配置中心规则） */
    public static MockOutcome passthrough() {
        return new MockOutcome(Type.PASSTHROUGH, null, null);
    }

    public Type getType() {
        return type;
    }

    /** 仅 {@link Type#VALUE} 时有效 */
    public Object getValue() {
        return value;
    }

    /** 仅 {@link Type#THROW} 时有效 */
    public Throwable getThrowable() {
        return throwable;
    }

    @Override
    public String toString() {
        switch (type) {
            case VALUE:
                return "MockOutcome[VALUE " + value + "]";
            case VOID:
                return "MockOutcome[VOID]";
            case THROW:
                return "MockOutcome[THROW " + throwable + "]";
            default:
                return "MockOutcome[PASSTHROUGH]";
        }
    }
}
