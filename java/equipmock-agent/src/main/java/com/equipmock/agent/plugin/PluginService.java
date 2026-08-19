package com.equipmock.agent.plugin;

import com.equipmock.agent.AgentHome;
import com.equipmock.agent.config.ConfigSchemaException;
import com.equipmock.api.MockHandler;
import com.equipmock.api.MockInterceptor;
import org.pf4j.DefaultPluginManager;
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.PluginWrapper;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * 插件装载服务（02 §6）：plugin-registry.json 清单驱动的 PF4J 生命周期管理 +
 * MockPoint 注册 + 热导入/卸载 + state.plugins[] 视图。
 *
 * <ul>
 *   <li><b>清单驱动</b>：只加载清单登记的 jar（不调用 pf4j 的 loadPlugins 全目录扫描，
 *       而是按清单逐个 loadPlugin）；清单有而 jar 缺 → MISSING 不中断；registry 损坏
 *       → 启动时全部不加载 + lastError，运行期保持旧状态 + lastError（04 §7）。</li>
 *   <li><b>版本硬校验</b>（D19）：manifest Plugin-Requires 经 {@link PluginRequires}
 *       与平台版本比对，不满足 → 拒载（REJECTED）。pf4j 内置的 requires→pf4j 版本校验
 *       被 {@code isPluginValid} 覆写短路（平台语义优先）。</li>
 *   <li><b>启停=路由开关</b>（D8）：registry.enabled 变化只翻 MockPoint.pluginEnabled
 *       与回写 state，无字节码操作；启动即停用的插件仍 loadPlugin（全量插桩，
 *       02 §6.2），state=RESOLVED。</li>
 *   <li><b>热导入</b>（D9）：新增条目 load+start+注册 MockPoint 后，对路由覆盖变化的
 *       已加载类 inst.retransformClasses 补齐（复用已注册的动态 matcher transformer）；
 *       失败类进 state.needsRestart 并记日志。删除条目 stop+unload+路由移除
 *       （字节码不回滚，调用自然 REAL）。</li>
 * </ul>
 */
public final class PluginService implements PluginRouter {

    /** 运行期错误上报（复用 ConfigCenter.reportRuntimeError → state.lastError） */
    public interface ErrorReporter {
        void report(String file, String message);
    }

    private static final String REGISTRY_DISPLAY = "plugins/plugin-registry.json";

    private final AgentHome home;
    private final Logger log;
    private final String platformVersion;
    private final Runnable stateRefresher;
    private final ErrorReporter errorReporter;

    private final Path pluginsDir;
    private final Path registryFile;

    /** id → 注册序 MockPoint（含禁用路由点） */
    private final Map<String, List<MockPoint>> pointsByPlugin =
            new LinkedHashMap<String, List<MockPoint>>();
    /** id → state 视图（清单序） */
    private final Map<String, PluginStatus> statuses =
            new LinkedHashMap<String, PluginStatus>();
    /** id → 是否曾被 start（DISABLED/RESOLVED 状态区分用） */
    private final Map<String, Boolean> startedOnce = new LinkedHashMap<String, Boolean>();
    /** retransform 失败的类（state.needsRestart 数据源） */
    private final Set<String> needsRestartClasses = new LinkedHashSet<String>();

    private volatile PluginRouting routing = PluginRouting.EMPTY;
    private DefaultPluginManager pluginManager;
    private PluginRegistry currentRegistry = PluginRegistry.empty();
    private Instrumentation instrumentation;

    public PluginService(AgentHome home, Logger log, String platformVersion,
                         Runnable stateRefresher, ErrorReporter errorReporter) {
        this.home = home;
        this.log = log;
        this.platformVersion = platformVersion;
        this.stateRefresher = stateRefresher;
        this.errorReporter = errorReporter;
        this.pluginsDir = home.root().resolve("plugins");
        this.registryFile = pluginsDir.resolve("plugin-registry.json");
    }

    /** retransform 用 Instrumentation（premain 注入；null=不可用，仅记日志） */
    public void setInstrumentation(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    // ------------------------------------------------------------------
    // 启动与变更入口（02 §3 第 5 步 / 04 §7）
    // ------------------------------------------------------------------

    /** 首次装载：registry 损坏 → 全部插件不加载 + lastError（不中断启动） */
    public synchronized void start() {
        PluginRegistry registry;
        try {
            registry = PluginRegistry.parse(registryFile, REGISTRY_DISPLAY);
        } catch (ConfigSchemaException e) {
            errorReporter.report(REGISTRY_DISPLAY,
                    "插件清单解析失败，本次不加载任何插件: " + e.getMessage());
            log.warning("plugin-registry.json 损坏，跳过插件装载: " + e.getMessage());
            currentRegistry = PluginRegistry.empty();
            return;
        }
        log.info("plugin service starting: " + registry.size() + " entr(y/ies)");
        applyRegistry(registry, false);
    }

    /** plugin-registry.json 变化（FileWatcher 回调）：diff 增/删/启停（04 §7） */
    public synchronized void onRegistryChanged() {
        PluginRegistry next;
        try {
            next = PluginRegistry.parse(registryFile, REGISTRY_DISPLAY);
        } catch (ConfigSchemaException e) {
            // 04 §7「任意解析失败」：保留旧内存状态 + lastError
            errorReporter.report(REGISTRY_DISPLAY,
                    "插件清单解析失败，保持已加载状态: " + e.getMessage());
            log.warning("plugin-registry.json 解析失败，保持旧状态: " + e.getMessage());
            return;
        }
        applyRegistry(next, true);
    }

    // ------------------------------------------------------------------
    // PluginRouter / state 视图
    // ------------------------------------------------------------------

    @Override
    public PluginRouting routing() {
        return routing;
    }

    /** state.plugins[] 数据源（清单序快照） */
    public synchronized List<PluginStatus> statuses() {
        return new ArrayList<PluginStatus>(statuses.values());
    }

    /** state.needsRestart 数据源 */
    public synchronized Set<String> needsRestart() {
        return new LinkedHashSet<String>(needsRestartClasses);
    }

    // ------------------------------------------------------------------
    // 内部：diff 应用
    // ------------------------------------------------------------------

    private void applyRegistry(PluginRegistry next, boolean hot) {
        PluginRegistry.Diff diff = next.diffFrom(currentRegistry);
        if (diff.isEmpty()) {
            return; // 04 §8 幂等：内容未变不重载、不重写 state
        }
        log.info("plugin registry changed" + (hot ? " (hot)" : "") + ": " + diff);
        for (PluginRegistry.Entry e : diff.removed) {
            unload(e.id);
        }
        for (PluginRegistry.Entry e : diff.added) {
            load(e);
        }
        for (PluginRegistry.Entry e : diff.enabledChanged) {
            toggle(e);
        }
        PluginRouting before = routing;
        rebuildRouting();
        currentRegistry = next;
        if (hot) {
            retransformCoverageChanged(before, routing);
        }
        stateRefresher.run();
    }

    /** 清单条目装载：MISSING → 版本校验 → pf4j load → 扫描 MockPoint → start */
    private void load(PluginRegistry.Entry entry) {
        Path jar = pluginsDir.resolve(entry.jar);
        if (!Files.isRegularFile(jar)) {
            putStatus(new PluginStatus(entry.id, null, PluginStatus.MISSING, 0,
                    "jar not found: plugins/" + entry.jar));
            log.warning("plugin '" + entry.id + "' jar missing: " + jar);
            return;
        }
        ManifestInfo mf = readManifest(jar);
        if (mf == null) {
            putStatus(new PluginStatus(entry.id, null, PluginStatus.FAILED, 0,
                    "manifest 不可读或缺 Plugin-Id/Plugin-Version: plugins/" + entry.jar));
            return;
        }
        if (!entry.id.equals(mf.id)) {
            putStatus(new PluginStatus(entry.id, mf.version, PluginStatus.FAILED, 0,
                    "registry id '" + entry.id + "' 与 manifest Plugin-Id '"
                            + mf.id + "' 不一致（04 §4）"));
            return;
        }
        PluginRequires.Result check = PluginRequires.check(mf.requires, platformVersion);
        if (!check.satisfied) {
            putStatus(new PluginStatus(entry.id, mf.version, PluginStatus.REJECTED, 0,
                    check.message));
            log.warning("plugin '" + entry.id + "' rejected: " + check.message);
            return;
        }
        try {
            ensureManager();
            String loadedId = pluginManager.loadPlugin(jar);
            // pf4j AbstractExtensionFinder.find(type, pluginId) 要求插件 STARTED，
            // 故先 start 再扫描扩展；registry.enabled 仅作为 D8 路由开关
            // （启动即停用的插件同样全量注册 MockPoint，02 §6.2）
            pluginManager.startPlugin(loadedId);
            startedOnce.put(loadedId, Boolean.TRUE);
            List<MockPoint> points = new ArrayList<MockPoint>();
            String error = scanPoints(loadedId, entry.enabled, points);
            if (error != null) {
                safeStop(loadedId);
                unloadQuietly(loadedId);
                putStatus(new PluginStatus(entry.id, mf.version, PluginStatus.FAILED, 0,
                        error));
                log.warning("plugin '" + entry.id + "' scan failed: " + error);
                return;
            }
            pointsByPlugin.put(loadedId, points);
            if (entry.enabled) {
                putStatus(new PluginStatus(entry.id, mf.version, PluginStatus.STARTED,
                        points.size(), null));
                log.info("plugin started: " + loadedId + "@" + mf.version + " ("
                        + points.size() + " mock point(s))");
            } else {
                // 02 §6.2：enabled=false 已加载未启用（路由开关断开）
                putStatus(new PluginStatus(entry.id, mf.version, PluginStatus.RESOLVED,
                        points.size(), null));
                log.info("plugin loaded (disabled): " + loadedId + "@" + mf.version
                        + " (" + points.size() + " mock point(s))");
            }
        } catch (Throwable t) {
            log.warning("plugin '" + entry.id + "' load failed: " + t);
            putStatus(new PluginStatus(entry.id, mf.version, PluginStatus.FAILED, 0,
                    "load/start failed: " + t));
        }
    }

    /** @MockInterceptor 扫描（02 §6.3）：注解缺失/空 targetClasses → 插件 FAILED */
    private String scanPoints(String pluginId, boolean enabled, List<MockPoint> out) {
        List<MockHandler> extensions;
        try {
            extensions = pluginManager.getExtensions(MockHandler.class, pluginId);
        } catch (Throwable t) {
            return "getExtensions(MockHandler) failed: " + t;
        }
        if (extensions.isEmpty()) {
            return "未发现 MockHandler 扩展（需要 @Extension + META-INF/extensions.idx，"
                    + "05 §1）";
        }
        for (MockHandler handler : extensions) {
            MockInterceptor anno = handler.getClass().getAnnotation(MockInterceptor.class);
            if (anno == null) {
                return handler.getClass().getName()
                        + " 缺少 @MockInterceptor 注解（05 §3）";
            }
            if (anno.targetClasses().length == 0) {
                return handler.getClass().getName()
                        + " 的 @MockInterceptor.targetClasses 为空（05 §3）";
            }
            if (anno.methods().length == 0) {
                return handler.getClass().getName()
                        + " 的 @MockInterceptor.methods 为空（05 §3）";
            }
            Set<String> methods =
                    new LinkedHashSet<String>(Arrays.asList(anno.methods()));
            for (String className : anno.targetClasses()) {
                out.add(new MockPoint(pluginId, className, methods, enabled, handler));
            }
        }
        return null; // 成功
    }

    /** 卸载：stop + unload + 路由移除（字节码不回滚，调用自然 REAL，02 §6.2） */
    private void unload(String pluginId) {
        pointsByPlugin.remove(pluginId);
        startedOnce.remove(pluginId);
        statuses.remove(pluginId);
        unloadQuietly(pluginId);
        log.info("plugin unloaded: " + pluginId);
    }

    /** pf4j stop+unload（尽力而为，失败仅记日志） */
    private void unloadQuietly(String pluginId) {
        if (pluginManager != null && pluginManager.getPlugin(pluginId) != null) {
            safeStop(pluginId);
            try {
                pluginManager.unloadPlugin(pluginId);
            } catch (Throwable t) {
                log.warning("unloadPlugin failed for '" + pluginId + "': " + t);
            }
        }
    }

    /** 启停开关（D8）：翻 MockPoint.pluginEnabled，无字节码操作 */
    private void toggle(PluginRegistry.Entry entry) {
        List<MockPoint> points = pointsByPlugin.get(entry.id);
        if (points == null) {
            return; // MISSING/REJECTED/FAILED：无路由点，仅记录标志
        }
        for (MockPoint point : points) {
            point.setPluginEnabled(entry.enabled);
        }
        if (entry.enabled && pluginManager != null
                && pluginManager.getPlugin(entry.id) != null) {
            try {
                pluginManager.startPlugin(entry.id); // 幂等（已 STARTED 时无操作）
            } catch (Throwable t) {
                log.warning("startPlugin on enable failed for '" + entry.id + "': " + t);
                for (MockPoint point : points) {
                    point.setPluginEnabled(false);
                }
                putStatus(entry.id, currentVersion(entry.id), PluginStatus.FAILED,
                        points.size(), "enable failed: " + t);
                return;
            }
        }
        // 启动即停用=RESOLVED；运行中由启用改停用=DISABLED（pf4j 侧保持 STARTED，
        // 仅路由开关断开——D8 明确启停无字节码操作）
        String state = entry.enabled ? PluginStatus.STARTED : PluginStatus.DISABLED;
        putStatus(entry.id, currentVersion(entry.id), state, points.size(), null);
        log.info("plugin '" + entry.id + "' routing " + (entry.enabled
                ? "enabled" : "disabled") + " (state=" + state + ")");
    }

    // ------------------------------------------------------------------
    // retransform 补齐（D9）
    // ------------------------------------------------------------------

    /** 路由覆盖（类集合/方法集合）变化 → 对已加载类 retransform 补插桩 */
    private void retransformCoverageChanged(PluginRouting before, PluginRouting after) {
        if (instrumentation == null) {
            return;
        }
        Set<String> changed = new LinkedHashSet<String>();
        for (String className : after.targetClasses()) {
            if (!before.targetClasses().contains(className)
                    || !after.methodNames(className).equals(before.methodNames(className))) {
                changed.add(className);
            }
        }
        for (String className : changed) {
            retransform(className);
        }
    }

    private void retransform(String className) {
        if (instrumentation == null) {
            return;
        }
        List<Class<?>> targets = new ArrayList<Class<?>>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (className.equals(loaded.getName())) {
                targets.add(loaded);
            }
        }
        if (targets.isEmpty()) {
            log.info("plugin target " + className
                    + " not loaded yet; dynamic matcher instruments on first load");
            return;
        }
        try {
            instrumentation.retransformClasses(targets.toArray(new Class<?>[0]));
            needsRestartClasses.remove(className);
            log.info("retransformed plugin target " + className);
        } catch (Throwable t) {
            needsRestartClasses.add(className);
            log.warning("retransform failed for " + className
                    + " (recorded in state.needsRestart): " + t);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void rebuildRouting() {
        List<MockPoint> all = new ArrayList<MockPoint>();
        for (List<MockPoint> points : pointsByPlugin.values()) {
            all.addAll(points);
        }
        routing = PluginRouting.build(all);
    }

    private void ensureManager() {
        if (pluginManager == null) {
            pluginManager = new DefaultPluginManager(pluginsDir) {
                @Override
                protected PluginDescriptorFinder createPluginDescriptorFinder() {
                    return new ManifestPluginDescriptorFinder();
                }

                @Override
                protected boolean isPluginValid(PluginWrapper wrapper) {
                    // Plugin-Requires 由 PluginService 以平台版本硬校验（D19）；
                    // 短路 pf4j 内置的 requires→pf4j 版本校验
                    return true;
                }
            };
        }
    }

    private void safeStop(String pluginId) {
        try {
            pluginManager.stopPlugin(pluginId);
        } catch (Throwable t) {
            log.fine("stopPlugin no-op/failed for '" + pluginId + "': " + t);
        }
    }

    private void putStatus(PluginStatus status) {
        statuses.put(status.id, status);
    }

    private void putStatus(String id, String version, String state, int points,
                           String error) {
        statuses.put(id, new PluginStatus(id, version, state, points, error));
    }

    private String currentVersion(String pluginId) {
        if (pluginManager != null) {
            PluginWrapper wrapper = pluginManager.getPlugin(pluginId);
            if (wrapper != null) {
                return wrapper.getDescriptor().getVersion();
            }
        }
        return null;
    }

    /** jar manifest 三字段读取；不可读/缺 Plugin-Id/Plugin-Version → null */
    private ManifestInfo readManifest(Path jar) {
        JarFile file = null;
        try {
            file = new JarFile(jar.toFile());
            Attributes attrs = file.getManifest() == null
                    ? null : file.getManifest().getMainAttributes();
            if (attrs == null) {
                return null;
            }
            String id = attrs.getValue("Plugin-Id");
            String version = attrs.getValue("Plugin-Version");
            if (id == null || id.trim().isEmpty() || version == null
                    || version.trim().isEmpty()) {
                return null;
            }
            return new ManifestInfo(id.trim(), version.trim(),
                    trimToNull(attrs.getValue("Plugin-Requires")));
        } catch (IOException e) {
            log.warning("manifest read failed for " + jar + ": " + e);
            return null;
        } finally {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException ignored) {
                    // 关闭失败可忽略
                }
            }
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** manifest 关键字段 */
    private static final class ManifestInfo {
        final String id;
        final String version;
        final String requires;

        ManifestInfo(String id, String version, String requires) {
            this.id = id;
            this.version = version;
            this.requires = requires;
        }
    }
}
