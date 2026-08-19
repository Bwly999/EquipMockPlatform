package com.equipmock.agent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * equip-mock home 目录定位与骨架初始化（02 §3 第 1 步 / 04 §1）。
 *
 * <p>定位顺序：{@code -Dequipmock.home} &gt; {@code ./equip-mock}（宿主工作目录）。
 * 不存在则创建骨架：settings.json、plugins/plugin-registry.json（空清单）、
 * config/groups/default/ 空目录、logs/。已存在的文件一律不覆盖（人/工作台是写方）。
 */
public final class AgentHome {

    /** settings.json 缺省内容（04 §2） */
    private static final String DEFAULT_SETTINGS =
            "{\n"
                    + "  \"$schema\": \"equipmock/settings@1\",\n"
                    + "  \"activeGroup\": \"default\",\n"
                    + "  \"mockEnabled\": true\n"
                    + "}\n";

    /** plugin-registry.json 空清单（04 §4） */
    private static final String DEFAULT_PLUGIN_REGISTRY =
            "{\n"
                    + "  \"$schema\": \"equipmock/plugin-registry@1\",\n"
                    + "  \"plugins\": []\n"
                    + "}\n";

    private final Path home;

    private AgentHome(Path home) {
        this.home = home;
    }

    /** 解析 home 并确保骨架存在 */
    public static AgentHome prepare(String homePropertyOverride) throws IOException {
        String configured = homePropertyOverride != null
                ? homePropertyOverride
                : System.getProperty("equipmock.home", "equip-mock");
        Path home = Paths.get(configured).toAbsolutePath().normalize();
        createDirectories(home.resolve("plugins"));
        createDirectories(home.resolve("config").resolve("groups").resolve("default"));
        createDirectories(home.resolve("logs"));
        createFileIfAbsent(home.resolve("settings.json"), DEFAULT_SETTINGS);
        createFileIfAbsent(home.resolve("plugins").resolve("plugin-registry.json"),
                DEFAULT_PLUGIN_REGISTRY);
        return new AgentHome(home);
    }

    private static void createDirectories(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
    }

    private static void createFileIfAbsent(Path file, String content) throws IOException {
        if (!Files.exists(file)) {
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        }
    }

    public Path root() {
        return home;
    }

    public Path logsDir() {
        return home.resolve("logs");
    }

    public Path stateFile() {
        return home.resolve("state.json");
    }

    public Path settingsFile() {
        return home.resolve("settings.json");
    }

    /** settings 快照（只取 M1 需要的两个字段；文件损坏时用默认值） */
    public Settings readSettings() {
        Path file = settingsFile();
        if (!Files.isReadable(file)) {
            return Settings.DEFAULTS;
        }
        try {
            com.google.gson.JsonObject obj;
            Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
            try {
                obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            } finally {
                reader.close();
            }
            String group = obj.has("activeGroup") && obj.get("activeGroup").isJsonPrimitive()
                    ? obj.get("activeGroup").getAsString() : "default";
            boolean enabled = !obj.has("mockEnabled") || obj.get("mockEnabled").getAsBoolean();
            return new Settings(group, enabled);
        } catch (Throwable t) {
            // 非法 settings：按默认值运行，错误由调用方写 state.lastError
            return Settings.DEFAULTS;
        }
    }

    /** settings 内存快照 */
    public static final class Settings {
        static final Settings DEFAULTS = new Settings("default", true);

        public final String activeGroup;
        public final boolean mockEnabled;

        Settings(String activeGroup, boolean mockEnabled) {
            this.activeGroup = activeGroup;
            this.mockEnabled = mockEnabled;
        }
    }
}
