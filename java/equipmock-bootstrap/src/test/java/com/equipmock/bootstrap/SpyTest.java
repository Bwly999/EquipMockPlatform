package com.equipmock.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * bootstrap 契约类单测（M1-1）：Spy.mock 在 HANDLER 为 null 时必须安全返回 null；
 * HANDLER 注入后正确委托；SneakyThrow 透明抛出受检异常。
 */
class SpyTest {

    @AfterEach
    void resetHandler() {
        // 还原全局状态，避免影响其它用例
        Spy.HANDLER = null;
    }

    @Test
    void mockReturnsNullWhenHandlerAbsent() {
        Spy.HANDLER = null;
        // agent 未就绪：返回 null（放行），绝不抛异常阻断宿主
        Object r = Spy.mock("com.demo.Foo", "bar", "()V", null, Spy.NO_ARGS);
        assertNull(r);
    }

    @Test
    void mockDelegatesToInjectedHandler() {
        MockResult expected = new MockResult(MockResult.VALUE, Integer.valueOf(5), null);
        Spy.HANDLER = new StubHandler(expected);
        Object r = Spy.mock("com.demo.Foo", "bar", "()I", null, Spy.NO_ARGS);
        assertSame(expected, r);
    }

    @Test
    void mockPassesThroughArguments() {
        final String[] seen = new String[3];
        final Object[] seenArgs = new Object[2];
        final Object self = new Object();
        Spy.HANDLER = (className, methodName, descriptor, s, args) -> {
            seen[0] = className;
            seen[1] = methodName;
            seen[2] = descriptor;
            seenArgs[0] = s;
            seenArgs[1] = args;
            return null;
        };
        Object[] args = new Object[]{"x"};
        Object r = Spy.mock("com.demo.Foo", "bar", "(Ljava/lang/String;)V", self, args);
        assertNull(r);
        assertEquals("com.demo.Foo", seen[0]);
        assertEquals("bar", seen[1]);
        assertEquals("(Ljava/lang/String;)V", seen[2]);
        assertSame(self, seenArgs[0]);
        assertSame(args, seenArgs[1]);
    }

    @Test
    void sneakyThrowRaisesCheckedExceptionWithoutDeclaration() {
        java.io.IOException checked = new java.io.IOException("checked");
        // 本 executable 未声明任何受检异常也能调用 raise —— 证明编译期透明；
        // 运行期精确抛出原始受检异常实例（无包装）。
        Throwable thrown = assertThrows(Throwable.class, () -> SneakyThrow.raise(checked));
        assertSame(checked, thrown);
    }

    /** 最小 ISpyHandler 桩 */
    private static final class StubHandler implements ISpyHandler {
        private final MockResult result;

        StubHandler(MockResult result) {
            this.result = result;
        }

        @Override
        public Object mock(String className, String methodName, String descriptor,
                           Object self, Object[] args) {
            return result;
        }
    }
}
