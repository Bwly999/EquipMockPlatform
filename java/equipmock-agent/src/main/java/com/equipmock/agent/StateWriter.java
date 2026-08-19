package com.equipmock.agent;

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
import java.util.logging.Logger;

/**
 * state.json 回写（02 §3 第 9 步 / 04 §6）：agent 专写，工作台只读。
 * 原子写协议（04 §5）：临时文件 + Files.move(ATOMIC_MOVE)，失败重试 3 次；
 * 文件被占用等最终失败仅记日志，绝不影响宿主运行。
 */
public final class StateWriter {

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

    public StateWriter(Path stateFile, Logger log, String agentVersion) {
        this.stateFile = stateFile;
        this.log = log;
        this.agentVersion = agentVersion;
    }

    /** lastError 结构（04 §6）；message 为 null 时整体写 null */
    public static JsonObject errorObject(String file, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("time", OffsetDateTime.now().format(TIME));
        error.addProperty("file", file);
        error.addProperty("message", message);
        return error;
    }

    /**
     * 全量写 state.json（M1 视图：无插件、无热导入）。
     *
     * @param activeGroup 活动配置组（settings）
     * @param mockEnabled 全局开关（settings）
     * @param instrumentedClasses 已注册插桩的目标类数（M1 语义=注册数，见 02 §8）
     * @param lastError 启动期错误；null 表示无错误
     */
    public void write(String activeGroup, boolean mockEnabled, int instrumentedClasses,
                      JsonObject lastError) {
        JsonObject state = new JsonObject();
        state.addProperty("$schema", "equipmock/state@1");
        state.addProperty("agentVersion", agentVersion);
        state.addProperty("pid", currentPid());
        state.addProperty("startedAt", OffsetDateTime.now().format(TIME));
        state.addProperty("lastWriteAt", OffsetDateTime.now().format(TIME));
        state.addProperty("activeGroup", activeGroup);
        state.addProperty("mockEnabled", mockEnabled);
        state.addProperty("instrumentedClasses", instrumentedClasses);
        state.add("plugins", new JsonArray()); // M1：无插件框架
        state.add("lastError", lastError == null ? JsonNull.INSTANCE : lastError);
        state.add("needsRestart", new JsonArray());
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
