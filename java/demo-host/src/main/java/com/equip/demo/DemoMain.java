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

    /** 一轮全量调用：输出行格式是 scripts/m1-verify.sh 的断言依据，不得随意变更 */
    private static void runOneRound(PowerDevice device) {
        int status = device.readStatus(1, "CH1");
        System.out.println("readStatus(1,CH1)=" + status);

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

    /** byte[] 渲染为 [1,2,3] 形式；null 渲染为 null */
    private static String renderBytes(byte[] data) {
        if (data == null) {
            return "null";
        }
        return Arrays.toString(data).replace(" ", "");
    }
}
