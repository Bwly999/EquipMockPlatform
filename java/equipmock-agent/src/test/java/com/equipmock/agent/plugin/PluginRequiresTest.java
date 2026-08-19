package com.equipmock.agent.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugin-Requires 解析与平台版本硬校验（D19，05 §6）：
 * 缺字段/非法区间/多条件 AND/边界操作符/SNAPSHOT 限定符语义。
 */
class PluginRequiresTest {

    private static final String PLATFORM = "1.0.0-SNAPSHOT";

    @Test
    void missingAttributeIsRejected() {
        PluginRequires.Result r = PluginRequires.check(null, PLATFORM);
        assertFalse(r.satisfied);
        assertTrue(r.message.contains("missing Plugin-Requires"), r.message);

        r = PluginRequires.check("   ", PLATFORM);
        assertFalse(r.satisfied);
        assertTrue(r.message.contains("missing Plugin-Requires"), r.message);
    }

    @Test
    void singleMinimumSatisfied() {
        PluginRequires.Result r = PluginRequires.check("equipmock >=1.0.0", PLATFORM);
        assertTrue(r.satisfied, r.message);
        assertNull(r.message);
    }

    @Test
    void rangeWithMultipleConditionsIsAnd() {
        assertTrue(PluginRequires.check("equipmock >=1.0.0 <2.0.0", PLATFORM).satisfied);
        assertFalse(PluginRequires.check("equipmock >=0.1.0 <0.2.0", PLATFORM).satisfied);

        // 多条件 AND：任一不满足即拒绝，error 含全部已判定条件
        PluginRequires.Result r = PluginRequires.check("equipmock >=1.0.0 <2.0.0 >1.5.0",
                PLATFORM);
        assertFalse(r.satisfied);
        assertTrue(r.message.contains("requires equipmock>=1.0.0 <2.0.0 >1.5.0"),
                r.message);
        assertTrue(r.message.contains("current=1.0.0-SNAPSHOT"), r.message);
    }

    @Test
    void allOperators() {
        assertTrue(PluginRequires.check("equipmock >0.9.9", PLATFORM).satisfied);
        assertFalse(PluginRequires.check("equipmock >1.0.0", PLATFORM).satisfied);
        assertTrue(PluginRequires.check("equipmock <=1.0.0", PLATFORM).satisfied);
        assertFalse(PluginRequires.check("equipmock <=0.9.9", PLATFORM).satisfied);
        assertTrue(PluginRequires.check("equipmock <2.0.0", PLATFORM).satisfied);
        assertFalse(PluginRequires.check("equipmock <1.0.0", PLATFORM).satisfied);
        // "=" 按数字段比较（SNAPSHOT 限定符不参与）
        assertTrue(PluginRequires.check("equipmock =1.0.0", PLATFORM).satisfied);
        assertFalse(PluginRequires.check("equipmock =1.2.0", PLATFORM).satisfied);
    }

    @Test
    void snapshotQualifierIgnoredInComparison() {
        // semver prerelease 语义会让 1.0.0-SNAPSHOT < 1.0.0，内部平台约定限定符不参与
        assertTrue(PluginRequires.check("equipmock >=1.0.0", "1.0.0-SNAPSHOT").satisfied);
        assertTrue(PluginRequires.check("equipmock >=1.0.0", "1.0.0").satisfied);
        assertFalse(PluginRequires.check("equipmock <1.0.0", "1.0.0-SNAPSHOT").satisfied);
    }

    @Test
    void shortVersionsZeroPadded() {
        assertTrue(PluginRequires.check("equipmock >=1.0", PLATFORM).satisfied);
        assertTrue(PluginRequires.check("equipmock >=1", PLATFORM).satisfied);
        assertFalse(PluginRequires.check("equipmock >1.0.0.0", PLATFORM).satisfied);
    }

    @Test
    void invalidSyntaxRejectedWithReadableError() {
        PluginRequires.Result r = PluginRequires.check("equipmock", PLATFORM);
        assertFalse(r.satisfied);
        assertTrue(r.message.contains("no version constraint"), r.message);

        r = PluginRequires.check("equipmock >=1.0.0 abc", PLATFORM);
        assertFalse(r.satisfied);
        assertTrue(r.message.contains("must be (>=|<=|>|<|=)x.y.z"), r.message);

        r = PluginRequires.check(">=1.0.0", PLATFORM);
        // 无产品名：首个条件 token 也可直接出现（宽松），此处应通过
        assertTrue(r.satisfied);

        r = PluginRequires.check("otherproduct >=1.0.0", PLATFORM);
        assertFalse(r.satisfied);
        assertTrue(r.message.contains("unknown product"), r.message);

        r = PluginRequires.check("equipmock >=abc", PLATFORM);
        assertFalse(r.satisfied);
        assertTrue(r.message.contains("invalid Plugin-Requires"), r.message);
    }

    @Test
    void unparseablePlatformVersionComparesAsZero() {
        // 平台版本读不到时兜底 1.0.0-SNAPSHOT；非法值按 0 处理（fail-closed）
        assertFalse(PluginRequires.check("equipmock >=1.0.0", "not-a-version").satisfied);
        assertTrue(PluginRequires.check("equipmock >=0.0.0", "not-a-version").satisfied);
    }

    @Test
    void messageFormatMatchesContract() {
        PluginRequires.Result r = PluginRequires.check("equipmock >=9.9.9", PLATFORM);
        assertEquals("requires equipmock>=9.9.9, current=1.0.0-SNAPSHOT", r.message);
    }
}
