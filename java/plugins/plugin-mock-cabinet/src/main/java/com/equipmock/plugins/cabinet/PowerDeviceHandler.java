package com.equipmock.plugins.cabinet;

import com.equipmock.api.MockHandler;
import com.equipmock.api.MockInterceptor;
import com.equipmock.api.MockInvocation;
import com.equipmock.api.MockOutcome;
import org.pf4j.Extension;

import java.io.IOException;

/**
 * 机柜电源 {@code com.equip.demo.PowerDevice} 的 MockHandler（05 §1/§2 决策示例）。
 *
 * <p>写死 vs 配置的三种典型路径各占一个方法（05 §2 决策表）：
 * <ul>
 *   <li>{@code powerOn}：写死 {@link MockOutcome#ofVoid()}——上电仿真永远吞掉真实调用；</li>
 *   <li>{@code readStatus}：静态 busy 标志为 true 时写死
 *       {@link MockOutcome#ofThrow(Throwable)}（故障注入逻辑优先于配置），否则返回
 *       {@code null} 落配置中心规则（联调期常调的部分）；</li>
 *   <li>{@code getDeviceStatus}：恒返回 {@code null} 落配置（POJO 值由表单配置）。</li>
 * </ul>
 *
 * <p><b>busy 标志的两个入口</b>：本 handler 由 agent 的 PF4J 插件类加载器从插件 jar
 * 加载，与测试类路径上的同名类是两个 Class——同加载器内直接改
 * {@link #simulatedBusy}（单元测试）；跨加载器（插件自测试 IT）用系统属性
 * {@code mock.cabinet.busy}（JVM 全局，插件内可见）。
 */
@Extension
@MockInterceptor(targetClasses = "com.equip.demo.PowerDevice",
        methods = {"readStatus", "powerOn", "getDeviceStatus"})
public class PowerDeviceHandler implements MockHandler {

    /** 写死故障注入开关：true=readStatus 抛 IOException("cabinet busy") */
    public static volatile boolean simulatedBusy = false;

    /** busy 判定：静态标志 或 系统属性 mock.cabinet.busy（跨类加载器可控） */
    static boolean busy() {
        return simulatedBusy || Boolean.getBoolean("mock.cabinet.busy");
    }

    @Override
    public MockOutcome handle(MockInvocation inv) {
        if ("powerOn".equals(inv.methodName)) {
            return MockOutcome.ofVoid(); // 写死：上电仿真（05 §2「状态/算法仿真」）
        }
        if ("readStatus".equals(inv.methodName) && busy()) {
            return MockOutcome.ofThrow(new IOException("cabinet busy")); // 写死优先于配置
        }
        return null; // readStatus(非 busy)/getDeviceStatus → 配置中心规则
    }
}
