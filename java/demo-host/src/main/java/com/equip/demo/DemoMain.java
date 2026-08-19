package com.equip.demo;

import java.util.Arrays;

/**
 * demo-host 入口（M1-6）：循环调用全部方法并打印机器可解析行，作为端到端验收载体。
 *
 * <p>用法：{@code java -Dequipmock.demo.iterations=N -cp demo-host.jar com.equip.demo.DemoMain}
 * <ul>
 *   <li>N &gt; 0：执行 N 轮后退出（脚本断言用）；</li>
 *   <li>N = 0（默认）：无限循环，每秒一轮，Ctrl+C 退出。</li>
 * </ul>
 */
public class DemoMain {

    public static void main(String[] args) throws Exception {
        int iterations = Integer.getInteger("equipmock.demo.iterations", 0);
        PowerDevice device = new PowerDevice();
        long round = 0;
        while (true) {
            round++;
            runOneRound(device);
            if (iterations > 0 && round >= iterations) {
                break;
            }
            Thread.sleep(1000);
        }
    }

    /** 一轮全量调用：输出行格式是 scripts/e2e-check.sh 的断言依据，不得随意变更 */
    private static void runOneRound(PowerDevice device) {
        callReadStatus(device, 1, "CH1");
        // M2 验收辅助（03 §9 用例 2/3/4：FullMatch 不命中通道 + THROW 校验通道）
        callReadStatus(device, 2, "CH2");

        System.out.println("isOnline()=" + PowerDevice.isOnline());

        System.out.println("getName()=" + device.getName());

        DeviceStatus st = device.getDeviceStatus();
        System.out.println("getDeviceStatus()=" + st);

        byte[] resp = device.send(new byte[]{9, 9});
        System.out.println("send=" + renderBytes(resp));

        device.powerOn(1);
        System.out.println("realPowerOnCount=" + RealCallCounter.powerOnCount());
        System.out.println("realReadStatusCount=" + RealCallCounter.readStatusCount());
        System.out.println("realSendCount=" + RealCallCounter.sendCount());

        System.out.println("unrelated=" + new UnrelatedService().hello());
    }

    /**
     * 断言辅助（M2）：调用 readStatus 并打印；THROW Mock 命中时打印
     * {@code readStatus(ch,name)=THROW:异常类:消息} 而非让异常终止演示循环。
     * 正常路径输出与 M1 完全一致。
     */
    private static void callReadStatus(PowerDevice device, int channel, String name) {
        try {
            System.out.println("readStatus(" + channel + "," + name + ")="
                    + device.readStatus(channel, name));
        } catch (Throwable t) {
            System.out.println("readStatus(" + channel + "," + name + ")=THROW:" + t);
        }
    }

    /** byte[] 渲染为 [1,2,3] 形式；null 渲染为 null */
    private static String renderBytes(byte[] data) {
        if (data == null) {
            return "null";
        }
        return Arrays.toString(data).replace(" ", "");
    }
}
