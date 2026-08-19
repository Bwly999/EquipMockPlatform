package com.equipmock.agent.plugin;

import com.equipmock.agent.AgentHome;
import com.equipmock.bootstrap.MockResult;
import com.equipmock.agent.RouteTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PluginService 全生命周期（真实最小插件 jar fixture）：
 * 清单驱动装载/MISSING/版本拒绝/缺 Requires/注解扫描失败/启停开关/
 * 运行期 diff（增删启停）/registry 损坏保持旧状态。
 */
class PluginServiceTest {

    private static final String GOOD = FixtureHandlers.class.getName() + "$GoodHandler";

    private Path homeDir;
    private AgentHome home;
    private PluginService service;
    private AtomicInteger stateWrites;
    private List<String> errors;

    @BeforeEach
    void setUp() throws Exception {
        homeDir = Files.createTempDirectory("equipmock-plugin-test");
        home = AgentHome.prepare(homeDir.toString());
        stateWrites = new AtomicInteger();
        errors = new java.util.concurrent.CopyOnWriteArrayList<String>();
        service = new PluginService(home, Logger.getLogger("test"), "1.0.0-SNAPSHOT",
                new Runnable() {
                    @Override
                    public void run() {
                        stateWrites.incrementAndGet();
                    }
                },
                new PluginService.ErrorReporter() {
                    @Override
                    public void report(String file, String message) {
                        errors.add(file + ": " + message);
                    }
                });
    }

    private void writeRegistry(String... entries) throws IOException {
        StringBuilder json = new StringBuilder("{\"plugins\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(entries[i]);
        }
        json.append("]}");
        Path file = home.root().resolve("plugins").resolve("plugin-registry.json");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-test");
        Files.write(tmp, json.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private String entry(String id, String jar, boolean enabled) {
        return "{\"id\":\"" + id + "\",\"jar\":\"" + jar + "\",\"enabled\":" + enabled + "}";
    }

    private static PluginStatus statusOf(PluginService service, String id) {
        for (PluginStatus s : service.statuses()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    private RouteTable configAlwaysReal() {
        return new RouteTable() {
            @Override
            public MockResult lookup(String className, String methodName,
                                     String descriptor) {
                return null;
            }

            @Override
            public Set<String> targetClasses() {
                return java.util.Collections.emptySet();
            }

            @Override
            public Set<String> methodNames(String className) {
                return java.util.Collections.emptySet();
            }
        };
    }

    // ------------------------------------------------------------------
    // 启动装载
    // ------------------------------------------------------------------

    @Test
    void startupWithEmptyRegistryLoadsNothing() {
        service.start();
        assertTrue(service.statuses().isEmpty());
        assertEquals(0, service.routing().targetClasses().size());
    }

    @Test
    void startupWithBrokenRegistryLoadsNothingAndReportsError() throws Exception {
        Files.write(home.root().resolve("plugins").resolve("plugin-registry.json"),
                "{ BROKEN !!!".getBytes(StandardCharsets.UTF_8));
        service.start();
        assertTrue(service.statuses().isEmpty(), "registry 损坏 → 全部插件不加载");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("plugin-registry.json"), errors.get(0));
    }

    @Test
    void goodPluginLoadsStartedAndRoutes() throws Exception {
        Path plugins = home.root().resolve("plugins");
        FixturePluginJar.build(plugins, "good-1.0.0.jar", "good", "1.0.0",
                "equipmock >=1.0.0 <2.0.0", GOOD);
        writeRegistry(entry("good", "good-1.0.0.jar", true));
        service.start();

        PluginStatus status = statusOf(service, "good");
        assertNotNull(status);
        assertEquals(PluginStatus.STARTED, status.state);
        assertEquals("1.0.0", status.version);
        assertEquals(1, status.mockPoints);
        assertNull(status.error);
        assertEquals(1, service.routing().targetClasses().size());
        assertTrue(service.routing().targetClasses().contains("fixture.Target"));
        assertTrue(service.routing().methodNames("fixture.Target").contains("ping"));
        assertTrue(stateWrites.get() > 0, "装载后回写 state");

        // 路由联动：写死 VALUE 生效
        CompositeRouteTable table =
                new CompositeRouteTable(service, configAlwaysReal(),
                        Logger.getLogger("test"));
        MockResult r = table.lookup("fixture.Target", "ping", "()Ljava/lang/String;",
                new Object[0], null);
        assertEquals("FIXED", r.value);
    }

    @Test
    void unregisteredJarIsNotLoaded() throws Exception {
        Path plugins = home.root().resolve("plugins");
        FixturePluginJar.build(plugins, "stray-1.0.0.jar", "stray", "1.0.0",
                "equipmock >=1.0.0", GOOD);
        writeRegistry(); // 清单未登记
        service.start();
        assertTrue(service.statuses().isEmpty(), "清单外的 jar 一律不加载（04 §4）");
        assertEquals(0, service.routing().targetClasses().size());
    }

    @Test
    void missingJarMarkedMissing() throws Exception {
        writeRegistry(entry("ghost", "ghost-1.0.0.jar", true));
        service.start();
        PluginStatus status = statusOf(service, "ghost");
        assertEquals(PluginStatus.MISSING, status.state);
        assertNull(status.version);
        assertTrue(status.error.contains("jar not found"), status.error);
    }

    @Test
    void versionTooHighRejected() throws Exception {
        Path plugins = home.root().resolve("plugins");
        FixturePluginJar.build(plugins, "toohigh-1.0.0.jar", "toohigh", "1.0.0",
                "equipmock >=9.9.9", GOOD);
        writeRegistry(entry("toohigh", "toohigh-1.0.0.jar", true));
        service.start();
        PluginStatus status = statusOf(service, "toohigh");
        assertEquals(PluginStatus.REJECTED, status.state);
        assertEquals("requires equipmock>=9.9.9, current=1.0.0-SNAPSHOT", status.error);
        assertEquals(0, service.routing().targetClasses().size());
    }

    @Test
    void missingRequiresRejected() throws Exception {
        Path plugins = home.root().resolve("plugins");
        FixturePluginJar.build(plugins, "noreq-1.0.0.jar", "noreq", "1.0.0",
                null, GOOD);
        writeRegistry(entry("noreq", "noreq-1.0.0.jar", true));
        service.start();
        PluginStatus status = statusOf(service, "noreq");
        assertEquals(PluginStatus.REJECTED, status.state);
        assertTrue(status.error.contains("missing Plugin-Requires"), status.error);
    }

    @Test
    void registryIdManifestIdMismatchFails() throws Exception {
        Path plugins = home.root().resolve("plugins");
        FixturePluginJar.build(plugins, "realid-1.0.0.jar", "realid", "1.0.0",
                "equipmock >=1.0.0", GOOD);
        writeRegistry(entry("otherid", "realid-1.0.0.jar", true));
        service.start();
        PluginStatus status = statusOf(service, "otherid");
        assertEquals(PluginStatus.FAILED, status.state);
        assertTrue(status.error.contains("不一致"), status.error);
    }

    @Test
    void missingAnnotationFailsPlugin() throws Exception {
        Path plugins = home.root().resolve("plugins");
        FixturePluginJar.build(plugins, "badanno-1.0.0.jar", "badanno", "1.0.0",
                "equipmock >=1.0.0",
                FixtureHandlers.class.getName() + "$NoAnnotationHandler");
        writeRegistry(entry("badanno", "badanno-1.0.0.jar", true));
        service.start();
        PluginStatus status = statusOf(service, "badanno");
        assertEquals(PluginStatus.FAILED, status.state);
        assertTrue(status.error.contains("@MockInterceptor"), status.error);
        assertEquals(0, status.mockPoints);
        assertEquals(0, service.routing().targetClasses().size());
    }

    @Test
    void emptyTargetClassesFailsPlugin() throws Exception {
        Path plugins = home.root().resolve("plugins");
        FixturePluginJar.build(plugins, "emptytgt-1.0.0.jar", "emptytgt", "1.0.0",
                "equipmock >=1.0.0",
                FixtureHandlers.class.getName() + "$EmptyTargetsHandler");
        writeRegistry(entry("emptytgt", "emptytgt-1.0.0.jar", true));
        service.start();
        PluginStatus status = statusOf(service, "emptytgt");
        assertEquals(PluginStatus.FAILED, status.state);
        assertTrue(status.error.contains("targetClasses"), status.error);
    }

    // ------------------------------------------------------------------
    // 运行期 diff（热导入/启停/卸载）
    // ------------------------------------------------------------------

    @Test
    void hotLifecycleAddToggleRemove() throws Exception {
        service.start(); // 空清单启动
        Path plugins = home.root().resolve("plugins");

        // 初始：无路由
        CompositeRouteTable table = new CompositeRouteTable(service, configAlwaysReal(),
                Logger.getLogger("test"));
        assertNull(table.lookup("fixture.Target", "ping", "()Ljava/lang/String;",
                new Object[0], null));

        // 热导入 enabled=false → RESOLVED（加载未启用）
        FixturePluginJar.build(plugins, "hot-1.0.0.jar", "hot", "1.0.0",
                "equipmock >=1.0.0", GOOD);
        writeRegistry(entry("hot", "hot-1.0.0.jar", false));
        service.onRegistryChanged();
        PluginStatus status = statusOf(service, "hot");
        assertEquals(PluginStatus.RESOLVED, status.state);
        assertEquals(1, status.mockPoints, "禁用插件同样注册 MockPoint（全量插桩，02 §6.2）");
        assertNull(table.lookup("fixture.Target", "ping", "()Ljava/lang/String;",
                new Object[0], null), "路由开关断开 → REAL");

        // enabled=true → STARTED，写死生效
        writeRegistry(entry("hot", "hot-1.0.0.jar", true));
        service.onRegistryChanged();
        assertEquals(PluginStatus.STARTED, statusOf(service, "hot").state);
        assertEquals("FIXED", table.lookup("fixture.Target", "ping",
                "()Ljava/lang/String;", new Object[0], null).value);

        // enabled=false（曾启动）→ DISABLED
        writeRegistry(entry("hot", "hot-1.0.0.jar", false));
        service.onRegistryChanged();
        assertEquals(PluginStatus.DISABLED, statusOf(service, "hot").state);
        assertNull(table.lookup("fixture.Target", "ping", "()Ljava/lang/String;",
                new Object[0], null), "D8：停用即路由断开");

        // 删除条目 → 卸载 + 路由移除
        writeRegistry();
        service.onRegistryChanged();
        assertNull(statusOf(service, "hot"), "卸载后 state.plugins 无该条目");
        assertEquals(0, service.routing().targetClasses().size());
        assertNull(table.lookup("fixture.Target", "ping", "()Ljava/lang/String;",
                new Object[0], null), "路由移除 → 调用自然 REAL");
    }

    @Test
    void brokenRegistryAtRuntimeKeepsOldState() throws Exception {
        Path plugins = home.root().resolve("plugins");
        FixturePluginJar.build(plugins, "keep-1.0.0.jar", "keep", "1.0.0",
                "equipmock >=1.0.0", GOOD);
        writeRegistry(entry("keep", "keep-1.0.0.jar", true));
        service.start();
        assertEquals(PluginStatus.STARTED, statusOf(service, "keep").state);

        Files.write(plugins.resolve("plugin-registry.json"),
                "{ BROKEN !!!".getBytes(StandardCharsets.UTF_8));
        service.onRegistryChanged();
        assertEquals(PluginStatus.STARTED, statusOf(service, "keep").state,
                "04 §7：解析失败保持旧内存状态");
        assertEquals(1, service.routing().targetClasses().size());
        assertTrue(errors.size() >= 1);
    }

    @Test
    void idempotentRegistryChangeDoesNotRewriteState() throws Exception {
        Path plugins = home.root().resolve("plugins");
        FixturePluginJar.build(plugins, "idem-1.0.0.jar", "idem", "1.0.0",
                "equipmock >=1.0.0", GOOD);
        writeRegistry(entry("idem", "idem-1.0.0.jar", true));
        service.start();
        int writes = stateWrites.get();
        service.onRegistryChanged(); // 内容未变
        assertEquals(writes, stateWrites.get(), "04 §8：内容未变不重写 state");
    }

    @Test
    void needsRestartStartsEmptyWithoutInstrumentation() throws Exception {
        service.start();
        assertTrue(service.needsRestart().isEmpty());
    }
}
