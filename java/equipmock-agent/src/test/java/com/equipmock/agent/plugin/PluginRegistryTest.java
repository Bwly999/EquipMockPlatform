package com.equipmock.agent.plugin;

import com.equipmock.agent.config.ConfigSchemaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * plugin-registry.json 解析与 diff（04 §4 / 04 §7：增/删/启停/换 jar）。
 */
class PluginRegistryTest {

    private Path registryFile;
    private Path dir;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("equipmock-registry-test");
        registryFile = dir.resolve("plugin-registry.json");
    }

    private void write(String json) throws Exception {
        Files.write(registryFile, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parseFullContractFields() throws Exception {
        write("{\"plugins\":[{\"id\":\"p1\",\"alias\":\"插件一\",\"jar\":\"p1.jar\","
                + "\"enabled\":true,\"importedAt\":\"2026-08-19T10:00:00+08:00\","
                + "\"note\":\"n\"},{\"id\":\"p2\",\"jar\":\"p2.jar\",\"enabled\":false}]}");
        PluginRegistry registry = PluginRegistry.parse(registryFile, "plugins/plugin-registry.json");
        assertEquals(2, registry.size());
        assertEquals("p1", registry.entries().get(0).id);
        assertEquals("p1.jar", registry.entries().get(0).jar);
        assertTrue(registry.entries().get(0).enabled);
        assertEquals(false, registry.entries().get(1).enabled);
    }

    @Test
    void brokenJsonRejected() {
        assertThrows(ConfigSchemaException.class, () -> {
            write("{ BROKEN !!!");
            PluginRegistry.parse(registryFile, "plugins/plugin-registry.json");
        });
    }

    @Test
    void missingOrInvalidFieldsRejected() throws Exception {
        write("{}");
        assertThrows(ConfigSchemaException.class,
                () -> PluginRegistry.parse(registryFile, "x"),
                "plugins 缺失应拒绝");
        write("{\"plugins\":{}}");
        assertThrows(ConfigSchemaException.class,
                () -> PluginRegistry.parse(registryFile, "x"));
        write("{\"plugins\":[{\"jar\":\"a.jar\",\"enabled\":true}]}");
        assertThrows(ConfigSchemaException.class,
                () -> PluginRegistry.parse(registryFile, "x"),
                "缺 id 应拒绝");
        write("{\"plugins\":[{\"id\":\"a\",\"jar\":\"a.jar\"}]}");
        assertThrows(ConfigSchemaException.class,
                () -> PluginRegistry.parse(registryFile, "x"),
                "缺 enabled 应拒绝");
        write("{\"plugins\":[{\"id\":\"a\",\"jar\":\"a.jar\",\"enabled\":\"yes\"}]}");
        assertThrows(ConfigSchemaException.class,
                () -> PluginRegistry.parse(registryFile, "x"),
                "enabled 非 boolean 应拒绝");
        write("{\"plugins\":[{\"id\":\"a\",\"jar\":\"a.jar\",\"enabled\":true},"
                + "{\"id\":\"a\",\"jar\":\"b.jar\",\"enabled\":true}]}");
        assertThrows(ConfigSchemaException.class,
                () -> PluginRegistry.parse(registryFile, "x"),
                "重复 id 应拒绝");
    }

    @Test
    void diffAddRemoveToggle() throws Exception {
        write("{\"plugins\":[]}");
        PluginRegistry empty = PluginRegistry.parse(registryFile, "x");

        write("{\"plugins\":[{\"id\":\"a\",\"jar\":\"a.jar\",\"enabled\":true},"
                + "{\"id\":\"b\",\"jar\":\"b.jar\",\"enabled\":false}]}");
        PluginRegistry two = PluginRegistry.parse(registryFile, "x");
        PluginRegistry.Diff d1 = two.diffFrom(empty);
        assertEquals(2, d1.added.size());
        assertTrue(d1.removed.isEmpty());
        assertTrue(d1.enabledChanged.isEmpty());

        write("{\"plugins\":[{\"id\":\"a\",\"jar\":\"a.jar\",\"enabled\":false},"
                + "{\"id\":\"c\",\"jar\":\"c.jar\",\"enabled\":true}]}");
        PluginRegistry next = PluginRegistry.parse(registryFile, "x");
        PluginRegistry.Diff d2 = next.diffFrom(two);
        assertEquals("c", d2.added.get(0).id);
        assertEquals("b", d2.removed.get(0).id);
        assertEquals("a", d2.enabledChanged.get(0).id);
        assertEquals(false, d2.enabledChanged.get(0).enabled);
        assertTrue(next.diffFrom(next).isEmpty(), "内容未变 diff 为空（04 §8 幂等）");
    }

    @Test
    void diffJarChangeIsRemovePlusAdd() throws Exception {
        write("{\"plugins\":[{\"id\":\"a\",\"jar\":\"a-1.0.0.jar\",\"enabled\":true}]}");
        PluginRegistry old = PluginRegistry.parse(registryFile, "x");
        write("{\"plugins\":[{\"id\":\"a\",\"jar\":\"a-2.0.0.jar\",\"enabled\":true}]}");
        PluginRegistry.Diff d = PluginRegistry.parse(registryFile, "x").diffFrom(old);
        assertEquals(1, d.removed.size());
        assertEquals(1, d.added.size());
        assertEquals("a-2.0.0.jar", d.added.get(0).jar);
    }

    @Test
    void emptyRegistrySizeZero() {
        assertEquals(0, PluginRegistry.empty().size());
        assertEquals(0, PluginRegistry.empty().entries().size());
        List<PluginRegistry.Entry> none = PluginRegistry.empty().entries();
        assertTrue(none.isEmpty());
    }
}
