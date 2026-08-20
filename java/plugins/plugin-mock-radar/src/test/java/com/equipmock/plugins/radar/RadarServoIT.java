package com.equipmock.plugins.radar;

import com.equip.demo.RadarServo;
import com.equip.demo.RealCallCounter;
import com.equipmock.testkit.EquipMockTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * plugin-mock-radar 进程内自测试（M4-3，failsafe *IT，约定同 CabinetPluginIT）。
 *
 * <p>覆盖：写死优先（getAzimuth=123.45 覆盖配置 88.8）、track 纯配置的
 * PATTERN_MATCH 命中 THROW / FULL_MATCH 命中 VALUE / 不命中 defaultAction、
 * 停用插件后写死让位配置。
 */
class RadarServoIT extends EquipMockTestBase {

    private static final String PLUGIN_ID = "mock-radar";

    private static Path pluginJar;

    @BeforeAll
    static void waitForAgentAndLocateJar() {
        awaitAgentRunning(8000);
        pluginJar = Paths.get(System.getProperty("equipmock.test.plugin.jar"));
    }

    @BeforeEach
    void registerThisPlugin() throws Exception {
        registerPlugin(pluginJar, PLUGIN_ID);
        awaitPluginStarted(PLUGIN_ID, 10000);
    }

    /** 雷达配置：track 三动作（PATTERN THROW / FULL VALUE / defaultAction）+ getAzimuth 配置 88.8 */
    private static void writeRadarConfig() throws Exception {
        writeSubGroup("default", "radar.json", "{\n"
                + "  \"$schema\": \"equipmock/subgroup@1\",\n"
                + "  \"name\": \"radar\",\n"
                + "  \"mocks\": [\n"
                + "    { \"class\": \"com.equip.demo.RadarServo\", \"method\": \"track\",\n"
                + "      \"enabled\": true,\n"
                + "      \"defaultAction\": { \"type\": \"VALUE\", \"value\": \"DEFAULT-TRACK\" },\n"
                + "      \"rules\": [\n"
                + "        { \"matchType\": \"PATTERN_MATCH\", \"argsPattern\": [\"\\\\d+\", \"LOCK.*\"],\n"
                + "          \"action\": { \"type\": \"THROW\", \"exception\": \"java.lang.IllegalStateException\",\n"
                + "                      \"message\": \"track lost\" } },\n"
                + "        { \"matchType\": \"FULL_MATCH\", \"args\": [5, \"SCAN\"],\n"
                + "          \"action\": { \"type\": \"VALUE\", \"value\": \"TRACKED-5\" } } ] },\n"
                + "    { \"class\": \"com.equip.demo.RadarServo\", \"method\": \"getAzimuth\",\n"
                + "      \"enabled\": true, \"rules\": [],\n"
                + "      \"defaultAction\": { \"type\": \"VALUE\", \"value\": 88.8 } } ]\n"
                + "}\n");
        awaitConfigApplied(4000);
    }

    /** 1) 写死优先：getAzimuth=123.45，压过配置 defaultAction 88.8 */
    @Test
    void getAzimuthHardcodedBeatsConfig() throws Exception {
        writeRadarConfig();
        RadarServo servo = new RadarServo();
        assertMocked("getAzimuth 写死优先", 123.45, servo.getAzimuth());
    }

    /** 2) PATTERN_MATCH 命中 THROW：track(7,"LOCK-1") → IllegalStateException("track lost") */
    @Test
    void trackPatternMatchThrows() throws Exception {
        writeRadarConfig();
        Exception e = assertThrowsIllegalState(() -> new RadarServo().track(7, "LOCK-1"));
        assertEquals("track lost", e.getMessage());
        // 不命中 PATTERN 的参数不受影响（走后续规则）
        assertMocked("非 LOCK 参数不受 THROW 影响", "TRACKED-5",
                new RadarServo().track(5, "SCAN"));
    }

    /** 3) FULL_MATCH 命中 VALUE：track(5,"SCAN") → "TRACKED-5"，真实打点不变 */
    @Test
    void trackFullMatchValueSkipsRealCall() throws Exception {
        writeRadarConfig();
        int before = RealCallCounter.trackCount();
        assertMocked("track FULL_MATCH", "TRACKED-5", new RadarServo().track(5, "SCAN"));
        assertEquals(before, RealCallCounter.trackCount(), "配置 VALUE 吞掉真实调用（打点不变）");
    }

    /** 4) 全不命中 → defaultAction：track(9,"SCAN") → "DEFAULT-TRACK"（真实返回 REAL-TRACK） */
    @Test
    void trackDefaultActionWhenNoRuleMatches() throws Exception {
        writeRadarConfig();
        assertMocked("track defaultAction", "DEFAULT-TRACK",
                new RadarServo().track(9, "SCAN"));
    }

    /** 5) 停用插件：写死让位配置——getAzimuth 回配置 88.8（真实实现 0.0） */
    @Test
    void disablingPluginLetsConfigTakeOverGetAzimuth() throws Exception {
        writeRadarConfig();
        setPluginEnabled(PLUGIN_ID, false);
        awaitPluginState(PLUGIN_ID, "DISABLED", 8000);
        assertMocked("停用后 getAzimuth 走配置", 88.8, new RadarServo().getAzimuth());
        assertMocked("停用后 track 仍走配置", "TRACKED-5",
                new RadarServo().track(5, "SCAN"));
    }

    /** JUnit assertThrows 的函数式适配（Java 8 Executable） */
    private static IllegalStateException assertThrowsIllegalState(Runnable call) {
        try {
            call.run();
        } catch (IllegalStateException e) {
            return e;
        }
        throw new AssertionError("期望抛出 IllegalStateException");
    }
}
