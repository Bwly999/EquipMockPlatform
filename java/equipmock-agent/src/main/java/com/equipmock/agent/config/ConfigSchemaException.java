package com.equipmock.agent.config;

import java.nio.file.Path;

/**
 * 配置加载/校验失败（03 §6）：携带文件、字段路径、行列号（语法错误时）与原因。
 * message 形如 {@code config/groups/default/cabinet.json: rules[1].argsPattern[0]
 * 非法正则: *** (line 3, column 18)}。
 */
public final class ConfigSchemaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 相对 home 的文件路径（写 state.lastError.file） */
    public final String file;
    /** 字段路径（如 mocks[0].rules[1].argsPattern[0]） */
    public final String fieldPath;
    /** 行号（-1=未知，语法错误时尽量提取） */
    public final int line;
    /** 列号（-1=未知） */
    public final int column;

    public ConfigSchemaException(String file, String fieldPath, String detail) {
        this(file, fieldPath, detail, -1, -1, null);
    }

    public ConfigSchemaException(String file, String fieldPath, String detail, Throwable cause) {
        this(file, fieldPath, detail, -1, -1, cause);
    }

    public ConfigSchemaException(String file, String fieldPath, String detail,
                                 int line, int column, Throwable cause) {
        super(buildMessage(file, fieldPath, detail, line, column), cause);
        this.file = file;
        this.fieldPath = fieldPath;
        this.line = line;
        this.column = column;
    }

    private static String buildMessage(String file, String fieldPath, String detail,
                                       int line, int column) {
        StringBuilder sb = new StringBuilder();
        sb.append(file == null ? "<unknown>" : file).append(": ");
        if (fieldPath != null && !fieldPath.isEmpty()) {
            sb.append(fieldPath).append(' ');
        }
        sb.append(detail);
        if (line > 0) {
            sb.append(" (line ").append(line).append(", column ").append(column).append(')');
        }
        return sb.toString();
    }

    /** 从 gson 异常消息中提取 line/column（"line 3 column 18" 形式） */
    public static int[] extractLineColumn(String message) {
        if (message == null) {
            return new int[]{-1, -1};
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("line (\\d+)(?: column|, column) (\\d+)")
                .matcher(message);
        if (m.find()) {
            try {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            } catch (NumberFormatException ignored) {
                // 兜底 -1
            }
        }
        return new int[]{-1, -1};
    }

    /** 便捷：语法错误（携带 gson 消息中的行列号） */
    public static ConfigSchemaException syntax(String file, String rawMessage, Throwable cause) {
        int[] lc = extractLineColumn(rawMessage);
        return new ConfigSchemaException(file, "", "JSON 语法错误: " + rawMessage,
                lc[0], lc[1], cause);
    }

    /** 便捷：字段级校验错误 */
    public static ConfigSchemaException field(String file, String fieldPath, String detail) {
        return new ConfigSchemaException(file, fieldPath, detail);
    }

    /** 仅供 Path 显示归一（正斜杠，便于日志/state 统一） */
    public static String displayPath(Path home, Path file) {
        String p = file.toString().replace('\\', '/');
        String h = home.toString().replace('\\', '/');
        if (p.startsWith(h + "/")) {
            return p.substring(h.length() + 1);
        }
        return p;
    }
}
