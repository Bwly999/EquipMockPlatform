package com.equipmock.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 快照原子性并发测试（03 §1 / M2-2 验收：并发读 + 重载循环无异常、无半新半旧）。
 *
 * <p>多线程持续读（index lookup + 目标类集合 + 文件表自洽性校验），
 * 主线程循环改写组文件并触发重载；结束后断言读取次数、无异常、
 * 且每次读到的快照都是内部自洽的（files 与 index 同代）。
 */
class SnapshotConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentReadersNeverObserveTornSnapshots() throws Exception {
        Path home = tempDir.resolve("home");
        Files.createDirectories(home.resolve("config/groups/default"));
        Path groupFile = home.resolve("config/groups/default/main.json");

        com.equipmock.agent.AgentHome agentHome =
                com.equipmock.agent.AgentHome.prepare(home.toString());
        final AtomicInteger writes = new AtomicInteger();
        ConfigCenter center = new ConfigCenter(agentHome, Logger.getLogger("test"),
                new StateSink() {
                    @Override
                    public void writeState(String activeGroup, boolean mockEnabled,
                                           int instrumentedClasses,
                                           java.util.Map<String, Integer> groupFileEntryCounts,
                                           com.google.gson.JsonObject lastError) {
                        writes.incrementAndGet();
                    }
                });
        write(groupFile, mockJson(1));
        center.start();

        final int readers = 4;
        final int iterations = 300;
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger observedValues = new AtomicInteger();
        final List<Throwable> errors = new ArrayList<Throwable>();
        final CountDownLatch startGate = new CountDownLatch(1);

        Thread[] threads = new Thread[readers];
        for (int i = 0; i < readers; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startGate.await();
                        while (running.get()) {
                            GroupSnapshot snapshot = center.activeGroup();
                            // 自洽性：非空快照的 files 与 index 必须同代
                            assertNotNull(snapshot);
                            int declared = snapshot.files().get("main").entryCount();
                            boolean indexed = snapshot.index().targetClasses()
                                    .contains("a.B");
                            if (declared > 0 && !indexed) {
                                throw new AssertionError("半新半旧快照: files=" + declared
                                        + " 但 index 缺 a.B");
                            }
                            // 读规则链深部
                            List<MockEntry> entries = snapshot.index()
                                    .lookup("a.B", "m", "()I");
                            for (MockEntry entry : entries) {
                                observedValues.addAndGet(
                                        entry.rules.get(0).args.get(0).getAsInt());
                            }
                            reads.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        synchronized (errors) {
                            errors.add(t);
                        }
                    }
                }
            }, "reader-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        startGate.countDown();

        // 重建循环：交替写不同 value（触发快照替换）与相同内容（触发幂等路径）
        for (int i = 0; i < iterations; i++) {
            if (i % 2 == 0) {
                write(groupFile, mockJson((i % 20) + 1));
            }
            center.onGroupFileChanged("default");
        }
        running.set(false);
        for (Thread t : threads) {
            t.join(5000);
        }

        assertTrue(errors.isEmpty(), "并发读不应出现异常: " + errors);
        assertTrue(reads.get() > readers, "读取应实际发生: " + reads.get());
        assertTrue(observedValues.get() > 0);
        // 幂等路径覆盖：一半迭代内容未变 → 至少一半替换被跳过（无异常即可，此处弱断言）
        assertTrue(writes.get() > 0, "有内容变化的重建应回写 state");
    }

    private static void write(Path file, String json) throws Exception {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-"
                + (int) (Math.random() * 1000000));
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static String mockJson(int value) {
        return "{\"mocks\":[{\"class\":\"a.B\",\"method\":\"m\",\"signature\":\"()I\","
                + "\"enabled\":true,\"rules\":[{\"matchType\":\"FULL_MATCH\",\"args\":["
                + value + "]," + "\"action\":{\"type\":\"VALUE\",\"value\":"
                + value + "}}]}]}";
    }
}
