package com.equipmock.plugins.radar;

import com.equipmock.api.MockHandler;
import com.equipmock.api.MockInterceptor;
import com.equipmock.api.MockInvocation;
import com.equipmock.api.MockOutcome;
import org.pf4j.Extension;

/**
 * 雷达伺服 {@code com.equip.demo.RadarServo} 的 MockHandler（M4-3，05 §2 决策示例）。
 *
 * <ul>
 *   <li>{@code getAzimuth}：写死 {@code ofValue(123.45)}——方位角由真实算法仿真，
 *       优先于配置（联调中不可调，验证"写死优先"）；</li>
 *   <li>{@code track}：恒返回 {@code null} 落配置中心——PATTERN_MATCH 命中 THROW
 *       （失锁模拟）、FULL_MATCH 命中 VALUE、全不命中走 defaultAction，三种动作
 *       全部由小分组 json 配置驱动。</li>
 * </ul>
 */
@Extension
@MockInterceptor(targetClasses = "com.equip.demo.RadarServo",
        methods = {"track", "getAzimuth"})
public class RadarServoHandler implements MockHandler {

    @Override
    public MockOutcome handle(MockInvocation inv) {
        if ("getAzimuth".equals(inv.methodName)) {
            return MockOutcome.ofValue(Double.valueOf(123.45)); // 写死优先于配置
        }
        return null; // track → 配置中心规则（纯配置场景）
    }
}
