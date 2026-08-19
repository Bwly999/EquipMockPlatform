package com.equipmock.agent.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code Plugin-Requires} 解析与平台版本硬校验（D19，05 §6）。
 *
 * <p>语法：{@code equipmock >=1.0.0 <2.0.0}——首个 token 为产品名（必须
 * {@code equipmock}），其后为若干 {@code op version} 条件（操作符
 * {@code >=,<=,<,>,=}），<b>空格分隔即 AND</b>。
 *
 * <p>实现决策（对 docs/02 §6.1 的细化）：
 * <ul>
 *   <li>版本比较用本类自带数值比较而非 PF4J VersionManager：
 *       ① PF4J {@code checkVersionConstraint} 无法解析产品名前缀与多条件 AND 格式；
 *       ② semver 语义下 {@code 1.0.0-SNAPSHOT < 1.0.0}（prerelease），会导致平台
 *       {@code 1.0.0-SNAPSHOT} 永远不满足 {@code >=1.0.0}——内部平台约定
 *       <b>限定符（-SNAPSHOT 等）不参与比较</b>，仅比较数字段
 *       {@code major.minor.patch}（缺省补 0）。</li>
 *   <li>缺 {@code Plugin-Requires} 视为<b>不满足</b>（REJECTED）：D19 是硬校验
 *       （05 §1 构件模板必含该属性），未声明兼容区间的插件兼容性未知，装备软件
 *       场景 fail-closed 更安全，且错误信息可指导作者补齐声明。</li>
 * </ul>
 */
public final class PluginRequires {

    /** 平台产品名（Plugin-Requires 首个 token） */
    public static final String PRODUCT = "equipmock";

    /** 操作符 + 版本（数字段，可带 -SNAPSHOT 等限定符）：">=1.0.0"、"=2.0.0-M1" */
    private static final Pattern CONDITION =
            Pattern.compile("^(>=|<=|>|<|=)(\\d+(?:\\.\\d+){0,3}(?:[-+][0-9A-Za-z.-]+)?)$");

    private static final Pattern VERSION =
            Pattern.compile("^(\\d+(?:\\.\\d+){0,3})(?:[-+][0-9A-Za-z.-]+)?$");

    /** 校验结果：satisfied=false 时 message 说明原因（可直接写 state.plugins[].error） */
    public static final class Result {
        public final boolean satisfied;
        public final String message;

        private Result(boolean satisfied, String message) {
            this.satisfied = satisfied;
            this.message = message;
        }

        static Result ok() {
            return new Result(true, null);
        }

        static Result reject(String message) {
            return new Result(false, message);
        }
    }

    private PluginRequires() {
    }

    /**
     * 校验 {@code Plugin-Requires} 属性值是否被当前平台版本满足。
     *
     * @param requires manifest 原始属性值（可为 null=缺失）
     * @param platformVersion 平台版本（agent Implementation-Version）
     * @return 缺失/非法/不满足均返回 satisfied=false 与可读原因
     */
    public static Result check(String requires, String platformVersion) {
        if (requires == null || requires.trim().isEmpty()) {
            return Result.reject("missing Plugin-Requires (expects '"
                    + PRODUCT + " >=x.y.z' per docs/05 §6)");
        }
        List<String[]> conditions = new ArrayList<String[]>();
        String[] tokens = requires.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (i == 0 && !token.startsWith(">") && !token.startsWith("<")
                    && !token.startsWith("=")) {
                // 首个 token 为产品名
                if (!PRODUCT.equals(token)) {
                    return Result.reject("unknown product '" + token + "' in Plugin-Requires '"
                            + requires + "' (expects '" + PRODUCT + "')");
                }
                continue;
            }
            Matcher m = CONDITION.matcher(token);
            if (!m.matches()) {
                return Result.reject("invalid Plugin-Requires '" + requires
                        + "': token '" + token
                        + "' must be (>=|<=|>|<|=)x.y.z, space-separated = AND");
            }
            conditions.add(new String[]{m.group(1), m.group(2)});
        }
        if (conditions.isEmpty()) {
            return Result.reject("invalid Plugin-Requires '" + requires
                    + "': no version constraint found");
        }
        long[] current = parse(platformVersion);
        StringBuilder required = new StringBuilder();
        for (String[] c : conditions) {
            if (required.length() > 0) {
                required.append(' ');
            }
            required.append(c[0]).append(c[1]);
            if (!satisfies(current, c[0], parse(c[1]))) {
                return Result.reject("requires " + PRODUCT + required + ", current="
                        + platformVersion);
            }
        }
        return Result.ok();
    }

    /** current op required（限定符不参与比较，数字段缺省补 0） */
    private static boolean satisfies(long[] current, String op, long[] required) {
        int cmp = compare(current, required);
        if (op.equals(">=")) {
            return cmp >= 0;
        }
        if (op.equals("<=")) {
            return cmp <= 0;
        }
        if (op.equals(">")) {
            return cmp > 0;
        }
        if (op.equals("<")) {
            return cmp < 0;
        }
        return cmp == 0; // "="
    }

    private static int compare(long[] a, long[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? a[i] : 0L;
            long y = i < b.length ? b[i] : 0L;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    /** "1.0.0-SNAPSHOT" → [1,0,0]；非法输入按 0 处理（fail-closed） */
    static long[] parse(String version) {
        if (version == null) {
            return new long[]{0L};
        }
        Matcher m = VERSION.matcher(version.trim());
        if (!m.matches()) {
            return new long[]{0L};
        }
        String[] parts = m.group(1).split("\\.");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0L;
            }
        }
        return out;
    }
}
