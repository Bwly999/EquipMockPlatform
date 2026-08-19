package com.equipmock.agent.config;

import com.equipmock.bootstrap.MockResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 匹配引擎单测（03 §4）：lookup 签名兜底、FULL_MATCH 深度相等、PATTERN_MATCH、
 * first-match 顺序、defaultAction、VOID/THROW/VALUE 执行、运行期签名错配放行。
 */
class MatchEngineTest {

    private static final Logger LOG = Logger.getLogger(MatchEngineTest.class.getName());
    private static final String CLS = "com.equipmock.agent.config.MatchEngineTest$Target";
    private static final String DESC = "(ILjava/lang/String;)I";

    /** 被拦截目标（返回类型解析走真实反射链路） */
    @SuppressWarnings("unused")
    static class Target {
        public int readStatus(int channel, String name) {
            return -1;
        }

        public void powerOn(int channel) {
        }

        public String getName() {
            return "real";
        }

        public List<Integer> channels() {
            return Arrays.asList(1);
        }

        public byte[] send(byte[] data) {
            return null;
        }

        public ObjectPojo status() {
            return null;
        }
    }

    public static class ObjectPojo {
        public boolean powered;
        public int voltage;
    }

    private final AtomicInteger reported = new AtomicInteger();
    private final List<String> reportMessages = new ArrayList<String>();
    private final MatchEngine engine = new MatchEngine(LOG, new MatchEngine.ErrorReporter() {
        @Override
        public void report(String file, String message) {
            reported.incrementAndGet();
            reportMessages.add(file + " | " + message);
        }
    });

    private static GroupSnapshot snapshotOf(String json) {
        return snapshotOf("t", json);
    }

    private static GroupSnapshot snapshotOf(String fileName, String json) {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("m2-engine");
            java.nio.file.Path file = dir.resolve(fileName + ".json");
            java.nio.file.Files.write(file, json.getBytes("UTF-8"),
                    java.nio.file.StandardOpenOption.CREATE);
            return GroupConfigParser.parseGroup(dir, "t", "config/groups/t");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String mock(String rules, String defaultAction) {
        return "{\"name\":\"t\",\"mocks\":[{\"class\":\"" + CLS + "\",\"method\":\"readStatus\","
                + "\"signature\":\"" + DESC + "\",\"enabled\":true,"
                + (defaultAction == null ? "" : "\"defaultAction\":" + defaultAction + ",")
                + "\"rules\":[" + rules + "]}]}";
    }

    private static String fullRule(String args, String action) {
        return "{\"matchType\":\"FULL_MATCH\",\"args\":" + args + ",\"action\":" + action + "}";
    }

    private static String patternRule(String argsPattern, String action) {
        return "{\"matchType\":\"PATTERN_MATCH\",\"argsPattern\":" + argsPattern
                + ",\"action\":" + action + "}";
    }

    private static final String ACTION_5 = "{\"type\":\"VALUE\",\"value\":5}";

    @Test
    void fullMatchHitAndMissWithDefaultAction() {
        GroupSnapshot snapshot = snapshotOf(mock(
                fullRule("[1,\"CH1\"]", ACTION_5),
                "{\"type\":\"VALUE\",\"value\":0}"));
        MockResult hit = engine.decide(snapshot, CLS, "readStatus", DESC,
                new Object[]{1, "CH1"});
        assertNotNull(hit);
        assertEquals(MockResult.VALUE, hit.code);
        assertEquals(5, ((Integer) hit.value).intValue());
        // 不命中 → defaultAction 0
        MockResult miss = engine.decide(snapshot, CLS, "readStatus", DESC,
                new Object[]{1, "CH2"});
        assertNotNull(miss);
        assertEquals(0, ((Integer) miss.value).intValue());
    }

    @Test
    void noDefaultActionFallsBackToReal() {
        GroupSnapshot snapshot = snapshotOf(mock(fullRule("[1,\"CH1\"]", ACTION_5), null));
        assertNull(engine.decide(snapshot, CLS, "readStatus", DESC,
                new Object[]{2, "CH1"}));
        assertNull(engine.decide(snapshot, CLS, "readStatus", DESC,
                new Object[]{1, "CH1", "extra"})); // 参数数量不符=不匹配
    }

    @Test
    void rulesAreFirstMatch() {
        // 宽 PATTERN 在前 → 先于精确 FULL 生效（03 §9 用例 4）
        GroupSnapshot snapshot = snapshotOf(mock(
                patternRule("[\"\\\\d+\",\"CH.*\"]", "{\"type\":\"VALUE\",\"value\":99}")
                        + "," + fullRule("[1,\"CH1\"]", ACTION_5),
                null));
        MockResult wide = engine.decide(snapshot, CLS, "readStatus", DESC,
                new Object[]{1, "CH1"});
        assertEquals(99, ((Integer) wide.value).intValue());
    }

    @Test
    void patternMatchNormalizesEachArg() {
        GroupSnapshot snapshot = snapshotOf(mock(
                patternRule("[\"\\\\d+\",\"CH(9[0-9])\"]",
                        "{\"type\":\"THROW\",\"exception\":\"java.io.IOException\","
                                + "\"message\":\"device timeout\"}"),
                null));
        MockResult miss = engine.decide(snapshot, CLS, "readStatus", DESC,
                new Object[]{1, "CH1"});
        assertNull(miss);
        MockResult hit = engine.decide(snapshot, CLS, "readStatus", DESC,
                new Object[]{91, "CH91"});
        assertNotNull(hit);
        assertEquals(MockResult.THROW, hit.code);
        assertTrue(hit.throwable instanceof IOException);
        assertEquals("device timeout", hit.throwable.getMessage());
    }

    @Test
    void patternMatchNullArgMatchesLiteralNull() {
        GroupSnapshot snapshot = snapshotOf(mock(
                patternRule("[\"null\",\".*\"]", ACTION_5), null));
        assertNotNull(engine.decide(snapshot, CLS, "readStatus", DESC,
                new Object[]{null, "x"}));
    }

    @Test
    void fullMatchDeepEquality() {
        // byte[] 逐字节比较 + $hex 返回值（03 §9 用例 9 的单元层验证）
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"send\",\"signature\":\"([B)[B\","
                + "\"enabled\":true,\"rules\":["
                + fullRule("[{\"$hex\":\"0909\"}]",
                        "{\"type\":\"VALUE\",\"value\":{\"$hex\":\"010203\"}}")
                + "]}]}";
        GroupSnapshot snap = snapshotOf(json);
        MockResult hit = engine.decide(snap, CLS, "send", "([B)[B",
                new Object[]{new byte[]{9, 9}});
        assertNotNull(hit);
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) hit.value);
        assertNull(engine.decide(snap, CLS, "send", "([B)[B", new Object[]{new byte[]{9, 8}}));
    }

    @Test
    void signatureFallbackWithoutDescriptor() {
        // 无 signature 条目：作用于同名全部重载（className#methodName# 兜底）
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"getName\",\"enabled\":true,"
                + "\"rules\":[]," + "\"defaultAction\":{\"type\":\"VALUE\",\"value\":\"MOCK\"}}]}";
        GroupSnapshot snap = snapshotOf(json);
        MockResult any = engine.decide(snap, CLS, "getName", "()Ljava/lang/String;",
                new Object[0]);
        assertNotNull(any);
        assertEquals("MOCK", any.value);
    }

    @Test
    void exactSignatureEntryTakesPriorityOverFallback() {
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"readStatus\",\"enabled\":true,"
                + "\"rules\":[]," + "\"defaultAction\":{\"type\":\"VALUE\",\"value\":111}},"
                + "{\"class\":\"" + CLS + "\",\"method\":\"readStatus\",\"signature\":\""
                + DESC + "\",\"enabled\":true,\"rules\":[],"
                + "\"defaultAction\":{\"type\":\"VALUE\",\"value\":222}}]}";
        GroupSnapshot snap = snapshotOf(json);
        MockResult exact = engine.decide(snap, CLS, "readStatus", DESC,
                new Object[]{1, "CH1"});
        assertEquals(222, ((Integer) exact.value).intValue());
        // 其它签名（同名的另一个重载描述符）落兜底条目
        MockResult other = engine.decide(snap, CLS, "readStatus", "(II)I",
                new Object[]{1, 2});
        assertEquals(111, ((Integer) other.value).intValue());
    }

    @Test
    void valueOnVoidMethodFallsBackToReal() {
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"powerOn\",\"signature\":\"(I)V\","
                + "\"enabled\":true,\"rules\":[],"
                + "\"defaultAction\":{\"type\":\"VALUE\",\"value\":5}}]}";
        GroupSnapshot snap = snapshotOf(json);
        assertNull(engine.decide(snap, CLS, "powerOn", "(I)V", new Object[]{1}));
        assertTrue(reported.get() > 0, "应记录运行期错配 lastError");
    }

    @Test
    void voidOnNonVoidMethodFallsBackToReal() {
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"readStatus\",\"signature\":\""
                + DESC + "\",\"enabled\":true,\"rules\":[],"
                + "\"defaultAction\":{\"type\":\"VOID\"}}]}";
        assertNull(engine.decide(snapshotOf(json), CLS, "readStatus", DESC,
                new Object[]{1, "CH1"}));
    }

    @Test
    void voidActionSkipsRealCall() {
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"powerOn\",\"signature\":\"(I)V\","
                + "\"enabled\":true,\"rules\":[],"
                + "\"defaultAction\":{\"type\":\"VOID\"}}]}";
        GroupSnapshot snap = snapshotOf(json);
        MockResult r = engine.decide(snap, CLS, "powerOn", "(I)V", new Object[]{1});
        assertNotNull(r);
        assertEquals(MockResult.VOID, r.code);
    }

    @Test
    void throwWithoutMessageUsesNoArgConstructor() {
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"powerOn\",\"signature\":\"(I)V\","
                + "\"enabled\":true,\"rules\":["
                + patternRule("[\"\\\\d+\"]",
                        "{\"type\":\"THROW\",\"exception\":\"java.lang.IllegalStateException\"}")
                + "]}]}";
        MockResult r = engine.decide(snapshotOf(json), CLS, "powerOn", "(I)V",
                new Object[]{3});
        assertNotNull(r);
        assertEquals(MockResult.THROW, r.code);
        assertEquals(IllegalStateException.class, r.throwable.getClass());
        assertNull(r.throwable.getMessage());
    }

    @Test
    void throwUnknownClassFallsBackToReal() {
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"powerOn\",\"signature\":\"(I)V\","
                + "\"enabled\":true,\"rules\":["
                + patternRule("[\"\\\\d+\"]",
                        "{\"type\":\"THROW\",\"exception\":\"not.a.RealException\"}")
                + "]}]}";
        assertNull(engine.decide(snapshotOf(json), CLS, "powerOn", "(I)V", new Object[]{3}));
        assertTrue(reported.get() > 0);
    }

    @Test
    void valueConversionFailureFallsBackToReal() {
        // String 返回配置数字 → 可转字符串（成功）；不可转场景：char 目标给长串
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"getName\","
                + "\"signature\":\"()Ljava/lang/String;\",\"enabled\":true,\"rules\":[],"
                + "\"defaultAction\":{\"type\":\"VALUE\",\"value\":123}}]}";
        GroupSnapshot snap = snapshotOf(json);
        MockResult r = engine.decide(snap, CLS, "getName", "()Ljava/lang/String;",
                new Object[0]);
        assertEquals("123", r.value);
        // 转换失败场景：String 返回目标配 JSON 对象 → 转换失败 → 日志+lastError+REAL
        String bad = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"getName\","
                + "\"signature\":\"()Ljava/lang/String;\",\"enabled\":true,\"rules\":[],"
                + "\"defaultAction\":{\"type\":\"VALUE\",\"value\":{\"a\":1}}}]}";
        assertNull(engine.decide(snapshotOf(bad), CLS, "getName",
                "()Ljava/lang/String;", new Object[0]));
        assertTrue(reported.get() > 0, "转换失败应记录 lastError");
    }

    @Test
    void listValueUsesGenericReturnType() {
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"channels\","
                + "\"signature\":\"()Ljava/util/List;\",\"enabled\":true,\"rules\":[],"
                + "\"defaultAction\":{\"type\":\"VALUE\",\"value\":[7,8]}}]}";
        GroupSnapshot snap = snapshotOf(json);
        MockResult r = engine.decide(snap, CLS, "channels", "()Ljava/util/List;",
                new Object[0]);
        assertNotNull(r);
        List<?> list = (List<?>) r.value;
        assertEquals(Integer.valueOf(7), list.get(0));
        assertEquals(Integer.valueOf(8), list.get(1));
    }

    @Test
    void pojoReturnValueFieldInjection() {
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"status\","
                + "\"signature\":\"()Lcom/equipmock/agent/config/MatchEngineTest$ObjectPojo;\","
                + "\"enabled\":true,\"rules\":[],"
                + "\"defaultAction\":{\"type\":\"VALUE\",\"value\":{\"powered\":true,"
                + "\"voltage\":220}}}]}";
        GroupSnapshot snap = snapshotOf(json);
        MockResult r = engine.decide(snap, CLS, "status",
                "()Lcom/equipmock/agent/config/MatchEngineTest$ObjectPojo;", new Object[0]);
        assertNotNull(r);
        ObjectPojo pojo = (ObjectPojo) r.value;
        assertTrue(pojo.powered);
        assertEquals(220, pojo.voltage);
    }

    @Test
    void disabledEntrySkippedAtIndexTime() {
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"readStatus\",\"enabled\":false,"
                + "\"rules\":[],\"defaultAction\":" + ACTION_5 + "}]}";
        GroupSnapshot snap = snapshotOf(json);
        assertNull(engine.decide(snap, CLS, "readStatus", DESC, new Object[]{1, "CH1"}));
        assertTrue(snap.index().targetClasses().isEmpty());
    }

    @Test
    void emptySnapshotIsReal() {
        assertNull(engine.decide(GroupSnapshot.empty("t"), CLS, "readStatus", DESC,
                new Object[]{1, "CH1"}));
        assertNull(engine.decide(null, CLS, "readStatus", DESC, new Object[]{1, "CH1"}));
    }

    @Test
    void multiFileMergeOrderNaturalFileSequence() {
        // a2 先于 a10（自然序）；同 methodId 规则链按文件序拼接
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("m2-merge");
            java.nio.file.Files.write(dir.resolve("a10.json"),
                    ("{\"name\":\"a\",\"mocks\":[{\"class\":\"" + CLS
                            + "\",\"method\":\"getName\",\"enabled\":true,\"rules\":["
                            + fullRule("[\"z\"]", "{\"type\":\"VALUE\",\"value\":10}")
                            + "]}]}").getBytes("UTF-8"));
            java.nio.file.Files.write(dir.resolve("a2.json"),
                    ("{\"name\":\"b\",\"mocks\":[{\"class\":\"" + CLS
                            + "\",\"method\":\"getName\",\"enabled\":true,\"rules\":["
                            + fullRule("[\"z\"]", "{\"type\":\"VALUE\",\"value\":2}")
                            + "]}]}").getBytes("UTF-8"));
            GroupSnapshot merged = GroupConfigParser.parseGroup(dir, "t", "config/groups/t");
            MockResult r = engine.decide(merged, CLS, "getName", "()Ljava/lang/String;",
                    new Object[]{"z"});
            assertEquals("2", r.value, "a2 应先于 a10 生效（自然序）");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fullMatchAgainstMapAndNestedStructures() {
        String json = "{\"name\":\"t\",\"mocks\":["
                + "{\"class\":\"" + CLS + "\",\"method\":\"readStatus\",\"enabled\":true,"
                + "\"rules\":["
                + fullRule("[1,\"CH1\",{\"a\":1,\"b\":[true,null]}]",
                        "{\"type\":\"VALUE\",\"value\":5}")
                + "]}]}";
        GroupSnapshot snap = snapshotOf(json);
        Map<String, Object> arg3 = new LinkedHashMap<String, Object>();
        arg3.put("b", Arrays.asList(Boolean.TRUE, null));
        arg3.put("a", 1);
        // 无签名条目 → 作用于任意签名；参数按位比较
        MockResult r = engine.decide(snap, CLS, "readStatus", "(ILjava/lang/String;Ljava/util/Map;)I",
                new Object[]{1, "CH1", arg3});
        assertNotNull(r);
        assertEquals(5, ((Integer) r.value).intValue());
    }

    @Test
    void jsonNullConfigArgMatchesNullActual() {
        GroupSnapshot snap = snapshotOf(mock(fullRule("[null,\"CH1\"]", ACTION_5), null));
        assertNotNull(engine.decide(snap, CLS, "readStatus", DESC,
                new Object[]{null, "CH1"}));
        assertNull(engine.decide(snap, CLS, "readStatus", DESC,
                new Object[]{1, "CH1"}));
    }
}
