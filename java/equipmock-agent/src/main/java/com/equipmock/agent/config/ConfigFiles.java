package com.equipmock.agent.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置文件读取公共工具：UTF-8 读取 + 语法解析（错误含行列号）+
 * 临时文件过滤（*.tmp* / *.bak，04 §7）+ 文件名自然序比较。
 */
public final class ConfigFiles {

    private ConfigFiles() {
    }

    /** 写方临时文件协议过滤（03 §7 / 04 §5）：忽略 *.tmp* 与 *.bak */
    public static boolean isTempOrBackup(String fileName) {
        return fileName.contains(".tmp") || fileName.endsWith(".bak");
    }

    /** 文件名（不含扩展）是否为配置契约文件 */
    public static boolean isJsonConfig(String fileName) {
        return fileName.endsWith(".json");
    }

    /** 读取并解析 JSON 对象；语法错误抛 ConfigSchemaException（含行列号） */
    public static JsonObject parseObject(Path file, String displayFile) {
        JsonElement root;
        try {
            BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
            try {
                root = new JsonParser().parse(reader);
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw ConfigSchemaException.field(displayFile, "",
                    "读取失败: " + e.getMessage());
        } catch (RuntimeException e) {
            // gson JsonSyntaxException / JsonParseException
            throw ConfigSchemaException.syntax(displayFile, String.valueOf(e.getMessage()), e);
        }
        if (root == null || !root.isJsonObject()) {
            throw ConfigSchemaException.field(displayFile, "", "顶层必须是 JSON 对象");
        }
        return root.getAsJsonObject();
    }

    /**
     * 文件名自然序比较（03 §1 合并序）：数字段按数值比较（a2 &lt; a10），其余按字典序。
     */
    public static int naturalCompare(String a, String b) {
        int ia = 0;
        int ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia);
            char cb = b.charAt(ib);
            boolean da = Character.isDigit(ca);
            boolean db = Character.isDigit(cb);
            if (da && db) {
                int ja = ia;
                while (ja < a.length() && Character.isDigit(a.charAt(ja))) {
                    ja++;
                }
                int jb = ib;
                while (jb < b.length() && Character.isDigit(b.charAt(jb))) {
                    jb++;
                }
                String na = a.substring(ia, ja);
                String nb = b.substring(ib, jb);
                int cmp = compareNumeric(na, nb);
                if (cmp != 0) {
                    return cmp;
                }
                ia = ja;
                ib = jb;
            } else if (da != db) {
                return da ? 1 : -1; // 非数字段在前（a.json &lt; a2.json）
            } else {
                if (ca != cb) {
                    return ca - cb;
                }
                ia++;
                ib++;
            }
        }
        return (a.length() - ia) - (b.length() - ib);
    }

    private static int compareNumeric(String a, String b) {
        String sa = stripLeadingZeros(a);
        String sb = stripLeadingZeros(b);
        if (sa.length() != sb.length()) {
            return sa.length() - sb.length();
        }
        return sa.compareTo(sb);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    /** 去掉 .json 扩展名 */
    public static String baseName(String fileName) {
        return fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - 5) : fileName;
    }
}
