package com.equip.demo;

/**
 * 设备状态 POJO（M1-6）：验证对象返回类型的 Mock 生效。
 *
 * <p>toString 格式为 DemoMain 机器可解析输出的组成部分，不得随意变更：
 * {@code DeviceStatus{powered=true, voltage=220, current=11}}
 */
public class DeviceStatus {

    public final boolean powered;
    public final int voltage;
    public final int current;

    public DeviceStatus(boolean powered, int voltage, int current) {
        this.powered = powered;
        this.voltage = voltage;
        this.current = current;
    }

    @Override
    public String toString() {
        return "DeviceStatus{powered=" + powered + ", voltage=" + voltage + ", current=" + current + "}";
    }
}
