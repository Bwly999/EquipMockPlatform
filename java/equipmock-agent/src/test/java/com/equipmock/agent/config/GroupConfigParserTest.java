package com.equipmock.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加载与校验单测（03 §6 全部规则正反例 + 组级原子性 + 合并序 + tmp/bak 排除）。
 */
class GroupConfigParserTest {

    @TempDir
    Path tempDir;

    private Path group(String name) throws Exception {
        Path dir = tempDir.resolve("config/groups").resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private GroupSnapshot parseOk(String fileName, String json) throws Exception {
        Path dir = group("g");
        Files.write(dir.resolve(fileName), json.getBytes(StandardCharsets.UTF_8));
        return GroupConfigParser.parseGroup(dir, "g", "config/groups/g");
    }

    private ConfigSchemaException parseFail(String fileName, String json) throws Exception {
        Path dir = group("g");
        Files.write(dir.resolve(fileName), json.getBytes(StandardCharsets.UTF_8));
        try {
            GroupConfigParser.parseGroup(dir, "g", "config/groups/g");
        } catch (ConfigSchemaException e) {
            return e;
        }
        fail("应当抛出 ConfigSchemaException");
        return null;
    }

    // ------------------------------------------------------------------
    // 正例
    // ------------------------------------------------------------------

    @Test
    void happyPathParsesFullStructure() throws Exception {
        GroupSnapshot snap = parseOk("cabinet.json", "{\n"
                + " \"$schema\": \"equipmock/subgroup@1\",\n"
                + " \"name\": \"cabinet\",\n"
                + " \"description\": \"机柜电源相关 Mock\",\n"
                + " \"mocks\": [\n"
                + "  {\"class\": \"com.equip.demo.PowerDevice\", \"method\": \"readStatus\","
                + " \"signature\": \"(ILjava/lang/String;)I\", \"enabled\": true,\n"
                + "  \"defaultAction\": {\"type\": \"VALUE\", \"value\": 0},\n"
                + "  \"rules\": [\n"
                + "   {\"matchType\": \"FULL_MATCH\", \"args\": [1, \"CH1\"],"
                + " \"action\": {\"type\": \"VALUE\", \"value\": 5}},\n"
                + "   {\"matchType\": \"PATTERN_MATCH\", \"argsPattern\": [\"\\\\d+\","
                + " \"CH(9[0-9])\"], \"action\": {\"type\": \"THROW\","
                + " \"exception\": \"java.io.IOException\", \"message\": \"device timeout\"}}\n"
                + "  ]},\n"
                + "  {\"class\": \"com.equip.demo.PowerDevice\", \"method\": \"powerOn\","
                + " \"enabled\": true, \"rules\": [],"
                + " \"defaultAction\": {\"type\": \"VOID\"}}\n"
                + " ]\n}");
        assertEquals(1, snap.files().size());
        SubGroupFile file = snap.files().get("cabinet");
        assertEquals(2, file.entryCount());
        assertEquals(1, snap.index().targetClasses().size());
        assertTrue(snap.index().targetClasses().contains("com.equip.demo.PowerDevice"));
        MockEntry readStatus = file.entries.get(0);
        assertEquals("(ILjava/lang/String;)I", readStatus.descriptor);
        assertEquals(2, readStatus.rules.size());
        assertEquals(MockRule.MatchType.FULL_MATCH, readStatus.rules.get(0).matchType);
        assertEquals(2, readStatus.rules.get(1).patterns.size()); // 正则已预编译
        assertEquals(ActionDef.Type.THROW, readStatus.rules.get(1).action.type);
        assertEquals(ActionDef.Type.VOID, file.entries.get(1).defaultAction.type);
    }

    @Test
    void valueNullIsAllowedForReferenceReturn() throws Exception {
        GroupSnapshot snap = parseOk("n.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"enabled\":true,\"rules\":[],"
                + "\"defaultAction\":{\"type\":\"VALUE\",\"value\":null}}]}");
        assertTrue(snap.files().get("n").entries.get(0).defaultAction.value.isJsonNull());
    }

    @Test
    void tmpAndBakFilesExcluded() throws Exception {
        Path dir = group("g");
        Files.write(dir.resolve("a.json"),
                "{\"mocks\":[]}".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("a.json.tmp-123456"),
                "{BROKEN".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("b.json.bak"),
                "{BROKEN".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("notes.txt"),
                "not json".getBytes(StandardCharsets.UTF_8));
        List<Path> files = GroupConfigParser.listJsonFiles(dir);
        assertEquals(1, files.size());
        assertEquals("a.json", files.get(0).getFileName().toString());
    }

    @Test
    void naturalFileOrdering() throws Exception {
        Path dir = group("g");
        for (String name : new String[]{"a10.json", "a2.json", "b.json", "a.json"}) {
            Files.write(dir.resolve(name), "{\"mocks\":[]}".getBytes(StandardCharsets.UTF_8));
        }
        List<Path> files = GroupConfigParser.listJsonFiles(dir);
        assertEquals("a.json", files.get(0).getFileName().toString());
        assertEquals("a2.json", files.get(1).getFileName().toString());
        assertEquals("a10.json", files.get(2).getFileName().toString());
        assertEquals("b.json", files.get(3).getFileName().toString());
    }

    // ------------------------------------------------------------------
    // 反例（03 §6 全部规则）
    // ------------------------------------------------------------------

    @Test
    void syntaxErrorIncludesLineColumnAndFile() throws Exception {
        ConfigSchemaException e = parseFail("bad.json", "{\n  \"mocks\": [\n");
        assertNotNull(e);
        assertTrue(e.getMessage().contains("bad.json"));
        assertTrue(e.getMessage().contains("JSON 语法错误"), "实际消息: " + e.getMessage());
        assertTrue(e.getMessage().contains("line"), "应含行列号: " + e.getMessage());
    }

    @Test
    void topLevelMustBeObject() throws Exception {
        assertNotNull(parseFail("arr.json", "[1,2]"));
    }

    @Test
    void mocksRequired() throws Exception {
        ConfigSchemaException e = parseFail("nomocks.json", "{\"name\":\"x\"}");
        assertTrue(e.getMessage().contains("mocks"));
    }

    @Test
    void unknownFieldRejected() throws Exception {
        ConfigSchemaException e = parseFail("unk.json",
                "{\"mocks\":[{\"class\":\"a.B\",\"method\":\"m\",\"enabled\":true,"
                        + "\"rules\":[],\"whatIsThis\":1}]}");
        assertTrue(e.getMessage().contains("whatIsThis"));
    }

    @Test
    void classMustBeFqcn() throws Exception {
        ConfigSchemaException e = parseFail("cls.json",
                "{\"mocks\":[{\"class\":\"not a fqcn\",\"method\":\"m\","
                        + "\"enabled\":true,\"rules\":[]}]}");
        assertTrue(e.getMessage().contains("class"));
    }

    @Test
    void methodMustBeIdentifier() throws Exception {
        assertNotNull(parseFail("m.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"bad-name\",\"enabled\":true,\"rules\":[]}]}"));
    }

    @Test
    void enabledRequired() throws Exception {
        assertNotNull(parseFail("en.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"rules\":[]}]}"));
    }

    @Test
    void rulesRequired() throws Exception {
        assertNotNull(parseFail("ru.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"enabled\":true}]}"));
    }

    @Test
    void illegalSignatureRejected() throws Exception {
        ConfigSchemaException e = parseFail("sig.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"signature\":\"(I)not-a-descriptor\","
                + "\"enabled\":true,\"rules\":[]}]}");
        assertTrue(e.getMessage().contains("signature"));
        // 合法示例应通过（独立组目录，避免坏文件残留导致整组失败）
        Path dir = group("g2");
        Files.write(dir.resolve("sig-ok.json"), ("{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"signature\":\"([Ljava/lang/String;Ljava/util/Map;)V\","
                + "\"enabled\":true,\"rules\":[]}]}").getBytes(StandardCharsets.UTF_8));
        GroupSnapshot ok = GroupConfigParser.parseGroup(dir, "g2", "config/groups/g2");
        assertEquals("([Ljava/lang/String;Ljava/util/Map;)V",
                ok.files().get("sig-ok").entries.get(0).descriptor);
    }

    @Test
    void signatureMissingSemicolonRejected() throws Exception {
        assertNotNull(parseFail("sig2.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"signature\":\"(Ljava/lang/String)I\","
                + "\"enabled\":true,\"rules\":[]}]}"));
    }

    @Test
    void illegalMatchTypeRejected() throws Exception {
        ConfigSchemaException e = parseFail("mt.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"enabled\":true,\"rules\":["
                + "{\"matchType\":\"REGEX\",\"args\":[],"
                + "\"action\":{\"type\":\"VOID\"}}]}]}");
        assertTrue(e.getMessage().contains("matchType"));
    }

    @Test
    void fullMatchRequiresArgs() throws Exception {
        ConfigSchemaException e = parseFail("full-no-args.json",
                "{\"mocks\":[{\"class\":\"a.B\",\"method\":\"m\",\"enabled\":true,"
                        + "\"rules\":[{\"matchType\":\"FULL_MATCH\","
                        + "\"action\":{\"type\":\"VOID\"}}]}]}");
        assertTrue(e.getMessage().contains("args"));
    }

    @Test
    void fullMatchMustNotHavePattern() throws Exception {
        ConfigSchemaException e = parseFail("full-with-pattern.json",
                "{\"mocks\":[{\"class\":\"a.B\",\"method\":\"m\",\"enabled\":true,"
                        + "\"rules\":[{\"matchType\":\"FULL_MATCH\",\"args\":[1],"
                        + "\"argsPattern\":[\"x\"],"
                        + "\"action\":{\"type\":\"VOID\"}}]}]}");
        assertTrue(e.getMessage().contains("argsPattern"));
    }

    @Test
    void patternMatchRequiresArgsPattern() throws Exception {
        assertNotNull(parseFail("pat-empty.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"enabled\":true,\"rules\":["
                + "{\"matchType\":\"PATTERN_MATCH\","
                + "\"action\":{\"type\":\"VOID\"}}]}]}"));
    }

    @Test
    void patternMatchEachItemMustCompile() throws Exception {
        ConfigSchemaException e = parseFail("pat-bad.json",
                "{\"mocks\":[{\"class\":\"a.B\",\"method\":\"m\",\"enabled\":true,"
                        + "\"rules\":[{\"matchType\":\"PATTERN_MATCH\","
                        + "\"argsPattern\":[\"\\\\d+\",\"[unclosed\"],"
                        + "\"action\":{\"type\":\"VOID\"}}]}]}");
        assertTrue(e.getMessage().contains("argsPattern[1]"));
        assertTrue(e.getMessage().contains("非法正则"));
    }

    @Test
    void patternMatchItemMustBeString() throws Exception {
        assertNotNull(parseFail("pat-num.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"enabled\":true,\"rules\":["
                + "{\"matchType\":\"PATTERN_MATCH\",\"argsPattern\":[5],"
                + "\"action\":{\"type\":\"VOID\"}}]}]}"));
    }

    @Test
    void actionTypeRequired() throws Exception {
        ConfigSchemaException e = parseFail("act-no-type.json",
                "{\"mocks\":[{\"class\":\"a.B\",\"method\":\"m\",\"enabled\":true,"
                        + "\"rules\":[{\"matchType\":\"FULL_MATCH\",\"args\":[],"
                        + "\"action\":{}}]}]}");
        assertTrue(e.getMessage().contains("type"));
    }

    @Test
    void illegalActionTypeRejected() throws Exception {
        assertNotNull(parseFail("act-bad-type.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"enabled\":true,\"rules\":["
                + "{\"matchType\":\"FULL_MATCH\",\"args\":[],"
                + "\"action\":{\"type\":\"SLEEP\"}}]}]}"));
    }

    @Test
    void valueRequiresValueField() throws Exception {
        ConfigSchemaException e = parseFail("act-no-value.json",
                "{\"mocks\":[{\"class\":\"a.B\",\"method\":\"m\",\"enabled\":true,"
                        + "\"rules\":[]"
                        + ",\"defaultAction\":{\"type\":\"VALUE\"}}]}");
        assertTrue(e.getMessage().contains("VALUE 必带 value"));
    }

    @Test
    void voidMustNotCarryValue() throws Exception {
        ConfigSchemaException e = parseFail("act-void-value.json",
                "{\"mocks\":[{\"class\":\"a.B\",\"method\":\"m\",\"enabled\":true,"
                        + "\"rules\":[{\"matchType\":\"FULL_MATCH\",\"args\":[],"
                        + "\"action\":{\"type\":\"VOID\",\"value\":1}}]}]}");
        assertTrue(e.getMessage().contains("VOID 不带 value"));
    }

    @Test
    void throwRequiresFqcnException() throws Exception {
        ConfigSchemaException e = parseFail("act-bad-exc.json",
                "{\"mocks\":[{\"class\":\"a.B\",\"method\":\"m\",\"enabled\":true,"
                        + "\"rules\":[{\"matchType\":\"FULL_MATCH\",\"args\":[],"
                        + "\"action\":{\"type\":\"THROW\",\"exception\":\"not valid\"}}]}]}");
        assertTrue(e.getMessage().contains("exception"));
        // 缺 exception 同样拒绝
        assertNotNull(parseFail("act-no-exc.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"enabled\":true,\"rules\":["
                + "{\"matchType\":\"FULL_MATCH\",\"args\":[],"
                + "\"action\":{\"type\":\"THROW\",\"message\":\"x\"}}]}]}"));
    }

    @Test
    void throwWithMessageOk() throws Exception {
        assertNotNull(parseOk("throw-ok.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"enabled\":true,\"rules\":[{\"matchType\":\"FULL_MATCH\","
                + "\"args\":[],\"action\":{\"type\":\"THROW\","
                + "\"exception\":\"java.io.IOException\",\"message\":\"timeout\"}}]}]}"));
    }

    @Test
    void throwMustNotCarryValue() throws Exception {
        assertNotNull(parseFail("act-throw-value.json", "{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"enabled\":true,\"rules\":["
                + "{\"matchType\":\"FULL_MATCH\",\"args\":[],"
                + "\"action\":{\"type\":\"THROW\",\"exception\":\"java.io.IOException\","
                + "\"value\":1}}]}]}"));
    }

    // ------------------------------------------------------------------
    // 组级原子性（03 §2）
    // ------------------------------------------------------------------

    @Test
    void anyBadFileRejectsWholeGroup() throws Exception {
        Path dir = group("g");
        Files.write(dir.resolve("good.json"), ("{\"mocks\":[{\"class\":\"a.B\","
                + "\"method\":\"m\",\"enabled\":true,\"rules\":[]}]}")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("broken.json"), "{ nope".getBytes(StandardCharsets.UTF_8));
        try {
            GroupConfigParser.parseGroup(dir, "g", "config/groups/g");
            fail("任一文件失败应整组拒绝");
        } catch (ConfigSchemaException e) {
            assertTrue(e.getMessage().contains("broken.json"));
        }
    }

    @Test
    void missingGroupDirectoryRejected() throws Exception {
        Path missing = tempDir.resolve("config/groups/none");
        try {
            GroupConfigParser.parseGroup(missing, "none", "config/groups/none");
            fail("组目录不存在应拒绝");
        } catch (ConfigSchemaException e) {
            assertTrue(e.getMessage().contains("配置组目录不存在"));
        }
    }
}
