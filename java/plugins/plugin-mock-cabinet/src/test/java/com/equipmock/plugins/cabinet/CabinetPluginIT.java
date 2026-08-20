package com.equipmock.plugins.cabinet;

import com.equip.demo.DeviceStatus;
import com.equip.demo.PowerDevice;
import com.equip.demo.RealCallCounter;
import com.equipmock.testkit.EquipMockTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * plugin-mock-cabinet 进程内自测试（05 §4.3，failsafe *IT）：
 * 测试 JVM 带 -javaagent 运行，registerPlugin 热导入本模块 target/ 下
 * 已打包的 mock-cabinet-*.jar（surefire test 阶段 jar 尚未打包，故由
 * failsafe 在 integration-test 阶段执行）。
 *
 * <p>每用例独立配置 + 独立登记（registerPlugin 幂等）；插件 hot 导入后
 * 轮询 state.plugins[].state==STARTED 作为完成信号（M3 热导入语义）。
 * busy 标志经系统属性 {@code mock.cabinet.busy} 控制——handler 由 agent 的
 * 插件类加载器加载，与测试类路径上的同名类静态不共享（见 PowerDeviceHandler）。
 */
class CabinetPluginIT extends EquipMockTestBase {

    private static final String PLUGIN_ID = "mock-cabinet";

    private static Path pluginJar;

    @BeforeAll
    static void waitForAgentAndLocateJar() {
        awaitAgentRunning(8000); // premain 先于测试执行；缺 -javaagent 时这里给出可读失败
        pluginJar = Paths.get(System.getProperty("equipmock.test.plugin.jar"));
    }

    @BeforeEach
    void registerThisPlugin() throws Exception {
        registerPlugin(pluginJar, PLUGIN_ID);
        awaitPluginStarted(PLUGIN_ID, 10000);
    }

    /** a) readStatus 走配置：FULL_MATCH [1,"CH1"] → VALUE 7（真实实现返回 -1） */
    @Test
    void readStatusUsesConfigFullMatch() throws Exception {
        writeSubGroup("default", "cabinet.json", "{\n"
                + "  \"$schema\": \"equipmock/subgroup@1\",\n"
                + "  \"name\": \"cabinet\",\n"
                + "  \"mocks\": [\n"
                + "    { \"class\": \"com.equip.demo.PowerDevice\", \"method\": \"readStatus\",\n"
                + "      \"enabled\": true,\n"
                + "      \"defaultAction\": { \"type\": \"VALUE\", \"value\": 0 },\n"
                + "      \"rules\": [\n"
                + "        { \"matchType\": \"FULL_MATCH\", \"args\": [1, \"CH1\"],\n"
                + "          \"action\": { \"type\": \"VALUE\", \"value\": 7 } } ] } ]\n"
                + "}\n");
        awaitConfigApplied(4000);
        assertMocked("readStatus FULL_MATCH", 7, new PowerDevice().readStatus(1, "CH1"));
        assertMocked("readStatus 不命中 defaultAction", 0, new PowerDevice().readStatus(2, "CH2"));
    }

    /** b) busy 写死优先于配置：配置存在仍抛 IOException("cabinet busy") */
    @Test
    void busyFlagHardcodedThrowBeatsConfig() throws Exception {
        writeSubGroup("default", "cabinet.json", "{\n"
                + "  \"$schema\": \"equipmock/subgroup@1\",\n"
                + "  \"name\": \"cabinet\",\n"
                + "  \"mocks\": [\n"
                + "    { \"class\": \"com.equip.demo.PowerDevice\", \"method\": \"readStatus\",\n"
                + "      \"enabled\": true,\n"
                + "      \"rules\": [ { \"matchType\": \"FULL_MATCH\", \"args\": [1, \"CH1\"],\n"
                + "          \"action\": { \"type\": \"VALUE\", \"value\": 7 } } ] } ]\n"
                + "}\n");
        awaitConfigApplied(4000);
        System.setProperty("mock.cabinet.busy", "true");
        try {
            IOException e = assertThrows(IOException.class,
                    () -> new PowerDevice().readStatus(1, "CH1"),
                    "busy=true 时写死 ofThrow 应优先于配置 VALUE 7");
            assertEquals("cabinet busy", e.getMessage());
        } finally {
            System.clearProperty("mock.cabinet.busy");
        }
        // 关闭 busy 后同一调用回到配置值（null 落配置路径）
        assertMocked("busy 关闭后走配置", 7, new PowerDevice().readStatus(1, "CH1"));
    }

    /** c) powerOn 写死 VOID：真实打点不增长（配置中心无 powerOn 条目，REAL 会增长） */
    @Test
    void powerOnHardcodedVoidSkipsRealCall() throws Exception {
        int before = RealCallCounter.powerOnCount();
        new PowerDevice().powerOn(1);
        new PowerDevice().powerOn(2);
        assertEquals(before, RealCallCounter.powerOnCount(),
                "插件写死 ofVoid 吞掉真实调用（真实打点不变）");
    }

    /** d) getDeviceStatus 走配置 POJO 注入（powered=true/voltage=220/current=11） */
    @Test
    void deviceStatusUsesConfigPojoInjection() throws Exception {
        writeSubGroup("default", "cabinet.json", "{\n"
                + "  \"$schema\": \"equipmock/subgroup@1\",\n"
                + "  \"name\": \"cabinet\",\n"
                + "  \"mocks\": [\n"
                + "    { \"class\": \"com.equip.demo.PowerDevice\", \"method\": \"getDeviceStatus\",\n"
                + "      \"enabled\": true, \"rules\": [],\n"
                + "      \"defaultAction\": { \"type\": \"VALUE\",\n"
                + "        \"value\": { \"powered\": true, \"voltage\": 220, \"current\": 11 } } } ]\n"
                + "}\n");
        awaitConfigApplied(4000);
        DeviceStatus st = new PowerDevice().getDeviceStatus();
        assertMocked("POJO powered", Boolean.TRUE, Boolean.valueOf(st.powered));
        assertMocked("POJO voltage", 220, st.voltage);
        assertMocked("POJO current", 11, st.current);
    }

    /** e) 停用插件（registry enabled=false）→ 回纯配置语义：busy 不再抛、powerOn 回 REAL */
    @Test
    void disablingPluginFallsBackToPureConfigSemantics() throws Exception {
        writeSubGroup("default", "cabinet.json", "{\n"
                + "  \"$schema\": \"equipmock/subgroup@1\",\n"
                + "  \"name\": \"cabinet\",\n"
                + "  \"mocks\": [\n"
                + "    { \"class\": \"com.equip.demo.PowerDevice\", \"method\": \"readStatus\",\n"
                + "      \"enabled\": true,\n"
                + "      \"rules\": [ { \"matchType\": \"FULL_MATCH\", \"args\": [1, \"CH1\"],\n"
                + "          \"action\": { \"type\": \"VALUE\", \"value\": 7 } } ] } ]\n"
                + "}\n");
        awaitConfigApplied(4000);
        System.setProperty("mock.cabinet.busy", "true");
        int powerOnBefore = RealCallCounter.powerOnCount();
        try {
            setPluginEnabled(PLUGIN_ID, false);
            awaitPluginState(PLUGIN_ID, "DISABLED", 8000);
            // 写死逻辑随路由开关断开：busy=true 也不再抛，走配置 VALUE 7
            assertMocked("停用后 busy 不生效走配置", 7,
                    new PowerDevice().readStatus(1, "CH1"));
            // powerOn 无配置条目 → 回真实调用（写死 VOID 已断开）
            new PowerDevice().powerOn(1);
            assertEquals(powerOnBefore + 1, RealCallCounter.powerOnCount(),
                    "停用后 powerOn 回真实（打点+1）");
        } finally {
            System.clearProperty("mock.cabinet.busy");
        }
    }
}
