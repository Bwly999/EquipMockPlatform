package com.equipmock.agent.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 小分组配置加载与 Schema 校验（03 §6 / 04 §3；与 docs/schemas/subgroup.schema.json 语义一致）。
 *
 * <p>组级原子性：{@link #parseGroup} 逐文件解析，任一文件失败即抛
 * {@link ConfigSchemaException}（整组拒绝，调用方保持旧快照，03 §2）。
 *
 * <p>校验规则全集：
 * <ol>
 *   <li>JSON 语法可解析（错误含行列号）</li>
 *   <li>mocks[].class 为 FQCN、method 为 Java 标识符、signature（若填）为合法 JVM descriptor</li>
 *   <li>matchType ∈ {FULL_MATCH, PATTERN_MATCH}；FULL_MATCH 必须带 args、PATTERN_MATCH
 *       必须带 argsPattern 且每项为<b>可编译正则</b>；两者与 matchType 不得交叉出现</li>
 *   <li>action 三选一字段完整：VALUE 必带 value（THROW/VOID 不得带）；THROW 的 exception
 *       必须为 FQCN、message 可选；VOID 不带 value</li>
 *   <li>未知字段拒绝（与 schema additionalProperties=false 一致）</li>
 * </ol>
 * 错误消息统一为 {@code 文件: 字段路径 详情 (line, column)}。
 */
public final class GroupConfigParser {

    /** settings 的 activeGroup 允许的目录名（04 §1：文件/目录名 [A-Za-z0-9_-]{1,64}） */
    public static final String GROUP_DIR_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private GroupConfigParser() {
    }

    /**
     * 解析整个组目录 → 不可变快照（文件按自然序，任一失败整组拒绝）。
     *
     * @param groupDir config/groups/&lt;groupName&gt; 目录
     * @param displayDirPrefix state.lastError 用的显示前缀（如 config/groups/default）
     */
    public static GroupSnapshot parseGroup(Path groupDir, String groupName,
                                           String displayDirPrefix) {
        if (!Files.isDirectory(groupDir)) {
            throw ConfigSchemaException.field(displayDirPrefix, "",
                    "配置组目录不存在或不可读: " + groupDir);
        }
        List<Path> files = listJsonFiles(groupDir);
        List<SubGroupFile> parsed = new ArrayList<SubGroupFile>(files.size());
        for (Path file : files) {
            parsed.add(parseFile(groupDir, file, displayDirPrefix));
        }
        Map<String, SubGroupFile> fileMap = new LinkedHashMap<String, SubGroupFile>();
        for (SubGroupFile f : parsed) {
            fileMap.put(f.fileName, f);
        }
        return new GroupSnapshot(groupName,
                Collections.unmodifiableMap(fileMap), MockIndex.build(parsed));
    }

    /** 列出组目录下全部 .json 契约文件（排除 tmp 与 .bak 临时文件），按文件名自然序 */
    public static List<Path> listJsonFiles(Path groupDir) {
        List<Path> out = new ArrayList<Path>();
        DirectoryStream.Filter<Path> filter = new DirectoryStream.Filter<Path>() {
            @Override
            public boolean accept(Path entry) throws IOException {
                String name = entry.getFileName().toString();
                return ConfigFiles.isJsonConfig(name) && !ConfigFiles.isTempOrBackup(name)
                        && Files.isRegularFile(entry);
            }
        };
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(groupDir, filter);
            try {
                for (Path p : stream) {
                    out.add(p);
                }
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            throw ConfigSchemaException.field(groupDir.toString(), "",
                    "枚举组目录失败: " + e.getMessage());
        }
        Collections.sort(out, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return ConfigFiles.naturalCompare(a.getFileName().toString(),
                        b.getFileName().toString());
            }
        });
        return out;
    }

    /** 解析单个小分组文件（display 前缀用于错误消息） */
    public static SubGroupFile parseFile(Path groupDir, Path file, String displayDirPrefix) {
        String fileName = ConfigFiles.baseName(file.getFileName().toString());
        String displayFile = displayDirPrefix + "/" + file.getFileName();
        JsonObject root = ConfigFiles.parseObject(file, displayFile);

        checkAllowedMembers(root, displayFile, "",
                "$schema", "name", "description", "mocks");
        if (!root.has("mocks") || !root.get("mocks").isJsonArray()) {
            throw ConfigSchemaException.field(displayFile, "mocks", "必填且必须为数组");
        }
        String displayName = root.has("name") && root.get("name").isJsonPrimitive()
                ? root.get("name").getAsString() : fileName;

        JsonArray mocks = root.getAsJsonArray("mocks");
        List<MockEntry> entries = new ArrayList<MockEntry>(mocks.size());
        for (int i = 0; i < mocks.size(); i++) {
            String path = "mocks[" + i + "]";
            JsonElement el = mocks.get(i);
            if (!el.isJsonObject()) {
                throw ConfigSchemaException.field(displayFile, path, "必须是对象");
            }
            entries.add(parseMockEntry(el.getAsJsonObject(), displayFile, path, displayFile));
        }
        return new SubGroupFile(fileName, displayName,
                Collections.unmodifiableList(entries));
    }

    private static MockEntry parseMockEntry(JsonObject mock, String displayFile,
                                            String path, String sourceFile) {
        checkAllowedMembers(mock, displayFile, path,
                "class", "method", "signature", "description", "enabled",
                "rules", "defaultAction");

        String className = requireString(mock, displayFile, path, "class");
        if (!DescriptorValidator.isFqcn(className)) {
            throw ConfigSchemaException.field(displayFile, path + ".class",
                    "必须是 FQCN 格式: '" + className + "'");
        }
        String methodName = requireString(mock, displayFile, path, "method");
        if (!DescriptorValidator.isIdentifier(methodName)) {
            throw ConfigSchemaException.field(displayFile, path + ".method",
                    "必须是 Java 标识符: '" + methodName + "'");
        }
        String descriptor = null;
        if (mock.has("signature")) {
            JsonElement sig = mock.get("signature");
            if (!sig.isJsonPrimitive() || !sig.getAsJsonPrimitive().isString()) {
                throw ConfigSchemaException.field(displayFile, path + ".signature",
                        "必须是字符串（JVM descriptor）");
            }
            descriptor = sig.getAsString();
            String err = DescriptorValidator.validate(descriptor);
            if (err != null) {
                throw ConfigSchemaException.field(displayFile, path + ".signature",
                        "非法 JVM descriptor: " + err);
            }
        }
        if (!mock.has("enabled") || !mock.get("enabled").isJsonPrimitive()
                || !mock.get("enabled").getAsJsonPrimitive().isBoolean()) {
            throw ConfigSchemaException.field(displayFile, path + ".enabled",
                    "必填且必须为 boolean");
        }
        boolean enabled = mock.get("enabled").getAsBoolean();
        if (!mock.has("rules") || !mock.get("rules").isJsonArray()) {
            throw ConfigSchemaException.field(displayFile, path + ".rules",
                    "必填且必须为数组（可为空）");
        }
        JsonArray rulesJson = mock.getAsJsonArray("rules");
        List<MockRule> rules = new ArrayList<MockRule>(rulesJson.size());
        for (int i = 0; i < rulesJson.size(); i++) {
            String rulePath = path + ".rules[" + i + "]";
            JsonElement el = rulesJson.get(i);
            if (!el.isJsonObject()) {
                throw ConfigSchemaException.field(displayFile, rulePath, "必须是对象");
            }
            rules.add(parseRule(el.getAsJsonObject(), displayFile, rulePath));
        }
        ActionDef defaultAction = null;
        if (mock.has("defaultAction")) {
            JsonElement da = mock.get("defaultAction");
            if (da.isJsonNull()) {
                defaultAction = null; // 显式 null 视同未配置
            } else if (da.isJsonObject()) {
                defaultAction = parseAction(da.getAsJsonObject(), displayFile,
                        path + ".defaultAction");
            } else {
                throw ConfigSchemaException.field(displayFile,
                        path + ".defaultAction", "必须是对象或 null");
            }
        }
        return new MockEntry(className, methodName, descriptor, enabled,
                Collections.unmodifiableList(rules), defaultAction, sourceFile);
    }

    private static MockRule parseRule(JsonObject rule, String displayFile, String path) {
        checkAllowedMembers(rule, displayFile, path,
                "matchType", "description", "args", "argsPattern", "action");
        if (!rule.has("matchType") || !rule.get("matchType").isJsonPrimitive()) {
            throw ConfigSchemaException.field(displayFile, path + ".matchType",
                    "必填（FULL_MATCH | PATTERN_MATCH）");
        }
        String matchType = rule.get("matchType").getAsString();
        MockRule.MatchType type;
        if ("FULL_MATCH".equals(matchType)) {
            type = MockRule.MatchType.FULL_MATCH;
        } else if ("PATTERN_MATCH".equals(matchType)) {
            type = MockRule.MatchType.PATTERN_MATCH;
        } else {
            throw ConfigSchemaException.field(displayFile, path + ".matchType",
                    "非法值: '" + matchType + "'（仅 FULL_MATCH / PATTERN_MATCH）");
        }
        List<JsonElement> args = null;
        List<String> patternSources = null;
        List<Pattern> patterns = null;
        if (type == MockRule.MatchType.FULL_MATCH) {
            if (!rule.has("args") || !rule.get("args").isJsonArray()) {
                throw ConfigSchemaException.field(displayFile, path + ".args",
                        "FULL_MATCH 必填且必须为数组");
            }
            if (rule.has("argsPattern")) {
                throw ConfigSchemaException.field(displayFile, path + ".argsPattern",
                        "FULL_MATCH 不得出现 argsPattern");
            }
            JsonArray arr = rule.getAsJsonArray("args");
            args = new ArrayList<JsonElement>(arr.size());
            for (JsonElement e : arr) {
                args.add(e);
            }
        } else {
            if (!rule.has("argsPattern") || !rule.get("argsPattern").isJsonArray()) {
                throw ConfigSchemaException.field(displayFile, path + ".argsPattern",
                        "PATTERN_MATCH 必填且必须为字符串数组");
            }
            if (rule.has("args")) {
                throw ConfigSchemaException.field(displayFile, path + ".args",
                        "PATTERN_MATCH 不得出现 args");
            }
            JsonArray arr = rule.getAsJsonArray("argsPattern");
            patternSources = new ArrayList<String>(arr.size());
            patterns = new ArrayList<Pattern>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JsonElement e = arr.get(i);
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                    throw ConfigSchemaException.field(displayFile,
                            path + ".argsPattern[" + i + "]", "必须是字符串（正则）");
                }
                String src = e.getAsString();
                try {
                    patterns.add(Pattern.compile(src));
                    patternSources.add(src);
                } catch (PatternSyntaxException pse) {
                    throw ConfigSchemaException.field(displayFile,
                            path + ".argsPattern[" + i + "]",
                            "非法正则: " + pse.getDescription());
                }
            }
        }
        if (!rule.has("action") || !rule.get("action").isJsonObject()) {
            throw ConfigSchemaException.field(displayFile, path + ".action",
                    "必填且必须为对象");
        }
        ActionDef action = parseAction(rule.getAsJsonObject("action"), displayFile,
                path + ".action");
        return new MockRule(type, args == null ? Collections.<JsonElement>emptyList()
                        : Collections.unmodifiableList(args),
                patternSources == null ? Collections.<String>emptyList()
                        : Collections.unmodifiableList(patternSources),
                patterns == null ? Collections.<Pattern>emptyList()
                        : Collections.unmodifiableList(patterns),
                action,
                optString(rule, "description"));
    }

    private static ActionDef parseAction(JsonObject action, String displayFile, String path) {
        checkAllowedMembers(action, displayFile, path,
                "type", "value", "exception", "message");
        if (!action.has("type") || !action.get("type").isJsonPrimitive()) {
            throw ConfigSchemaException.field(displayFile, path + ".type",
                    "必填（VALUE | THROW | VOID）");
        }
        String typeStr = action.get("type").getAsString();
        ActionDef.Type type;
        if ("VALUE".equals(typeStr)) {
            type = ActionDef.Type.VALUE;
        } else if ("THROW".equals(typeStr)) {
            type = ActionDef.Type.THROW;
        } else if ("VOID".equals(typeStr)) {
            type = ActionDef.Type.VOID;
        } else {
            throw ConfigSchemaException.field(displayFile, path + ".type",
                    "非法值: '" + typeStr + "'（仅 VALUE / THROW / VOID）");
        }
        switch (type) {
            case VALUE:
                if (!action.has("value")) {
                    throw ConfigSchemaException.field(displayFile, path + ".value",
                            "VALUE 必带 value");
                }
                if (action.has("exception") || action.has("message")) {
                    throw ConfigSchemaException.field(displayFile, path,
                            "VALUE 不得出现 exception/message 字段");
                }
                return new ActionDef(type, action.get("value"), null, null);
            case THROW:
                if (!action.has("exception") || !action.get("exception").isJsonPrimitive()) {
                    throw ConfigSchemaException.field(displayFile, path + ".exception",
                            "THROW 必带 exception（FQCN）");
                }
                String fqcn = action.get("exception").getAsString();
                if (!DescriptorValidator.isFqcn(fqcn)) {
                    throw ConfigSchemaException.field(displayFile, path + ".exception",
                            "必须是 FQCN 格式: '" + fqcn + "'");
                }
                if (action.has("value")) {
                    throw ConfigSchemaException.field(displayFile, path,
                            "THROW 不得出现 value 字段");
                }
                String message = optString(action, "message");
                if (action.has("message")
                        && !(action.get("message").isJsonPrimitive()
                             && action.get("message").getAsJsonPrimitive().isString())) {
                    throw ConfigSchemaException.field(displayFile, path + ".message",
                            "必须是字符串");
                }
                return new ActionDef(type, null, fqcn, message);
            case VOID:
            default:
                if (action.has("value")) {
                    throw ConfigSchemaException.field(displayFile, path + ".value",
                            "VOID 不带 value");
                }
                if (action.has("exception") || action.has("message")) {
                    throw ConfigSchemaException.field(displayFile, path,
                            "VOID 不得出现 exception/message 字段");
                }
                return new ActionDef(type, null, null, null);
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static void checkAllowedMembers(JsonObject obj, String displayFile,
                                            String path, String... allowed) {
        Set<String> allow = new java.util.HashSet<String>(Arrays.asList(allowed));
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            if (!allow.contains(e.getKey())) {
                throw ConfigSchemaException.field(displayFile,
                        path.isEmpty() ? e.getKey() : path + "." + e.getKey(),
                        "未知字段（schema additionalProperties=false）");
            }
        }
    }

    private static String requireString(JsonObject obj, String displayFile,
                                        String path, String member) {
        if (!obj.has(member) || !obj.get(member).isJsonPrimitive()
                || !obj.get(member).getAsJsonPrimitive().isString()) {
            throw ConfigSchemaException.field(displayFile, path + "." + member,
                    "必填且必须为非空字符串");
        }
        String v = obj.get(member).getAsString();
        if (v.isEmpty()) {
            throw ConfigSchemaException.field(displayFile, path + "." + member,
                    "必填且必须为非空字符串");
        }
        return v;
    }

    private static String optString(JsonObject obj, String member) {
        if (obj.has(member) && obj.get(member).isJsonPrimitive()
                && obj.get(member).getAsJsonPrimitive().isString()) {
            return obj.get(member).getAsString();
        }
        return null;
    }
}
