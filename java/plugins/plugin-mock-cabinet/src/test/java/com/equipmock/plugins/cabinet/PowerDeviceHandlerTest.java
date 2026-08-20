package com.equipmock.plugins.cabinet;

import com.equipmock.api.MockInvocation;
import com.equipmock.api.MockOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * handler 纯逻辑单测（surefire，不挂 agent）：写死/落配置分支的决策表（05 §2）。
 * 跨类加载器的 busy 系统属性入口也在此验证（IT 中实际使用）。
 */
class PowerDeviceHandlerTest {

    private final PowerDeviceHandler handler = new PowerDeviceHandler();

    @AfterEach
    void resetBusy() {
        PowerDeviceHandler.simulatedBusy = false;
        System.clearProperty("mock.cabinet.busy");
    }

    @Test
    void powerOnAlwaysHardcodedVoid() {
        MockOutcome out = handler.handle(invocation("powerOn", 1));
        assertEquals(MockOutcome.Type.VOID, out.getType(), "powerOn 写死 ofVoid");
    }

    @Test
    void readStatusBusyThrowsHardcodedIOException() {
        PowerDeviceHandler.simulatedBusy = true;
        MockOutcome out = handler.handle(invocation("readStatus", 1, "CH1"));
        assertEquals(MockOutcome.Type.THROW, out.getType(), "busy 时写死 ofThrow");
        IOException e = (IOException) out.getThrowable();
        assertEquals("cabinet busy", e.getMessage());
    }

    @Test
    void readStatusBusyAlsoReadableViaSystemProperty() {
        System.setProperty("mock.cabinet.busy", "true");
        assertTrue(PowerDeviceHandler.busy(), "跨类加载器入口：系统属性生效");
        MockOutcome out = handler.handle(invocation("readStatus", 2, "CH2"));
        assertEquals(MockOutcome.Type.THROW, out.getType());
        assertNotNull(out.getThrowable());
    }

    @Test
    void readStatusNotBusyFallsThroughToConfig() {
        MockOutcome out = handler.handle(invocation("readStatus", 1, "CH1"));
        assertNull(out, "非 busy 返回 null → 配置中心规则（05 §2 默认路径）");
    }

    @Test
    void getDeviceStatusAlwaysFallsThroughToConfig() {
        MockOutcome out = handler.handle(invocation("getDeviceStatus"));
        assertNull(out, "getDeviceStatus 恒落配置（POJO 值由表单配置）");
    }

    @Test
    void staticBusyFlagVisibleAcrossInstances() {
        PowerDeviceHandler.simulatedBusy = true;
        try {
            // 新实例共享同一静态标志（同加载器语义；IT 中经系统属性跨加载器）
            assertTrue(new PowerDeviceHandler().handle(
                    invocation("readStatus", 1, "CH1")).getType() == MockOutcome.Type.THROW);
        } finally {
            PowerDeviceHandler.simulatedBusy = false;
        }
    }

    private static MockInvocation invocation(String method, Object... args) {
        return new MockInvocation(null, "com.equip.demo.PowerDevice", method,
                "()I", args, null);
    }
}
