package com.equip.demo;

/**
 * 模拟装备设备的被 Mock 目标类（M1-6，05 §5）。
 *
 * <p>方法覆盖 M1-4 验收所需形态：
 * <ul>
 *   <li>int / boolean / String / POJO / byte[] 五种返回类型；</li>
 *   <li>{@link #isOnline()} 为 <b>静态</b>方法、{@link #powerOn(int)} 为 <b>final</b> 方法，
 *       验证静态/实例与 final 方法的 advice 织入；</li>
 *   <li>真实实现返回可区分的错误值（-1 / false / REAL-DEVICE / null），便于肉眼与脚本断言。</li>
 * </ul>
 */
public class PowerDevice {

    /** 真实实现：返回 -1（Mock 预设 VALUE 5） */
    public int readStatus(int channel, String name) {
        RealCallCounter.onReadStatus();
        return -1;
    }

    /** 静态方法。真实实现：false（Mock 预设 VALUE true） */
    public static boolean isOnline() {
        return false;
    }

    /** 真实实现：REAL-DEVICE（Mock 预设 VALUE MOCK-DEVICE） */
    public String getName() {
        return "REAL-DEVICE";
    }

    /** 真实实现：powered=false/voltage=0/current=0（Mock 预设 VALUE new DeviceStatus(true,220,11)） */
    public DeviceStatus getDeviceStatus() {
        return new DeviceStatus(false, 0, 0);
    }

    /** final void 方法。真实实现：打点（Mock 预设 VOID，打点应为 0） */
    public final void powerOn(int channel) {
        RealCallCounter.onPowerOn();
    }

    /** 真实实现：返回 null（Mock 预设 VALUE new byte[]{1,2,3}） */
    public byte[] send(byte[] data) {
        RealCallCounter.onSend();
        return null;
    }
}
