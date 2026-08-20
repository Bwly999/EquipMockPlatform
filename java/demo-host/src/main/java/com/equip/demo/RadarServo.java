package com.equip.demo;

/**
 * 雷达伺服被 Mock 目标类（M4-3，05 §5 扩展）：plugin-mock-radar 的拦截目标。
 *
 * <p>与 {@link PowerDevice} 同风格：真实实现返回可区分的固定值
 * （track 返回 "REAL-TRACK"、getAzimuth 返回 0.0），并经 {@link RealCallCounter} 打点，
 * 便于测试与脚本区分 Mock 生效。不进入 DemoMain 循环（不改既有 e2e 输出契约）。
 */
public class RadarServo {

    /** 真实实现：0.0（雷达插件写死 ofValue(123.45)；配置 VALUE 亦可为 88.8） */
    public double getAzimuth() {
        RealCallCounter.onGetAzimuth();
        return 0.0;
    }

    /** 真实实现："REAL-TRACK"（配置规则：PATTERN 命中 THROW / FULL 命中 / defaultAction） */
    public String track(int targetId, String mode) {
        RealCallCounter.onTrack();
        return "REAL-TRACK";
    }
}
