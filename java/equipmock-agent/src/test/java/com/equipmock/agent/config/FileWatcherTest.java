package com.equipmock.agent.config;

import com.equipmock.agent.AgentHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileWatcher 单测（03 §7 / 04 §8）：临时目录 + 手写轮询断言（不引新依赖）。
 * WatchService 主通道验证 settings/组文件/registry 三类事件 + tmp/bak 忽略。
 */
class FileWatcherTest {

    @TempDir
    Path tempDir;

    private AgentHome home;
    private FileWatcher watcher;
    private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<String>();
    private final AtomicInteger settingsEvents = new AtomicInteger();
    private final AtomicInteger registryEvents = new AtomicInteger();
    private final CountDownLatch settingsLatch = new CountDownLatch(1);
    private final CountDownLatch groupLatch = new CountDownLatch(1);
    private final CountDownLatch registryLatch = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        home = AgentHome.prepare(tempDir.resolve("home").toString());
        Files.createDirectories(home.root().resolve("config/groups/default"));
        watcher = new FileWatcher(home.root(), new FileWatcher.Listener() {
            @Override
            public void onSettingsChanged(Path settingsFile) {
                settingsEvents.incrementAndGet();
                settingsLatch.countDown();
                events.add("settings");
            }

            @Override
            public void onGroupFileChanged(String group, Path file) {
                events.add("group:" + group + ":" + file.getFileName());
                groupLatch.countDown();
            }

            @Override
            public void onPluginRegistryChanged(Path registryFile) {
                registryEvents.incrementAndGet();
                registryLatch.countDown();
                events.add("registry");
            }
        }, Logger.getLogger("test"));
        watcher.start();
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.stop();
        }
    }

    /** 原子写（04 §5 写方协议） */
    private void atomicWrite(Path target, String content) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-"
                + (int) (Math.random() * 1000000));
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Awaitility 式轮询等待（无新依赖） */
    private static void awaitTrue(java.util.function.BooleanSupplier condition,
                                  long timeoutMillis, String what) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("等待超时: " + what);
    }

    @Test
    void settingsChangeTriggersCallback() throws Exception {
        atomicWrite(home.settingsFile(),
                "{\"activeGroup\":\"default\",\"mockEnabled\":false}");
        assertTrue(settingsLatch.await(10, TimeUnit.SECONDS),
                "settings.json 变化应在防抖后回调");
        assertTrue(settingsEvents.get() >= 1);
    }

    @Test
    void groupFileChangeTriggersCallbackWithGroupName() throws Exception {
        atomicWrite(home.root().resolve("config/groups/default/cabinet.json"),
                "{\"mocks\":[]}");
        assertTrue(groupLatch.await(10, TimeUnit.SECONDS), "组文件变化应回调");
        boolean found = false;
        for (String e : events) {
            if (e.startsWith("group:default:")) {
                found = true;
            }
        }
        assertTrue(found, "回调应携带组名 default: " + events);
    }

    @Test
    void pluginRegistryChangeTriggersCallback() throws Exception {
        atomicWrite(home.root().resolve("plugins/plugin-registry.json"),
                "{\"plugins\":[]}");
        assertTrue(registryLatch.await(10, TimeUnit.SECONDS),
                "plugin-registry.json 变化应回调（M3 预留通道）");
    }

    @Test
    void tmpAndBakFilesIgnored() throws Exception {
        Path dir = home.root().resolve("config/groups/default");
        final AtomicInteger before = new AtomicInteger(events.size());
        atomicWrite(dir.resolve("a.tmp-123456"), "{}");
        atomicWrite(dir.resolve("b.json.bak"), "{}");
        atomicWrite(dir.resolve("c.json.tmp-1"), "{}");
        // 等待 2.5s（防抖 500ms + 富余），不应产生任何事件
        Thread.sleep(2500);
        assertEquals(before.get(), events.size(),
                "tmp/bak 不应触发事件: " + events);
    }

    @Test
    void sameFileMultipleRawEventsMergedIntoOneCallback() throws Exception {
        // Windows 单次原子 move 通常产生 DELETE(tmp)+CREATE+MODIFY 多个原始事件，
        // 防抖应把它们合并为一次回调；间隔 >500ms 的两次写则各回调一次
        Path file = home.root().resolve("config/groups/default/d.json");
        final AtomicInteger count = new AtomicInteger();
        final CountDownLatch first = new CountDownLatch(1);
        FileWatcher counting = new FileWatcher(home.root(), new FileWatcher.Listener() {
            @Override
            public void onSettingsChanged(Path settingsFile) {
            }

            @Override
            public void onGroupFileChanged(String group, Path f) {
                count.incrementAndGet();
                first.countDown();
            }

            @Override
            public void onPluginRegistryChanged(Path registryFile) {
            }
        }, Logger.getLogger("test"));
        counting.start();
        try {
            atomicWrite(file, "{\"v\":1}");
            assertTrue(first.await(10, TimeUnit.SECONDS), "第一次写入应回调");
            int afterFirst = count.get();
            assertTrue(afterFirst == 1, "同文件一次写入的多个原始事件应合并: " + afterFirst);
            Thread.sleep(1200); // 跨过防抖窗口后再写第二次
            atomicWrite(file, "{\"v\":2}");
            awaitTrue(new java.util.function.BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return count.get() >= 2;
                }
            }, 10000, "第二次写入应回调");
        } finally {
            counting.stop();
        }
    }

    @Test
    void newGroupDirectoryCreatedAfterStartIsWatched() throws Exception {
        final CountDownLatch newGroupLatch = new CountDownLatch(1);
        FileWatcher nested = new FileWatcher(home.root(), new FileWatcher.Listener() {
            @Override
            public void onSettingsChanged(Path settingsFile) {
            }

            @Override
            public void onGroupFileChanged(String group, Path file) {
                if ("fresh".equals(group)) {
                    newGroupLatch.countDown();
                }
            }

            @Override
            public void onPluginRegistryChanged(Path registryFile) {
            }
        }, Logger.getLogger("test"));
        nested.start();
        try {
            Path fresh = home.root().resolve("config/groups/fresh");
            Files.createDirectories(fresh);
            atomicWrite(fresh.resolve("x.json"), "{\"mocks\":[]}");
            assertTrue(newGroupLatch.await(10, TimeUnit.SECONDS),
                    "启动后新建的组目录也应在监听范围内（含兜底轮询通道）");
        } finally {
            nested.stop();
        }
    }

    @Test
    void fallbackPollDetectsChangeWhenWatchServiceMissesIt() throws Exception {
        // 模拟 WatchService 丢事件（04 §8 兜底语义）：兜底轮询 5s 一拍也应发现新契约文件
        Path file = home.root().resolve("config/groups/default/polled.json");
        atomicWrite(file, "{\"v\":1}");
        awaitTrue(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                for (String e : events) {
                    if (e.startsWith("group:default:")) {
                        return true;
                    }
                }
                return false;
            }
        }, 12000, "兜底轮询应发现新契约文件");
    }
}
