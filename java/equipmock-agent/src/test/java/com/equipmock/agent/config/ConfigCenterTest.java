package com.equipmock.agent.config;

import com.equipmock.agent.AgentHome;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigCenter 单测（03 §2/§3 + 04 §7/§8）：组级原子性、切组成败语义、
 * mockEnabled 即时回调、非活动组忽略、幂等（未变不重写 state）、lastError 结构。
 */
class ConfigCenterTest {

    @TempDir
    Path tempDir;

    private AgentHome home;
    private RecordingSink sink;
    private ConfigCenter center;

    /** 计数桩：记录每次 writeState 的入参 */
    static final class RecordingSink implements StateSink {
        int writes;
        String activeGroup;
        boolean mockEnabled;
        int instrumentedClasses;
        Map<String, Integer> counts;
        JsonObject lastError;

        @Override
        public void writeState(String activeGroup, boolean mockEnabled,
                               int instrumentedClasses,
                               Map<String, Integer> groupFileEntryCounts,
                               JsonObject lastError) {
            writes++;
            this.activeGroup = activeGroup;
            this.mockEnabled = mockEnabled;
            this.instrumentedClasses = instrumentedClasses;
            this.counts = groupFileEntryCounts;
            this.lastError = lastError;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        home = AgentHome.prepare(tempDir.resolve("home").toString());
        sink = new RecordingSink();
        center = new ConfigCenter(home, Logger.getLogger("test"), sink);
    }

    private void writeGroup(String group, String file, String json) throws Exception {
        Path dir = home.root().resolve("config/groups").resolve(group);
        Files.createDirectories(dir);
        atomicWrite(dir.resolve(file + ".json"), json);
    }

    private void atomicWrite(Path target, String content) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-"
                + (int) (Math.random() * 1000000));
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static final String MOCK_5 = "{\"mocks\":[{\"class\":\"a.B\",\"method\":\"m\","
            + "\"enabled\":true,\"rules\":[],"
            + "\"defaultAction\":{\"type\":\"VALUE\",\"value\":5}}]}";

    @Test
    void startLoadsSettingsAndGroup() throws Exception {
        writeGroup("default", "main", MOCK_5);
        center.start();
        assertEquals("default", center.settings().activeGroup);
        assertTrue(center.settings().mockEnabled);
        assertEquals(1, center.activeGroup().files().size());
        assertEquals(Integer.valueOf(1),
                center.activeGroup().entryCounts().get("main"));
        assertTrue(center.activeGroup().index().targetClasses().contains("a.B"));
    }

    @Test
    void firstStartWithBrokenGroupRunsEmptyAllReal() throws Exception {
        writeGroup("default", "main", "{ broken json");
        center.start();
        // 空组=全部 REAL + lastError 可见
        assertTrue(center.activeGroup().isEmpty());
        assertNotNull(center.lastError());
        assertEquals("config/groups/default/main.json",
                center.lastError().get("file").getAsString());
        assertTrue(center.lastError().get("message").getAsString().contains("语法错误")
                || center.lastError().get("message").getAsString().contains("JSON"));
    }

    @Test
    void brokenSettingsFallsBackToDefaults() throws Exception {
        atomicWrite(home.settingsFile(), "{ nope");
        center.start();
        assertEquals("default", center.settings().activeGroup);
        assertTrue(center.settings().mockEnabled);
        assertNotNull(center.lastError());
        assertEquals("settings.json", center.lastError().get("file").getAsString());
    }

    @Test
    void groupReloadKeepsOldSnapshotOnAnyFileFailure() throws Exception {
        writeGroup("default", "main", MOCK_5);
        writeGroup("default", "extra", MOCK_5);
        center.start();
        GroupSnapshot before = center.activeGroup();
        // 写坏 extra → 整组拒绝（main 的新内容也不生效）
        writeGroup("default", "main", MOCK_5.replace("\"value\":5", "\"value\":9"));
        writeGroup("default", "extra", "{ broken");
        center.onGroupFileChanged("default");
        assertSame(before, center.activeGroup(), "组级原子性：整组保持旧快照");
        assertNotNull(center.lastError());
        assertEquals("config/groups/default/extra.json",
                center.lastError().get("file").getAsString());
        // 恢复后生效
        writeGroup("default", "extra", MOCK_5);
        center.onGroupFileChanged("default");
        assertEquals(Integer.valueOf(9),
                valueOf(center.activeGroup()));
    }

    private Integer valueOf(GroupSnapshot snapshot) {
        return ((com.google.gson.JsonPrimitive) snapshot.files().get("main")
                .entries.get(0).defaultAction.value).getAsNumber().intValue();
    }

    @Test
    void idempotentReloadDoesNotReplaceOrRewriteState() throws Exception {
        writeGroup("default", "main", MOCK_5);
        center.start();
        sink.writes = 0;
        GroupSnapshot before = center.activeGroup();
        // 触发同样的文件事件（内容未变）→ 不替换、不重写 state
        writeGroup("default", "main", MOCK_5);
        center.onGroupFileChanged("default");
        assertSame(before, center.activeGroup());
        assertEquals(0, sink.writes, "快照内容未变不应重写 state.json");
    }

    @Test
    void settingsSwitchGroupLoadsNewGroupOnlyOnSuccess() throws Exception {
        writeGroup("default", "main", MOCK_5);
        writeGroup("fault-sim", "fs", MOCK_5.replace("\"value\":5", "\"value\":99"));
        center.start();
        // 切到 fault-sim
        atomicWrite(home.settingsFile(),
                "{\"activeGroup\":\"fault-sim\",\"mockEnabled\":true}");
        center.onSettingsFileChanged();
        assertEquals("fault-sim", center.activeGroup().groupName());
        assertTrue(center.activeGroup().files().containsKey("fs"));
        assertEquals("fault-sim", sink.activeGroup);
        // 切到不存在的组 → 保持旧组 + lastError
        atomicWrite(home.settingsFile(),
                "{\"activeGroup\":\"ghost\",\"mockEnabled\":true}");
        center.onSettingsFileChanged();
        assertEquals("fault-sim", center.activeGroup().groupName());
        assertEquals("fault-sim", center.settings().activeGroup);
        assertNotNull(center.lastError());
        assertTrue(center.lastError().get("file").getAsString()
                .contains("config/groups/ghost"));
        // 切回 default
        atomicWrite(home.settingsFile(),
                "{\"activeGroup\":\"default\",\"mockEnabled\":true}");
        center.onSettingsFileChanged();
        assertEquals("default", center.activeGroup().groupName());
    }

    @Test
    void mockEnabledToggleInvokesCallbackInstantly() throws Exception {
        writeGroup("default", "main", MOCK_5);
        center.start();
        final AtomicInteger toggles = new AtomicInteger();
        final List<Boolean> seen = new ArrayList<Boolean>();
        center.setGlobalEnabledCallback(new Consumer<Boolean>() {
            @Override
            public void accept(Boolean enabled) {
                toggles.incrementAndGet();
                seen.add(enabled);
            }
        });
        atomicWrite(home.settingsFile(),
                "{\"activeGroup\":\"default\",\"mockEnabled\":false}");
        center.onSettingsFileChanged();
        assertEquals(1, toggles.get());
        assertEquals(Boolean.FALSE, seen.get(0));
        assertFalse(center.settings().mockEnabled);
        assertFalse(sink.mockEnabled);
        // 同内容重复事件 → 幂等不回调
        center.onSettingsFileChanged();
        assertEquals(1, toggles.get());
    }

    @Test
    void nonActiveGroupChangesAreIgnored() throws Exception {
        writeGroup("default", "main", MOCK_5);
        center.start();
        GroupSnapshot before = center.activeGroup();
        writeGroup("other", "x", "{ broken"); // 非活动组坏文件也不影响
        center.onGroupFileChanged("other");
        assertSame(before, center.activeGroup());
        assertEquals(0, sink.writes);
    }

    @Test
    void runtimeErrorRecordedAndDeduplicated() throws Exception {
        writeGroup("default", "main", MOCK_5);
        center.start();
        sink.writes = 0;
        center.reportRuntimeError("config/groups/default/main.json", "boom");
        assertEquals("boom", center.lastError().get("message").getAsString());
        assertEquals(1, sink.writes);
        // 相同消息不重复写 state（热路径去重）
        center.reportRuntimeError("config/groups/default/main.json", "boom");
        assertEquals(1, sink.writes);
        // 新消息会覆盖
        center.reportRuntimeError("config/groups/default/main.json", "boom2");
        assertEquals("boom2", center.lastError().get("message").getAsString());
    }

    @Test
    void lastErrorHasTimeFileMessageStructure() throws Exception {
        writeGroup("default", "main", "{ bad");
        center.start();
        JsonObject error = center.lastError();
        assertNotNull(error);
        assertTrue(error.has("time"));
        assertTrue(error.has("file"));
        assertTrue(error.has("message"));
    }

    @Test
    void stateSinkReceivesGroupEntryCountsAndInstrumentedCount() throws Exception {
        writeGroup("default", "main", MOCK_5);
        writeGroup("default", "second", "{\"mocks\":[]}");
        center.setInstrumentedClassCount(new java.util.function.IntSupplier() {
            @Override
            public int getAsInt() {
                return 7;
            }
        });
        center.start();
        // 首启不写 state（AgentPremain 统一写）；通过一次变更触发
        writeGroup("default", "second", MOCK_5);
        center.onGroupFileChanged("default");
        assertEquals("default", sink.activeGroup);
        assertEquals(7, sink.instrumentedClasses);
        Map<String, Integer> expected = new LinkedHashMap<String, Integer>();
        expected.put("main", 1);
        expected.put("second", 1);
        assertEquals(expected, sink.counts);
    }

    @Test
    void snapshotReplaceNotifiesListener() throws Exception {
        writeGroup("default", "main", MOCK_5);
        center.start();
        final AtomicReference<GroupSnapshot> replaced = new AtomicReference<GroupSnapshot>();
        center.setGroupReloadListener(new ConfigCenter.GroupReloadListener() {
            @Override
            public void onActiveGroupReplaced(GroupSnapshot newSnapshot) {
                replaced.set(newSnapshot);
            }
        });
        writeGroup("default", "main", MOCK_5.replace("5", "6"));
        center.onGroupFileChanged("default");
        assertNotNull(replaced.get());
        assertEquals("default", replaced.get().groupName());
    }
}
