package com.equipmock.agent;

import com.equipmock.agent.config.StateSink;
import com.equipmock.agent.plugin.PluginStatus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * state.json 回写（02 §3 第 9 步 / 02 §8 / 04 §6）：agent 专写，工作台只读。
 * 原子写协议（04 §5）：临时文件 + Files.move(ATOMIC_MOVE)，失败重试 3 次；
 * 文件被占用等最终失败仅记日志，绝不影响宿主运行。
 *
 * <p>M2 扩展（02 §8 回写时机全集）：activeGroup、mockEnabled、活动组各文件条目数
 * （groupFiles，03 §2 第 4 步）、lastError {time,file,message}；
 * instrumentedClasses 保持 M1 语义（注册的目标类数）。startedAt 固定为 agent 启动时刻。
 */
public final class StateWriter implements StateSink {

    /** 写失败重试次数（04 §6） */
    private static final int MAX_RETRIES = 3;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME; // 2026-08-19T10:00:00+08:00 形式

    // serializeNulls：04 §6 要求无错误时显式输出 "lastError": null
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting()
            .serializeNulls().create();

    private final Path stateFile;
    private final Logger log;
    private final String agentVersion;
    private final String startedAt = OffsetDateTime.now().format(TIME);

    /** M3：plugins[] 数据源（PluginService::statuses）；null=空数组 */
    private volatile Supplier<List<PluginStatus>> pluginsSupplier;
    /** M3：needsRestart 数据源（PluginService::needsRestart）；null=空数组 */
    private volatile Supplier<Collection<String>> needsRestartSupplier;

    public StateWriter(Path stateFile, Logger log, String agentVersion) {
        this.stateFile = stateFile;
        this.log = log;
        this.agentVersion = agentVersion;
    }

    /** 注入 plugins[] 数据源（AgentPremain 装配 PluginService 后调用） */
    public void setPluginsSupplier(Supplier<List<PluginStatus>> supplier) {
        this.pluginsSupplier = supplier;
    }

    /** 注入 needsRestart 数据源 */
    public void setNeedsRestartSupplier(Supplier<Collection<String>> supplier) {
        this.needsRestartSupplier = supplier;
    }

    /** lastError 结构（04 §6）；message 为 null 时整体写 null */
    public static JsonObject errorObject(String file, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("time", OffsetDateTime.now().format(TIME));
        error.addProperty("file", file);
        error.addProperty("message", message);
        return error;
    }

    /** M1 兼容视图（降级路径）：无组文件条目数字段 */
    public void write(String activeGroup, boolean mockEnabled, int instrumentedClasses,
                      JsonObject lastError) {
        writeState(activeGroup, mockEnabled, instrumentedClasses, null, lastError);
    }

    @Override
    public synchronized void writeState(String activeGroup, boolean mockEnabled,
                                        int instrumentedClasses,
                                        Map<String, Integer> groupFileEntryCounts,
                                        JsonObject lastError) {
        JsonObject state = new JsonObject();
        state.addProperty("$schema", "equipmock/state@1");
        state.addProperty("agentVersion", agentVersion);
        state.addProperty("pid", currentPid());
        state.addProperty("startedAt", startedAt);
        state.addProperty("lastWriteAt", OffsetDateTime.now().format(TIME));
        state.addProperty("activeGroup", activeGroup);
        state.addProperty("mockEnabled", mockEnabled);
        state.addProperty("instrumentedClasses", instrumentedClasses);
        if (groupFileEntryCounts != null) {
            JsonObject files = new JsonObject();
            for (Map.Entry<String, Integer> e : groupFileEntryCounts.entrySet()) {
                files.addProperty(e.getKey(), e.getValue().intValue());
            }
            state.add("groupFiles", files);
        }
        JsonArray plugins = new JsonArray();
        Supplier<List<PluginStatus>> pluginsSource = this.pluginsSupplier;
        if (pluginsSource != null) {
            List<PluginStatus> statuses = pluginsSource.get();
            if (statuses != null) {
                for (PluginStatus status : statuses) {
                    plugins.add(status.toJson());
                }
            }
        }
        state.add("plugins", plugins);
        state.add("lastError", lastError == null ? JsonNull.INSTANCE : lastError);
        JsonArray needsRestart = new JsonArray();
        Supplier<Collection<String>> needsRestartSource = this.needsRestartSupplier;
        if (needsRestartSource != null) {
            Collection<String> classes = needsRestartSource.get();
            if (classes != null) {
                for (String className : classes) {
                    needsRestart.add(className);
                }
            }
        }
        state.add("needsRestart", needsRestart);
        writeAtomically(GSON.toJson(state));
    }

    private void writeAtomically(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp-"
                    + randomSuffix());
            try {
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                log.warning("state.json write attempt " + attempt + "/" + MAX_RETRIES
                        + " failed: " + e);
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件清理失败可忽略
                }
            }
        }
        // 04 §6：3 次重试后仅记日志，绝不影响宿主
        log.severe("state.json write finally failed after " + MAX_RETRIES + " attempts");
    }

    private static String randomSuffix() {
        return String.format("%06d", new SecureRandom().nextInt(1000000));
    }

    /** JDK8 无 ProcessHandle：从 RuntimeMXBean 名称 "pid@host" 解析 */
    static long currentPid() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Long.parseLong(name.substring(0, name.indexOf('@')));
        } catch (Throwable t) {
            return -1L;
        }
    }
}
