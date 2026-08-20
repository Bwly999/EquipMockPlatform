package com.equipmock.testkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * testkit 文件协议自测（无 agent 挂载：只验证骨架/原子写/registry 读改写，
 * 不调用 await* —— state.json 由 agent 专写，本 JVM 没有 agent）。
 *
 * <p>本 JVM 无 agent 监听，允许每方法重定向 home 到独立 @TempDir 重建骨架
 * （IT 场景 home 由 -Dequipmock.home 固定，不做重定向）。
 */
class TestKitHomeLayoutTest extends EquipMockTestBase {

    @TempDir
    Path tempDir;

    @BeforeEach
    void freshHomePerMethod() throws Exception {
        home = tempDir.resolve("home");
        seedSkeleton();
    }

    @Test
    void skeletonSeededPerContract() throws Exception {
        assertTrue(Files.isDirectory(subGroupFile("default", ".").getParent()),
                "config/groups/default 目录存在");
        assertTrue(Files.isDirectory(pluginsDir()), "plugins/ 目录存在");
        assertTrue(Files.isRegularFile(settingsFile()), "settings.json 存在");
        assertTrue(Files.isRegularFile(registryFile()), "plugin-registry.json 存在");
        try (Reader r = Files.newBufferedReader(settingsFile(), StandardCharsets.UTF_8)) {
            JsonObject settings = JsonParser.parseReader(r).getAsJsonObject();
            assertEquals("default", settings.get("activeGroup").getAsString());
            assertTrue(settings.get("mockEnabled").getAsBoolean());
        }
        try (Reader r = Files.newBufferedReader(registryFile(), StandardCharsets.UTF_8)) {
            assertEquals(0, JsonParser.parseReader(r).getAsJsonObject()
                    .getAsJsonArray("plugins").size(), "初始 registry 为空清单");
        }
    }

    @Test
    void writeSubGroupUsesAtomicWriteWithoutResidue() throws Exception {
        String json = "{\n  \"$schema\": \"equipmock/subgroup@1\",\n"
                + "  \"name\": \"cabinet\",\n  \"mocks\": []\n}\n";
        writeSubGroup("default", "cabinet.json", json);
        Path file = subGroupFile("default", "cabinet.json");
        assertEquals(json, new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                "内容原样落盘");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(file.getParent())) {
            for (Path p : stream) {
                assertFalse(p.getFileName().toString().contains(".tmp"),
                        "无 tmp 残留: " + p);
            }
        }
        // 非法组名拒绝（04 §1 目录名规则）
        try {
            writeSubGroup("坏 组名", "x.json", "{}");
            throw new AssertionError("非法组名应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    void registerPluginCopiesJarAndRegistryEntry() throws Exception {
        Path jar = dummyJar("mock-demo-1.0.0.jar", "mock-demo");
        registerPlugin(jar, "mock-demo");
        Path copied = pluginsDir().resolve("mock-demo-1.0.0.jar");
        assertTrue(Files.isRegularFile(copied), "jar 已拷入 plugins/");
        assertEquals(Files.size(jar), Files.size(copied), "拷贝内容大小一致");
        JsonObject entry = parse(registryFile()).getAsJsonArray("plugins").get(0)
                .getAsJsonObject();
        assertEquals("mock-demo", entry.get("id").getAsString());
        assertEquals("mock-demo-1.0.0.jar", entry.get("jar").getAsString());
        assertTrue(entry.get("enabled").getAsBoolean());
        // 幂等：重复登记不产生第二条目
        registerPlugin(jar, "mock-demo");
        assertEquals(1, parse(registryFile()).getAsJsonArray("plugins").size());
    }

    @Test
    void setPluginEnabledKeepsJarField() throws Exception {
        registerPlugin(dummyJar("mock-demo-1.0.0.jar", "mock-demo"), "mock-demo");
        setPluginEnabled("mock-demo", false);
        JsonObject entry = parse(registryFile()).getAsJsonArray("plugins").get(0)
                .getAsJsonObject();
        assertFalse(entry.get("enabled").getAsBoolean());
        assertEquals("mock-demo-1.0.0.jar", entry.get("jar").getAsString(),
                "翻转 enabled 不丢 jar 字段");
        setPluginEnabled("mock-demo", true);
        assertTrue(parse(registryFile()).getAsJsonArray("plugins").get(0)
                .getAsJsonObject().get("enabled").getAsBoolean());
    }

    @Test
    void stateReaderReturnsNullWithoutAgent() {
        assertNull(readStateJson(), "无 agent 时 state.json 不存在，读取返回 null（不抛异常）");
    }

    /** 读取并关闭（Windows 上泄漏句柄会让后续原子 move AccessDenied） */
    private static JsonObject parse(Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** 构造带 Plugin-Id manifest 的最小 jar（仅验证拷贝/登记，不被加载） */
    private Path dummyJar(String fileName, String pluginId) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.putValue("Manifest-Version", "1.0");
        attrs.putValue("Plugin-Id", pluginId);
        attrs.putValue("Plugin-Version", "1.0.0");
        Path jar = tempDir.resolve(fileName);
        JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
        out.close();
        return jar;
    }
}
