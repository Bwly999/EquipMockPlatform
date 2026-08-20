package com.equipmock.testkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 插件自测试基类（05 §4，M4-1）：测试 JVM 自身带 {@code -javaagent} 运行，
 * 测试代码直接调用目标类断言 Mock 生效——与真实运行方式一致。
 *
 * <p><b>挂载约定</b>（插件模块 pom 的 surefire/failsafe argLine 模板，可整体 {@code -D} 覆写）：
 * <pre>
 *   -javaagent:${equipmock.agent.jar}
 *   -Dequipmock.bootstrap.jar=${equipmock.bootstrap.jar}   // agent jar 同目录无 bootstrap 时由 agent 兜底加载
 *   -Dequipmock.home=${project.build.directory}/equipmock-test-home/f${surefire.forkNumber}
 * </pre>
 *
 * <ul>
 *   <li><b>home</b>：基类只解析 {@code -Dequipmock.home} 并向该目录写（与本 agent 实例对齐），
 *       不另造临时目录。failsafe 配 {@code forkCount=1, reuseForks=false} → 每个测试类独占一个
 *       JVM + 一个 {@code f&lt;forkNumber&gt;} home；模块 pom 另在 pre-integration-test 清理
 *       {@code target/equipmock-test-home}，保证每次构建从干净骨架开始（不在 JVM 内删目录——
 *       agent 正在 watch，先删后建会触发一次 lastError 假阳性）。</li>
 *   <li><b>agent 定位</b>：属性 {@code equipmock.agent.jar} &gt; 环境变量 {@code EQUIPMOCK_AGENT_JAR}
 *       &gt; 从 {@code user.dir} 向上最多三级探测 {@code equipmock-agent/target/equip-mock-agent.jar}
 *       （含 {@code java/} 前缀两种布局）。仅用于诊断信息与缺 agent 时给出可读报错；
 *       实际挂载由 argLine 完成。</li>
 *   <li><b>写协议</b>：settings/registry/小分组全部走 04 §5 原子写（tmp + ATOMIC_MOVE）。</li>
 *   <li><b>完成信号</b>：agent 每次重载/装载成功都重写 state.json（lastWriteAt 更新）——
 *       {@link #awaitConfigApplied} 等 lastWriteAt 越过写前基线且 lastError==null；
 *       {@link #awaitPluginStarted} 轮询 state.plugins[].state（premain 时 registry 尚不存在，
 *       热导入在测试中生效是既定语义，M3）。</li>
 * </ul>
 */
public abstract class EquipMockTestBase {

    /** 组/小分组目录名校验（04 §1：文件/目录名 [A-Za-z0-9_-]{1,64}） */
    private static final String GROUP_DIR_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .setPrettyPrinting().create();

    /** 本测试类对齐的 equip-mock home（来自 -Dequipmock.home） */
    protected static Path home;

    /** 定位到的 agent jar（诊断用；null=探测失败，实际挂载以 argLine 为准） */
    protected static Path agentJar;

    /** settings.json 内存镜像（本基类是测试 JVM 内 settings 的唯一写方） */
    private static String activeGroupMirror = "default";
    private static boolean mockEnabledMirror = true;

    /** 上一次变更写盘前的 state.lastWriteAt（awaitConfigApplied 的比对基线） */
    private static volatile String stateBaseline = "";

    /**
     * 自上次成功 awaitConfigApplied 以来是否有未确认变更。
     * agent 幂等语义（04 §8）：内容未变不重载、不重写 state——所以写帮助方法
     * 先做同内容判定，await 对幂等写立即返回，与 agent 行为精确对齐。
     */
    private static boolean pendingChange = false;

    /** 小分组文件（group/file）→ 上次写入内容（幂等判定） */
    private static final java.util.Map<String, String> lastSubGroupContent =
            new java.util.HashMap<String, String>();

    @BeforeAll
    protected static void initEquipMockTestKit() throws IOException {
        agentJar = locateAgentJar();
        home = resolveHome();
        seedSkeleton();
        System.out.println("[equipmock-testkit] home=" + home + ", agentJar=" + agentJar);
    }

    // ------------------------------------------------------------------
    // 路径与定位
    // ------------------------------------------------------------------

    /** -Dequipmock.home；缺省 user.dir/target/equipmock-test-home（IDE 直跑兜底） */
    protected static Path resolveHome() {
        String configured = System.getProperty("equipmock.home");
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir"), "target", "equipmock-test-home")
                .toAbsolutePath().normalize();
    }

    /**
     * agent jar 定位：属性 equipmock.agent.jar &gt; 环境变量 EQUIPMOCK_AGENT_JAR &gt;
     * user.dir 及其上三级目录内的 equipmock-agent/target/equip-mock-agent.jar
     * （含 java/ 前缀布局——覆盖从根/从 java/从插件模块发起构建的全部形态）。
     */
    protected static Path locateAgentJar() {
        String explicit = System.getProperty("equipmock.agent.jar");
        if (explicit != null && !explicit.trim().isEmpty()) {
            return Paths.get(explicit.trim()).toAbsolutePath().normalize();
        }
        String env = System.getenv("EQUIPMOCK_AGENT_JAR");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim()).toAbsolutePath().normalize();
        }
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path[] bases = {userDir, userDir.getParent(), parentOf(userDir.getParent()),
                parentOf(parentOf(userDir.getParent()))};
        String[] suffixes = {"java/equipmock-agent/target/equip-mock-agent.jar",
                "equipmock-agent/target/equip-mock-agent.jar"};
        for (Path base : bases) {
            if (base == null) {
                continue;
            }
            for (String suffix : suffixes) {
                Path candidate = base.resolve(suffix).normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Path parentOf(Path p) {
        return p == null ? null : p.getParent();
    }

    /** home 下常用路径 */
    protected static Path settingsFile() {
        return home.resolve("settings.json");
    }

    protected static Path pluginsDir() {
        return home.resolve("plugins");
    }

    protected static Path registryFile() {
        return pluginsDir().resolve("plugin-registry.json");
    }

    protected static Path stateFile() {
        return home.resolve("state.json");
    }

    /** 小分组文件路径（不落盘，仅定位） */
    protected static Path subGroupFile(String group, String file) {
        return home.resolve("config").resolve("groups").resolve(group).resolve(file);
    }

    // ------------------------------------------------------------------
    // 骨架（04 §1：create-if-absent，与 AgentHome.prepare 语义一致）
    // ------------------------------------------------------------------

    /** 建骨架（04 §1；create-if-absent，幂等）。无 agent 场景可重定向 home 后重建 */
    protected static void seedSkeleton() throws IOException {
        Files.createDirectories(subGroupDir("default"));
        Files.createDirectories(pluginsDir());
        Files.createDirectories(home.resolve("logs"));
        createFileIfAbsent(settingsFile(), settingsJson("default", true));
        createFileIfAbsent(registryFile(), "{\n  \"$schema\": \"equipmock/plugin-registry@1\",\n"
                + "  \"plugins\": []\n}\n");
    }

    private static Path subGroupDir(String group) {
        return home.resolve("config").resolve("groups").resolve(group);
    }

    private static void createFileIfAbsent(Path file, String content) throws IOException {
        if (!Files.exists(file)) {
            atomicWrite(file, content);
        }
    }

    private static String settingsJson(String group, boolean enabled) {
        return "{\n  \"$schema\": \"equipmock/settings@1\",\n"
                + "  \"activeGroup\": \"" + group + "\",\n"
                + "  \"mockEnabled\": " + enabled + "\n}\n";
    }

    // ------------------------------------------------------------------
    // 写方协议（04 §5 原子写）
    // ------------------------------------------------------------------

    /** 原子写：tmp-6位随机 + ATOMIC_MOVE（不持锁读方永远看到完整文件） */
    protected static void atomicWrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + randomSuffix());
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String randomSuffix() {
        return String.format("%06d", new SecureRandom().nextInt(1000000));
    }

    /** 写活动组（或任意组）内小分组 json（原子写；内容变化后用 awaitConfigApplied 等生效） */
    protected static void writeSubGroup(String group, String file, String json)
            throws IOException {
        if (!group.matches(GROUP_DIR_PATTERN)) {
            throw new IllegalArgumentException(
                    "group 目录名仅允许 [A-Za-z0-9_-]{1,64}: '" + group + "'（04 §1）");
        }
        String name = file.endsWith(".json") ? file : file + ".json";
        if (name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            throw new IllegalArgumentException("非法小分组文件名: '" + file + "'");
        }
        String key = group + "/" + name;
        boolean changed = !json.equals(lastSubGroupContent.get(key));
        if (changed) {
            markStateBaseline();
            pendingChange = true;
        }
        lastSubGroupContent.put(key, json);
        Files.createDirectories(subGroupDir(group));
        atomicWrite(subGroupFile(group, name), json);
    }

    /** settings.activeGroup 切组（保留当前 mockEnabled 镜像） */
    protected static void setActiveGroup(String group) throws IOException {
        if (!group.matches(GROUP_DIR_PATTERN)) {
            throw new IllegalArgumentException(
                    "group 目录名仅允许 [A-Za-z0-9_-]{1,64}: '" + group + "'（04 §1）");
        }
        if (!group.equals(activeGroupMirror)) {
            markStateBaseline();
            pendingChange = true;
            activeGroupMirror = group;
        }
        atomicWrite(settingsFile(), settingsJson(activeGroupMirror, mockEnabledMirror));
    }

    /** settings.mockEnabled 全局开关（false=全部放行真实调用） */
    protected static void enableMock(boolean on) throws IOException {
        if (on != mockEnabledMirror) {
            markStateBaseline();
            pendingChange = true;
            mockEnabledMirror = on;
        }
        atomicWrite(settingsFile(), settingsJson(activeGroupMirror, mockEnabledMirror));
    }

    /**
     * 登记插件：拷 jar 进 plugins/ + registry 追加/覆盖条目（enabled=true）。
     * 幂等：jar 已存在且大小一致则跳过拷贝（插件类加载器可能正持有该文件）。
     */
    protected static void registerPlugin(Path jarPath, String pluginId) throws IOException {
        if (!Files.isRegularFile(jarPath)) {
            throw new FileNotFoundException("插件 jar 不存在: " + jarPath);
        }
        markStateBaseline();
        Path dest = pluginsDir().resolve(jarPath.getFileName().toString());
        if (!Files.exists(dest) || Files.size(dest) != Files.size(jarPath)) {
            Files.copy(jarPath, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        writeRegistry(pluginId, jarPath.getFileName().toString(), true);
    }

    /** registry.enabled 翻转（D8 路由开关：停用后写死/配置语义让位于纯配置→REAL 由配置决定） */
    protected static void setPluginEnabled(String pluginId, boolean enabled)
            throws IOException {
        markStateBaseline();
        writeRegistry(pluginId, null, enabled);
    }

    /** registry 读-改-写：按 id 定位条目；jarPath=null 保持原 jar 字段 */
    private static void writeRegistry(String pluginId, String jarPath, boolean enabled)
            throws IOException {
        JsonObject registry;
        try (Reader reader = Files.newBufferedReader(registryFile(), StandardCharsets.UTF_8)) {
            registry = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Throwable t) {
            registry = new JsonObject();
            registry.addProperty("$schema", "equipmock/plugin-registry@1");
        }
        JsonArray plugins = registry.has("plugins") && registry.get("plugins").isJsonArray()
                ? registry.getAsJsonArray("plugins") : new JsonArray();
        JsonObject entry = null;
        for (JsonElement e : plugins) {
            if (e.isJsonObject() && pluginId.equals(stringOf(e.getAsJsonObject(), "id"))) {
                entry = e.getAsJsonObject();
                break;
            }
        }
        boolean isNew = entry == null;
        if (isNew) {
            if (jarPath == null) {
                throw new IllegalStateException(
                        "插件未登记，无法翻转 enabled: '" + pluginId + "'（先 registerPlugin）");
            }
            entry = new JsonObject();
            entry.addProperty("id", pluginId);
            entry.addProperty("alias", pluginId);
        }
        // agent 侧 diff 以 (id, jar, enabled) 为准（importedAt 展示用不参与）：
        // 条目实际未变则为幂等写，不推进 await 基线
        boolean changed = isNew
                || (jarPath != null && !jarPath.equals(stringOf(entry, "jar")))
                || !entry.has("enabled") || entry.get("enabled").getAsBoolean() != enabled;
        if (changed) {
            markStateBaseline();
            pendingChange = true;
        }
        if (jarPath != null) {
            entry.addProperty("jar", jarPath);
        }
        entry.addProperty("enabled", enabled);
        entry.addProperty("importedAt", OffsetDateTime.now().format(TIME));
        if (isNew) {
            plugins.add(entry);
        }
        registry.add("plugins", plugins);
        atomicWrite(registryFile(), GSON.toJson(registry) + "\n");
    }

    // ------------------------------------------------------------------
    // 等待 agent（轮询 state.json）
    // ------------------------------------------------------------------

    /** 等待 agent 起来（state.json 出现）；IT 的第一步调用，缺 -javaagent 时给出可读失败 */
    protected static void awaitAgentRunning(long timeoutMs) {
        awaitCondition(timeoutMs, "agent 未挂载或 state.json 未生成（检查 -javaagent/"
                + "equipmock.home；探测 agentJar=" + agentJar + "）", new Condition() {
            @Override
            public boolean satisfied() {
                return readStateJson() != null;
            }
        });
    }

    /** 等上一次基线之后的成功重载：lastWriteAt 变化且 lastError==null；幂等写立即返回 */
    protected static void awaitConfigApplied(long timeoutMs) {
        if (!pendingChange) {
            return; // 内容与上次已确认一致：agent 不重载不重写 state（04 §8 幂等）
        }
        awaitCondition(timeoutMs, "配置变更未生效（基线 lastWriteAt=" + stateBaseline + "）",
                new Condition() {
                    @Override
                    public boolean satisfied() {
                        JsonObject state = readStateJson();
                        if (state == null) {
                            return false;
                        }
                        String lastWriteAt = stringOf(state, "lastWriteAt");
                        return lastWriteAt != null && !lastWriteAt.equals(stateBaseline)
                                && state.get("lastError").isJsonNull();
                    }
                });
        pendingChange = false;
    }

    /** 等插件 state==STARTED（热导入完成信号） */
    protected static void awaitPluginStarted(String pluginId, long timeoutMs) {
        awaitPluginState(pluginId, "STARTED", timeoutMs);
    }

    /** 等插件进入指定状态（STARTED/RESOLVED/DISABLED/MISSING/REJECTED/FAILED） */
    protected static void awaitPluginState(final String pluginId, final String expected,
                                           long timeoutMs) {
        awaitCondition(timeoutMs, "state.plugins['" + pluginId + "'] 未达到 " + expected,
                new Condition() {
                    @Override
                    public boolean satisfied() {
                        return expected.equals(pluginState(pluginId));
                    }
                });
    }

    /** 当前 state.plugins[id].state；无 state/条目返回 null */
    protected static String pluginState(String pluginId) {
        JsonObject state = readStateJson();
        if (state == null || !state.has("plugins") || !state.get("plugins").isJsonArray()) {
            return null;
        }
        for (JsonElement e : state.getAsJsonArray("plugins")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject p = e.getAsJsonObject();
            if (pluginId.equals(stringOf(p, "id"))) {
                return stringOf(p, "state");
            }
        }
        return null;
    }

    /** state.plugins[id].error（诊断用；无则 null） */
    protected static String pluginError(String pluginId) {
        JsonObject state = readStateJson();
        if (state == null || !state.has("plugins") || !state.get("plugins").isJsonArray()) {
            return null;
        }
        for (JsonElement e : state.getAsJsonArray("plugins")) {
            if (e.isJsonObject()
                    && pluginId.equals(stringOf(e.getAsJsonObject(), "id"))) {
                return stringOf(e.getAsJsonObject(), "error");
            }
        }
        return null;
    }

    /** 变更写盘前快照 lastWriteAt（各写帮助方法自动调用） */
    private static void markStateBaseline() {
        JsonObject state = readStateJson();
        stateBaseline = state == null ? "" : stringOf(state, "lastWriteAt");
        if (stateBaseline == null) {
            stateBaseline = "";
        }
    }

    /** state.json 读取（原子写读端：瞬时不可读/未生成返回 null 由调用方重试） */
    protected static JsonObject readStateJson() {
        try (Reader reader = Files.newBufferedReader(stateFile(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stringOf(JsonObject obj, String member) {
        return obj.has(member) && obj.get(member).isJsonPrimitive()
                ? obj.get(member).getAsString() : null;
    }

    private interface Condition {
        boolean satisfied();
    }

    private static void awaitCondition(long timeoutMs, String message, Condition condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Throwable lastDiagnostic = null;
        while (System.currentTimeMillis() < deadline) {
            if (condition.satisfied()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断: " + message, e);
            }
        }
        // 超时：附 state.json 当前视图辅助定位
        JsonObject state = readStateJson();
        String view = state == null ? "(state.json 不可读/未生成)"
                : "lastError=" + state.get("lastError");
        throw new AssertionError("等待超时(" + timeoutMs + "ms): " + message + "; " + view);
    }

    // ------------------------------------------------------------------
    // 断言便捷（按需使用）
    // ------------------------------------------------------------------

    /** 断言命中 Mock 值 */
    protected static void assertMocked(String what, Object expected, Object actual) {
        assertEquals(expected, actual, "[" + what + "] 应命中 Mock 值");
    }

    /** 断言命中 Mock 值（浮点，delta=1e-9） */
    protected static void assertMocked(String what, double expected, double actual) {
        assertEquals(expected, actual, 1e-9, "[" + what + "] 应命中 Mock 值");
    }

    /** 断言回到真实值 */
    protected static void assertReal(String what, Object expected, Object actual) {
        assertEquals(expected, actual, "[" + what + "] 应回到真实值");
    }

    /** 断言回到真实值（浮点，delta=1e-9） */
    protected static void assertReal(String what, double expected, double actual) {
        assertEquals(expected, actual, 1e-9, "[" + what + "] 应回到真实值");
    }
}
