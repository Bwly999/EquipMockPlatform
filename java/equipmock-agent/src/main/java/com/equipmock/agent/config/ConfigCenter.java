package com.equipmock.agent.config;

import com.equipmock.agent.AgentHome;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

/**
 * 配置中心（03 §1/§2/§3）：settings + 活动组快照的加载、原子替换与切组。
 *
 * <p>内存模型：两个 volatile 单引用（SettingsSnapshot / GroupSnapshot），
 * 查询路径无锁；所有变更入口 synchronized 串行化（监听线程单线程调用）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>组级原子性：任一文件解析失败 → 整组保持旧快照（首启失败=空组全部 REAL），
 *       state.lastError 记录 file/message/time；成功的文件也不生效。</li>
 *   <li>切组：settings.activeGroup 变化 → 按启动流程加载新组，<b>成功才替换</b>
 *       settings+active 并回写 state；失败保持旧组+旧 settings + lastError。</li>
 *   <li>mockEnabled 变化 → 回调 {@link #globalEnabledCallback}（AgentSpyHandler.setGlobalEnabled）。</li>
 *   <li>幂等：settings 未变 / 新快照内容与旧快照 equals → 不替换、不重写 state（04 §8）。</li>
 *   <li>非活动组文件变化：仅保存不加载（04 §7）。</li>
 * </ul>
 */
public final class ConfigCenter {

    private final AgentHome home;
    private final Logger log;
    private final StateSink stateSink;

    private volatile SettingsSnapshot settings = SettingsSnapshot.DEFAULTS;
    private volatile GroupSnapshot active = null;
    private volatile JsonObject lastError = null;

    /** mockEnabled 变化回调（AgentSpyHandler::setGlobalEnabled）；premain 注入 */
    private volatile Consumer<Boolean> globalEnabledCallback;
    /** 快照替换回调（新增目标类的已加载提示）；AgentPremain 注入 */
    private volatile GroupReloadListener reloadListener;
    /** instrumentedClasses 数据源（RouteTable::targetClassCount）；AgentPremain 注入 */
    private volatile IntSupplier instrumentedClassCount = new IntSupplier() {
        @Override
        public int getAsInt() {
            return 0;
        }
    };

    /** 快照成功替换通知（TargetClassChangeMonitor 用） */
    public interface GroupReloadListener {
        void onActiveGroupReplaced(GroupSnapshot newSnapshot);
    }

    public ConfigCenter(AgentHome home, Logger log, StateSink stateSink) {
        this.home = home;
        this.log = log;
        this.stateSink = stateSink;
    }

    // ------------------------------------------------------------------
    // 启动加载（03 §2）
    // ------------------------------------------------------------------

    /**
     * 首次加载：settings（损坏→日志+lastError+默认值）→ 活动组（失败→空组+lastError）。
     * 不写 state（由 AgentPremain 在插桩注册后统一写）。
     */
    public synchronized void start() {
        settings = loadSettingsOrDefault();
        active = loadGroupOrEmpty(settings.activeGroup);
        log.info("config center started: activeGroup=" + active.groupName()
                + ", files=" + active.entryCounts());
    }

    // ------------------------------------------------------------------
    // 查询（热路径，volatile 读）
    // ------------------------------------------------------------------

    public SettingsSnapshot settings() {
        return settings;
    }

    public GroupSnapshot activeGroup() {
        GroupSnapshot snapshot = active;
        return snapshot == null ? GroupSnapshot.empty(settings.activeGroup) : snapshot;
    }

    public JsonObject lastError() {
        return lastError;
    }

    // ------------------------------------------------------------------
    // FileWatcher 回调（04 §7 变更语义总表）
    // ------------------------------------------------------------------

    /** settings.json 变化：切组（成功才替换）/ mockEnabled 开关（03 §3） */
    public synchronized void onSettingsFileChanged() {
        SettingsSnapshot parsed;
        try {
            parsed = loadSettings();
        } catch (ConfigSchemaException e) {
            recordError(e.file, e.getMessage());
            log.warning("settings.json 解析失败，保持旧配置: " + e.getMessage());
            return;
        }
        if (parsed.equals(settings)) {
            return; // 幂等：内容未变
        }
        boolean groupChanged = !parsed.activeGroup.equals(settings.activeGroup);
        if (groupChanged) {
            GroupSnapshot next;
            try {
                next = loadGroup(parsed.activeGroup);
            } catch (ConfigSchemaException e) {
                recordError(e.file, e.getMessage());
                log.warning("切组失败，保持旧组 " + settings.activeGroup + ": "
                        + e.getMessage());
                return; // settings 也保持旧值（组与开关是 settings 的原子整体）
            }
            boolean enabledChanged = parsed.mockEnabled != settings.mockEnabled;
            settings = parsed;
            active = next;
            if (enabledChanged && globalEnabledCallback != null) {
                globalEnabledCallback.accept(Boolean.valueOf(parsed.mockEnabled));
            }
            log.info("switched active group to '" + next.groupName() + "' (files="
                    + next.entryCounts() + ")");
            notifyListener(next);
            writeState();
            return;
        }
        // 仅 mockEnabled 变化：即时生效（04 §7）
        settings = parsed;
        if (globalEnabledCallback != null) {
            globalEnabledCallback.accept(Boolean.valueOf(parsed.mockEnabled));
        }
        log.info("mockEnabled -> " + parsed.mockEnabled);
        writeState();
    }

    /** 组内文件变化：活动组重建整组快照；非活动组仅保存不加载（04 §7） */
    public synchronized void onGroupFileChanged(String groupName) {
        if (active == null || !groupName.equals(settings.activeGroup)) {
            log.fine("non-active group '" + groupName + "' changed; saved without loading");
            return;
        }
        GroupSnapshot next;
        try {
            next = loadGroup(groupName);
        } catch (ConfigSchemaException e) {
            recordError(e.file, e.getMessage());
            log.warning("组 '" + groupName + "' 重载失败，整组保持旧快照: " + e.getMessage());
            return;
        }
        if (next.equals(active)) {
            return; // 幂等：快照内容未变不替换、不重写 state
        }
        active = next;
        log.info("group '" + groupName + "' reloaded (files=" + next.entryCounts() + ")");
        notifyListener(next);
        writeState();
    }

    /** 运行期错误上报（匹配引擎：转换失败/签名错配/THROW 失败）；同消息去重防热路径 IO */
    public synchronized void reportRuntimeError(String file, String message) {
        JsonObject current = lastError;
        if (current != null && message.equals(current.get("message").getAsString())) {
            return;
        }
        recordError(file, message);
    }

    // ------------------------------------------------------------------
    // 注入点（AgentPremain 装配）
    // ------------------------------------------------------------------

    public void setGlobalEnabledCallback(Consumer<Boolean> callback) {
        this.globalEnabledCallback = callback;
    }

    public void setGroupReloadListener(GroupReloadListener listener) {
        this.reloadListener = listener;
    }

    public void setInstrumentedClassCount(IntSupplier supplier) {
        this.instrumentedClassCount = supplier;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void notifyListener(GroupSnapshot snapshot) {
        GroupReloadListener listener = this.reloadListener;
        if (listener != null) {
            try {
                listener.onActiveGroupReplaced(snapshot);
            } catch (Throwable t) {
                log.warning("group reload listener failed: " + t);
            }
        }
    }

    private SettingsSnapshot loadSettingsOrDefault() {
        try {
            SettingsSnapshot parsed = loadSettings();
            log.info("settings loaded: " + parsed);
            return parsed;
        } catch (ConfigSchemaException e) {
            recordError(e.file, e.getMessage());
            log.warning("settings.json 损坏，按默认值运行 {activeGroup:default, "
                    + "mockEnabled:true}: " + e.getMessage());
            return SettingsSnapshot.DEFAULTS;
        }
    }

    /** 解析 settings.json（04 §2：activeGroup+mockEnabled 必填，目录名格式校验） */
    private SettingsSnapshot loadSettings() {
        Path file = home.settingsFile();
        String display = "settings.json";
        JsonObject root = ConfigFiles.parseObject(file, display);
        if (!root.has("activeGroup") || !root.get("activeGroup").isJsonPrimitive()
                || !root.get("activeGroup").getAsJsonPrimitive().isString()) {
            throw ConfigSchemaException.field(display, "activeGroup",
                    "必填且必须为字符串（目录名）");
        }
        String group = root.get("activeGroup").getAsString();
        if (!group.matches(GroupConfigParser.GROUP_DIR_PATTERN)) {
            throw ConfigSchemaException.field(display, "activeGroup",
                    "目录名仅允许 [A-Za-z0-9_-]{1,64}: '" + group + "'");
        }
        if (!root.has("mockEnabled") || !root.get("mockEnabled").isJsonPrimitive()
                || !root.get("mockEnabled").getAsJsonPrimitive().isBoolean()) {
            throw ConfigSchemaException.field(display, "mockEnabled",
                    "必填且必须为 boolean");
        }
        return new SettingsSnapshot(group, root.get("mockEnabled").getAsBoolean());
    }

    private GroupSnapshot loadGroupOrEmpty(String groupName) {
        try {
            return loadGroup(groupName);
        } catch (ConfigSchemaException e) {
            recordError(e.file, e.getMessage());
            log.warning("组 '" + groupName + "' 首启加载失败，空组运行（全部 REAL）: "
                    + e.getMessage());
            return GroupSnapshot.empty(groupName);
        }
    }

    private GroupSnapshot loadGroup(String groupName) {
        Path groupsRoot = home.root().resolve("config").resolve("groups");
        Path groupDir = groupsRoot.resolve(groupName);
        String displayPrefix = "config/groups/" + groupName;
        return GroupConfigParser.parseGroup(groupDir, groupName, displayPrefix);
    }

    private void recordError(String file, String message) {
        lastError = com.equipmock.agent.StateWriter.errorObject(file, message);
        writeState();
    }

    private void writeState() {
        GroupSnapshot snapshot = activeGroup();
        stateSink.writeState(snapshot.groupName(), settings.mockEnabled,
                instrumentedClassCount.getAsInt(), snapshot.entryCounts(), lastError);
    }
}
