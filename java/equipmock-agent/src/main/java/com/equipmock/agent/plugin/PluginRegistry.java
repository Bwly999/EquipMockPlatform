package com.equipmock.agent.plugin;

import com.equipmock.agent.config.ConfigFiles;
import com.equipmock.agent.config.ConfigSchemaException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * plugins/plugin-registry.json 清单模型与 diff（04 §4 / 04 §7）。
 *
 * <p>清单是插件加载的唯一事实源：目录中不在清单内的 jar 一律不加载。
 * 语法/结构错误抛 {@link ConfigSchemaException}（调用方决定 startup=全不加载、
 * 运行期=保持旧状态 + lastError，04 §7「任意解析失败」行）。
 *
 * <p>id 语义（04 §4）：必须与 jar MANIFEST {@code Plugin-Id} 一致；
 * 同一 id 重复登记视为清单错误（fail-fast，工作台侧本不该产出）。
 */
public final class PluginRegistry {

    /** 清单条目（04 §4 字段全集；alias/note/importedAt 仅展示用，agent 不消费） */
    public static final class Entry {
        public final String id;
        public final String jar;
        public final boolean enabled;

        Entry(String id, String jar, boolean enabled) {
            this.id = id;
            this.jar = jar;
            this.enabled = enabled;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry other = (Entry) o;
            return id.equals(other.id) && jar.equals(other.jar)
                    && enabled == other.enabled;
        }

        @Override
        public int hashCode() {
            return id.hashCode() * 31 + jar.hashCode();
        }

        @Override
        public String toString() {
            return "Entry[" + id + " jar=" + jar + " enabled=" + enabled + "]";
        }
    }

    /** 变更 diff 结果（04 §7「plugin-registry.json」行：增/删/启停） */
    public static final class Diff {
        public final List<Entry> added = new ArrayList<Entry>();
        public final List<Entry> removed = new ArrayList<Entry>();
        public final List<Entry> enabledChanged = new ArrayList<Entry>();

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && enabledChanged.isEmpty();
        }

        @Override
        public String toString() {
            return "Diff[+" + added + " -" + removed + " ~" + enabledChanged + "]";
        }
    }

    private static final PluginRegistry EMPTY =
            new PluginRegistry(Collections.<Entry>emptyList());

    private final List<Entry> entries;
    private final Map<String, Entry> byId;

    private PluginRegistry(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
        Map<String, Entry> map = new LinkedHashMap<String, Entry>();
        for (Entry e : entries) {
            map.put(e.id, e);
        }
        this.byId = Collections.unmodifiableMap(map);
    }

    /** 空清单 */
    public static PluginRegistry empty() {
        return EMPTY;
    }

    /** 解析 plugin-registry.json（结构错误抛 ConfigSchemaException，display=相对路径） */
    public static PluginRegistry parse(Path file, String displayFile)
            throws ConfigSchemaException {
        JsonObject root = ConfigFiles.parseObject(file, displayFile);
        if (!root.has("plugins") || !root.get("plugins").isJsonArray()) {
            throw ConfigSchemaException.field(displayFile, "plugins",
                    "必填且必须为数组");
        }
        List<Entry> out = new ArrayList<Entry>();
        Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
        int index = 0;
        for (JsonElement element : root.get("plugins").getAsJsonArray()) {
            String at = "plugins[" + index + "]";
            if (!element.isJsonObject()) {
                throw ConfigSchemaException.field(displayFile, at, "必须是对象");
            }
            JsonObject obj = element.getAsJsonObject();
            String id = requiredString(obj, "id", at, displayFile);
            String jar = requiredString(obj, "jar", at, displayFile);
            if (!obj.has("enabled") || !obj.get("enabled").isJsonPrimitive()
                    || !obj.get("enabled").getAsJsonPrimitive().isBoolean()) {
                throw ConfigSchemaException.field(displayFile, at + ".enabled",
                        "必填且必须为 boolean");
            }
            boolean enabled = obj.get("enabled").getAsBoolean();
            if (seen.containsKey(id)) {
                throw ConfigSchemaException.field(displayFile, at + ".id",
                        "重复登记插件 id '" + id + "'（首次出现在 plugins["
                                + seen.get(id) + "]）");
            }
            seen.put(id, index);
            out.add(new Entry(id, jar, enabled));
            index++;
        }
        return new PluginRegistry(out);
    }

    private static String requiredString(JsonObject obj, String field, String at,
                                         String displayFile)
            throws ConfigSchemaException {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive()
                || !obj.get(field).getAsJsonPrimitive().isString()) {
            throw ConfigSchemaException.field(displayFile, at + "." + field,
                    "必填且必须为字符串");
        }
        String value = obj.get(field).getAsString();
        if (value.isEmpty()) {
            throw ConfigSchemaException.field(displayFile, at + "." + field,
                    "不得为空字符串");
        }
        return value;
    }

    /** 条目列表（清单登记序，即插件注册序） */
    public List<Entry> entries() {
        return entries;
    }

    public Entry byId(String id) {
        return byId.get(id);
    }

    public int size() {
        return entries.size();
    }

    /**
     * 与旧清单 diff（04 §7）：新增（按新清单序）/删除/启停。
     * jar 文件名变化按「删旧+增新」处理。
     */
    public Diff diffFrom(PluginRegistry old) {
        Diff d = new Diff();
        for (Entry e : entries) {
            Entry prev = old.byId.get(e.id);
            if (prev == null) {
                d.added.add(e);
            } else if (prev.enabled != e.enabled) {
                d.enabledChanged.add(e);
            } else if (!prev.jar.equals(e.jar)) {
                d.removed.add(prev);
                d.added.add(e);
            }
        }
        for (Entry e : old.entries) {
            if (!byId.containsKey(e.id)) {
                d.removed.add(e);
            }
        }
        return d;
    }
}
