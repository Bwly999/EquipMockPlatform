package com.equipmock.bootstrap;

/**
 * 无需声明受检异常的抛出工具（02 §4.4）。
 *
 * <p>放在 bootstrap jar：advice 内联字节码会调用本类，必须对所有类加载器可见。
 * 利用经典泛型 thrower 技巧（Lombok SneakyThrows 同款）：编译器将 T 推断为
 * RuntimeException，故调用处无需声明受检异常；运行期字节码 {@code athrow} 可抛出
 * 任意 Throwable 而不受方法签名的检查异常表约束，对宿主异常表完全透明。
 */
public final class SneakyThrow {

    private SneakyThrow() {
    }

    /**
     * 直接抛出给定 Throwable，调用方无需（也无法）声明受检异常。
     * 本方法永不正常返回。
     */
    public static RuntimeException raise(Throwable t) {
        // 显式类型参数：让编译器把 T 解析为 RuntimeException（非受检），
        // 若省略则按实参推断为 Throwable，调用处将被要求声明受检异常
        throw SneakyThrow.<RuntimeException>sneakyThrow0(t);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneakyThrow0(Throwable t) throws T {
        throw (T) t;
    }
}
