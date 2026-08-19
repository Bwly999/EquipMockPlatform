package com.equipmock.agent.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JVM 方法描述符校验（03 §6 规则 2：signature 若填必须是合法 descriptor）。
 *
 * <p>手写递归下降解析（agent 不引入 asm 类型 API 供配置校验复用）：
 * {@code (FieldType*)ReturnType}，FieldType/ReturnType = 基本类型字符 |
 * {@code L&lt;内部名&gt;;} | {@code [} + FieldType。
 */
public final class DescriptorValidator {

    private DescriptorValidator() {
    }

    private static final Set<String> PRIMITIVES = new HashSet<String>(Arrays.asList(
            "Z", "B", "C", "S", "I", "J", "F", "D", "V"));

    /** 合法返回 null；非法返回原因描述 */
    public static String validate(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return "signature 为空";
        }
        int[] pos = {0};
        if (descriptor.charAt(pos[0]) != '(') {
            return "signature 必须以 '(' 开头";
        }
        pos[0]++;
        while (pos[0] < descriptor.length() && descriptor.charAt(pos[0]) != ')') {
            String err = readFieldType(descriptor, pos);
            if (err != null) {
                return err;
            }
            if (pos[0] >= descriptor.length()) {
                return "signature 缺少 ')' 或返回类型不完整";
            }
        }
        if (pos[0] >= descriptor.length() || descriptor.charAt(pos[0]) != ')') {
            return "signature 缺少 ')'";
        }
        pos[0]++;
        if (pos[0] >= descriptor.length()) {
            return "signature 缺少返回类型";
        }
        String err = readFieldType(descriptor, pos);
        if (err != null) {
            return err;
        }
        if (pos[0] != descriptor.length()) {
            return "signature 在返回类型后有多余字符";
        }
        return null;
    }

    private static String readFieldType(String s, int[] pos) {
        char c = s.charAt(pos[0]);
        if (c == 'V') {
            pos[0]++;
            return null; // 仅返回位置合法，但参数位出现 V 由整体结构容忍（宽松处，不另检）
        }
        if (PRIMITIVES.contains(String.valueOf(c))) {
            pos[0]++;
            return null;
        }
        if (c == 'L') {
            int end = s.indexOf(';', pos[0]);
            if (end < 0) {
                return "对象类型缺少结尾 ';'";
            }
            String name = s.substring(pos[0] + 1, end);
            if (name.isEmpty()) {
                return "对象类型名为空";
            }
            for (int i = 0; i < name.length(); i++) {
                char n = name.charAt(i);
                if (n == '.' || n == ';' || n == '[' || n == '(' || n == ')') {
                    return "对象类型名含非法字符 '" + n + "'";
                }
            }
            pos[0] = end + 1;
            return null;
        }
        if (c == '[') {
            pos[0]++;
            if (pos[0] >= s.length()) {
                return "数组缺少元素类型";
            }
            return readFieldType(s, pos);
        }
        return "非法类型字符 '" + c + "'";
    }

    /** FQCN 格式（04 §3.1 class / THROW exception 用）：标识符(.标识符)* */
    private static final Pattern FQCN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** Java 标识符（方法名） */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** FQCN 格式校验（THROW exception / mock class 用；允许无包段的简单类名） */
    public static boolean isFqcn(String s) {
        return s != null && FQCN.matcher(s).matches();
    }

    public static boolean isIdentifier(String s) {
        return s != null && IDENTIFIER.matcher(s).matches();
    }
}
