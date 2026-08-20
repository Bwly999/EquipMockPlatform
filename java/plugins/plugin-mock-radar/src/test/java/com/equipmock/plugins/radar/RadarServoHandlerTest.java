package com.equipmock.plugins.radar;

import com.equipmock.api.MockInvocation;
import com.equipmock.api.MockOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * handler 纯逻辑单测（surefire，不挂 agent）：写死（getAzimuth）与落配置（track）。
 */
class RadarServoHandlerTest {

    private final RadarServoHandler handler = new RadarServoHandler();

    @Test
    void getAzimuthHardcodedValue() {
        MockOutcome out = handler.handle(invocation("getAzimuth"));
        assertEquals(MockOutcome.Type.VALUE, out.getType());
        assertEquals(Double.valueOf(123.45), (Double) out.getValue(), 1e-9,
                "getAzimuth 写死 ofValue(123.45)");
    }

    @Test
    void trackFallsThroughToConfig() {
        MockOutcome out = handler.handle(invocation("track", 5, "SCAN"));
        assertNull(out, "track 恒返回 null → 配置中心规则（PATTERN/FULL/defaultAction）");
    }

    private static MockInvocation invocation(String method, Object... args) {
        return new MockInvocation(null, "com.equip.demo.RadarServo", method,
                "()D", args, null);
    }
}
