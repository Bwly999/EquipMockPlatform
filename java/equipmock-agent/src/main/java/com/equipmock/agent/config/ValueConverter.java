package com.equipmock.agent.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 类型转换器（03 §5，D12）：参数规范化串 + JSON 字面量 → 目标类型。
 *
 * <p>两条主线：
 * <ul>
 *   <li>{@link #normalize(Object)}（03 §5.1）：运行期实参 → 规范字符串，
 *       供 FULL_MATCH 的 POJO 比较 / PATTERN_MATCH 的正则匹配；截断上限 4096 字符。</li>
 *   <li>{@link #fromJson(JsonElement, Type)}（03 §5.2）：配置 JSON → 目标类型
 *       （8 种基本类型及包装含数字窄化、String、enum、byte[]/char[] 的 $hex/$b64、
 *       数组/List/Map、POJO 反射注入；其余类型抛 {@link ConversionException}）。
 *       调用方（匹配引擎）捕获后放行 REAL，绝不把 checkcast/转换异常漏给宿主。</li>
 * </ul>
 *
 * <p>嵌套 byte[]/char[] 的 gson 序列化注册为 {@code {"$hex":"..."}} 类型标签
 * （04 §3.2），使容器/POJO 内的字节数组在配置侧与运行期侧产生相同的规范串。
 */
public final class ValueConverter {

    /** 规范串截断上限（03 §5.1，防超长参数拖垮匹配） */
    public static final int MAX_CANONICAL_LENGTH = 4096;

    /** 规范化用 gson：byte[]/char[] → $hex 标签；其余走 gson 默认（枚举 name、Map key toString） */
    private static final Gson NORM_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(byte[].class, new JsonSerializer<byte[]>() {
                @Override
                public JsonElement serialize(byte[] src, Type typeOfSrc,
                                             JsonSerializationContext context) {
                    JsonObject tag = new JsonObject();
                    tag.addProperty("$hex", hexBytes(src));
                    return tag;
                }
            })
            .registerTypeAdapter(char[].class, new JsonSerializer<char[]>() {
                @Override
                public JsonElement serialize(char[] src, Type typeOfSrc,
                                             JsonSerializationContext context) {
                    JsonObject tag = new JsonObject();
                    tag.addProperty("$hex", hexChars(src));
                    return tag;
                }
            })
            .create();

    private ValueConverter() {
    }

    /** 类型转换失败（运行期命中该条 → 日志 + REAL，03 §5.2） */
    public static final class ConversionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConversionException(String message) {
            super(message);
        }

        public ConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // =========================================================================
    // 5.1 参数规范化字符串
    // =========================================================================

    /**
     * 运行期实参 → 规范字符串（03 §5.1 全表）。
     * <ul>
     *   <li>null → {@code null}；Boolean/Character/Number → String.valueOf；String → 原文</li>
     *   <li>byte[] → 大写 hex（A1B2…）；char[] → UTF-16 码元 4 位 hex（0041…）</li>
     *   <li>enum → name()</li>
     *   <li>数组/List/Map/POJO → gson 序列化 + key 排序（见 {@link #canonical}）</li>
     *   <li>其它（流/句柄/未知对象）→ {@code 类名@identityHash}</li>
     * </ul>
     * 超过 4096 字符截断。
     */
    public static String normalize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return truncate((String) value);
        }
        if (value instanceof Boolean || value instanceof Character || value instanceof Number) {
            return truncate(String.valueOf(value));
        }
        if (value instanceof byte[]) {
            return truncate(hexBytes((byte[]) value));
        }
        if (value instanceof char[]) {
            return truncate(hexChars((char[]) value));
        }
        if (value instanceof Enum) {
            return truncate(((Enum<?>) value).name());
        }
        boolean container = value instanceof List || value instanceof Map
                || value.getClass().isArray();
        try {
            JsonElement tree = NORM_GSON.toJsonTree(value);
            if (container) {
                return truncate(canonical(tree));
            }
            // POJO：gson 能产出非空对象才认为是数据对象；空对象（流/句柄）→ identityHash
            if (tree.isJsonObject() && tree.getAsJsonObject().size() > 0) {
                return truncate(canonical(tree));
            }
        } catch (Throwable ignored) {
            // 序列化失败（循环引用等）→ 落到 identityHash
        }
        return truncate(value.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(value)));
    }

    /**
     * JSON 元素 → 规范串（key 递归排序、空白紧凑），供配置侧与运行期侧比较。
     * 数字保留书写形式（gson LazilyParsedNumber 原样输出）。
     */
    static String canonical(JsonElement element) {
        StringBuilder sb = new StringBuilder(64);
        writeCanonical(element, sb, 0);
        return sb.toString();
    }

    private static void writeCanonical(JsonElement element, StringBuilder sb, int depth) {
        if (depth > 64) { // 巨型/循环引用防护（07 风险表）
            sb.append("...");
            return;
        }
        if (element == null || element.isJsonNull()) {
            sb.append("null");
            return;
        }
        if (element.isJsonPrimitive()) {
            sb.append(element.getAsJsonPrimitive().toString());
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            sb.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCanonical(array.get(i), sb, depth + 1);
            }
            sb.append(']');
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        TreeMap<String, JsonElement> sorted = new TreeMap<String, JsonElement>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            sorted.put(e.getKey(), e.getValue());
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonElement> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(NORM_GSON.toJson(e.getKey())); // 转义后的带引号 key
            sb.append(':');
            writeCanonical(e.getValue(), sb, depth + 1);
        }
        sb.append('}');
    }

    /** byte[] → 大写 hex（每字节 2 位） */
    public static String hexBytes(byte[] data) {
        if (data == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /** char[] → UTF-16 码元 hex（每码元 4 位，0041…） */
    public static String hexChars(char[] data) {
        if (data == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(data.length * 4);
        for (char c : data) {
            sb.append(String.format("%04X", (int) c));
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        return s.length() <= MAX_CANONICAL_LENGTH ? s : s.substring(0, MAX_CANONICAL_LENGTH);
    }

    // =========================================================================
    // 5.2 JSON 字面量 → 目标类型
    // =========================================================================

    /**
     * 配置 JSON → 目标类型。不支持/失败抛 {@link ConversionException}（调用方放行 REAL）。
     *
     * @param element 配置侧 JSON 字面量（可为 null = JsonNull）
     * @param targetType 目标类型（可含泛型：List&lt;Integer&gt;、Map&lt;String,Long&gt;、数组等）
     */
    public static Object fromJson(JsonElement element, Type targetType) {
        Class<?> raw = rawType(targetType);
        if (element == null || element.isJsonNull()) {
            if (raw.isPrimitive()) {
                throw new ConversionException("null 不可转换为基本类型 " + raw.getName());
            }
            return null;
        }
        // 基本类型及包装（含数字窄化 5.0 → int 5）
        if (raw == Boolean.class || raw == Boolean.TYPE) {
            requirePrimitive(element, "boolean");
            return Boolean.valueOf(element.getAsJsonPrimitive().getAsBoolean());
        }
        if (raw == Character.class || raw == Character.TYPE) {
            requirePrimitive(element, "char");
            String s = element.getAsJsonPrimitive().getAsString();
            if (s.length() != 1) {
                throw new ConversionException("char 目标需要长度 1 的字符串: '" + s + "'");
            }
            return Character.valueOf(s.charAt(0));
        }
        if (raw == Byte.class || raw == Byte.TYPE) {
            return Byte.valueOf((byte) number(element).byteValue());
        }
        if (raw == Short.class || raw == Short.TYPE) {
            return Short.valueOf((short) number(element).shortValue());
        }
        if (raw == Integer.class || raw == Integer.TYPE) {
            return Integer.valueOf(number(element).intValue());
        }
        if (raw == Long.class || raw == Long.TYPE) {
            return Long.valueOf(number(element).longValue());
        }
        if (raw == Float.class || raw == Float.TYPE) {
            return Float.valueOf(number(element).floatValue());
        }
        if (raw == Double.class || raw == Double.TYPE) {
            return Double.valueOf(number(element).doubleValue());
        }
        // String
        if (raw == String.class) {
            if (!element.isJsonPrimitive()) {
                throw new ConversionException("String 目标需要字面量: " + element);
            }
            return element.getAsJsonPrimitive().getAsString();
        }
        // enum（by name）
        if (raw.isEnum()) {
            if (!element.isJsonPrimitive()) {
                throw new ConversionException("enum 目标需要 name 字符串: " + element);
            }
            String name = element.getAsJsonPrimitive().getAsString();
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object value = Enum.valueOf((Class<? extends Enum>) raw.asSubclass(Enum.class), name);
                return value;
            } catch (IllegalArgumentException e) {
                throw new ConversionException("枚举 " + raw.getName() + " 无值 " + name, e);
            }
        }
        // byte[] / char[]（$hex/$b64 类型标签，04 §3.2；字面量数组兜底）
        if (raw == byte[].class) {
            return toByteArray(element);
        }
        if (raw == char[].class) {
            return toCharArray(element);
        }
        // 数组
        if (raw.isArray()) {
            return toArray(element, targetType);
        }
        // List / Map
        if (List.class.isAssignableFrom(raw)) {
            if (!element.isJsonArray()) {
                throw new ConversionException("List 目标需要 JSON 数组: " + element);
            }
            Type elementType = elementType(targetType, 0);
            JsonArray array = element.getAsJsonArray();
            List<Object> list = new ArrayList<Object>(array.size());
            for (JsonElement item : array) {
                list.add(elementType == null ? defaultMap(item) : fromJson(item, elementType));
            }
            return list;
        }
        if (Map.class.isAssignableFrom(raw)) {
            if (!element.isJsonObject()) {
                throw new ConversionException("Map 目标需要 JSON 对象: " + element);
            }
            Type valueType = elementType(targetType, 1);
            Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                map.put(e.getKey(), valueType == null ? defaultMap(e.getValue())
                        : fromJson(e.getValue(), valueType));
            }
            return map;
        }
        // POJO（公有无参构造 + 字段注入递归；无无参构造时回退全参构造按序注入）
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
            throw new ConversionException("不支持的类型（接口/抽象类）: " + raw.getName());
        }
        return toPojo(element.getAsJsonObject(), raw);
    }

    private static void requirePrimitive(JsonElement element, String kind) {
        if (!element.isJsonPrimitive()) {
            throw new ConversionException(kind + " 目标需要字面量: " + element);
        }
    }

    private static Number number(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ConversionException("数字目标需要数字字面量: " + element);
        }
        try {
            return element.getAsJsonPrimitive().getAsNumber();
        } catch (JsonSyntaxException e) {
            throw new ConversionException("数字字面量越界: " + element, e);
        }
    }

    /** byte[]：{"$hex"}/{"$b64"} 标签或数字数组 */
    private static byte[] toByteArray(JsonElement element) {
        byte[] tagged = taggedBytes(element);
        if (tagged != null) {
            return tagged;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            byte[] out = new byte[array.size()];
            for (int i = 0; i < array.size(); i++) {
                out[i] = (byte) number(array.get(i)).byteValue();
            }
            return out;
        }
        throw new ConversionException("byte[] 目标需要 $hex/$b64 对象或数字数组: " + element);
    }

    /** char[]：{"$hex":"0041…"}（UTF-16 码元）或字符串/数字数组 */
    private static char[] toCharArray(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            if (o.has("$hex") && o.size() == 1) {
                String hex = o.get("$hex").getAsString();
                if (hex.length() % 4 != 0 || !hex.matches("[0-9A-Fa-f]*")) {
                    throw new ConversionException("char[] $hex 需为每码元 4 位的 hex: " + hex);
                }
                char[] out = new char[hex.length() / 4];
                for (int i = 0; i < out.length; i++) {
                    out[i] = (char) Integer.parseInt(hex.substring(i * 4, i * 4 + 4), 16);
                }
                return out;
            }
            throw new ConversionException("char[] 目标仅支持 $hex 标签对象: " + element);
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            char[] out = new char[array.size()];
            for (int i = 0; i < array.size(); i++) {
                JsonElement e = array.get(i);
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
                    out[i] = (char) e.getAsJsonPrimitive().getAsNumber().intValue();
                } else {
                    String s = e.getAsJsonPrimitive().getAsString();
                    if (s.length() != 1) {
                        throw new ConversionException("char[] 元素需单字符: '" + s + "'");
                    }
                    out[i] = s.charAt(0);
                }
            }
            return out;
        }
        throw new ConversionException("char[] 目标需要 $hex 对象或数组: " + element);
    }

    /** {"$hex"}/{"$b64"} → byte[]；非标签结构返回 null */
    static byte[] taggedBytes(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject o = element.getAsJsonObject();
        try {
            if (o.has("$hex") && o.size() == 1) {
                String hex = o.get("$hex").getAsString();
                if (hex.length() % 2 != 0 || !hex.matches("[0-9A-Fa-f]*")) {
                    throw new ConversionException("$hex 需为偶数位 hex: " + hex);
                }
                byte[] out = new byte[hex.length() / 2];
                for (int i = 0; i < out.length; i++) {
                    out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
                return out;
            }
            if (o.has("$b64") && o.size() == 1) {
                return Base64.getDecoder().decode(o.get("$b64").getAsString());
            }
        } catch (ConversionException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ConversionException("$b64 解码失败: " + e.getMessage(), e);
        }
        return null;
    }

    /** 数组转换（元素类型从 Method 泛型签名取，03 §5.2） */
    private static Object toArray(JsonElement element, Type targetType) {
        if (!element.isJsonArray()) {
            throw new ConversionException("数组目标需要 JSON 数组: " + element);
        }
        Class<?> raw = rawType(targetType);
        Type componentType;
        if (targetType instanceof GenericArrayType) {
            componentType = ((GenericArrayType) targetType).getGenericComponentType();
        } else {
            componentType = raw.getComponentType();
        }
        JsonArray array = element.getAsJsonArray();
        Object out = java.lang.reflect.Array.newInstance(raw.getComponentType(), array.size());
        for (int i = 0; i < array.size(); i++) {
            Object item = fromJson(array.get(i), componentType);
            if (item == null && raw.getComponentType().isPrimitive()) {
                throw new ConversionException("基本类型数组元素不可为 null: index " + i);
            }
            java.lang.reflect.Array.set(out, i, item);
        }
        return out;
    }

    /** POJO：a) 公有无参构造 + 按字段注入（递归）；b) 回退：参数个数 = 字段数的公共全参构造按序注入 */
    private static Object toPojo(JsonObject object, Class<?> raw) {
        if (!object.isJsonObject()) {
            throw new ConversionException("POJO 目标需要 JSON 对象: " + raw.getName());
        }
        try {
            Constructor<?> noArg = null;
            for (Constructor<?> c : raw.getConstructors()) {
                if (c.getParameterTypes().length == 0) {
                    noArg = c;
                    break;
                }
            }
            if (noArg != null) {
                Object instance = noArg.newInstance();
                for (Map.Entry<String, JsonElement> e : object.entrySet()) {
                    Field field = findField(raw, e.getKey());
                    if (field == null || Modifier.isStatic(field.getModifiers())
                            || Modifier.isTransient(field.getModifiers())
                            || Modifier.isFinal(field.getModifiers())) {
                        continue; // 未知/不可写字段跳过（宽松）
                    }
                    field.setAccessible(true);
                    field.set(instance, fromJson(e.getValue(), field.getGenericType()));
                }
                return instance;
            }
            // 回退：全参构造（按 JSON 成员出现顺序，字段顺序由声明序约定）
            Constructor<?>[] ctors = raw.getConstructors();
            java.util.Arrays.sort(ctors, new java.util.Comparator<Constructor<?>>() {
                @Override
                public int compare(Constructor<?> a, Constructor<?> b) {
                    return Integer.compare(b.getParameterTypes().length,
                            a.getParameterTypes().length);
                }
            });
            List<JsonElement> values = new ArrayList<JsonElement>();
            for (Map.Entry<String, JsonElement> e : object.entrySet()) {
                values.add(e.getValue());
            }
            for (Constructor<?> ctor : ctors) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length != values.size() || params.length == 0) {
                    continue;
                }
                try {
                    Object[] args = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        args[i] = fromJson(values.get(i), params[i]);
                    }
                    return ctor.newInstance(args);
                } catch (ConversionException skipToNext) {
                    // 参数类型不匹配 → 尝试下一个构造
                }
            }
            throw new ConversionException("POJO " + raw.getName()
                    + " 无可用构造（公有无参或与字段数一致的全参）");
        } catch (ConversionException e) {
            throw e;
        } catch (Throwable t) {
            throw new ConversionException("POJO " + raw.getName() + " 构造失败: " + t, t);
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 沿继承链继续
            }
        }
        return null;
    }

    /** 泛型容器（List/Map）的元素类型；非参数化或类型变量 → null（用默认映射） */
    private static Type elementType(Type containerType, int index) {
        if (containerType instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) containerType).getActualTypeArguments();
            if (index < args.length && !(args[index] instanceof java.lang.reflect.TypeVariable)) {
                return args[index];
            }
        }
        return null;
    }

    /** 无元素类型信息时的默认 Java 映射：对象→LinkedHashMap、数组→ArrayList、数字→Double */
    private static Object defaultMap(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive p = element.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return Boolean.valueOf(p.getAsBoolean());
            }
            if (p.isNumber()) {
                return Double.valueOf(p.getAsDouble());
            }
            return p.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<Object>();
            for (JsonElement e : element.getAsJsonArray()) {
                list.add(defaultMap(e));
            }
            return list;
        }
        Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            map.put(e.getKey(), defaultMap(e.getValue()));
        }
        return map;
    }

    /** Type → 原始 Class（剥离泛型/通配/泛型数组） */
    public static Class<?> rawType(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return rawType(((ParameterizedType) type).getRawType());
        }
        if (type instanceof GenericArrayType) {
            Class<?> component = rawType(((GenericArrayType) type).getGenericComponentType());
            return java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        if (type instanceof WildcardType) {
            WildcardType wildcard = (WildcardType) type;
            return wildcard.getUpperBounds().length > 0
                    ? rawType(wildcard.getUpperBounds()[0]) : Object.class;
        }
        if (type instanceof java.lang.reflect.TypeVariable) {
            return Object.class;
        }
        throw new ConversionException("无法解析的类型: " + type);
    }

    /** 便捷：Set 等特殊 gson Token（保留 API 完整性） */
    @SuppressWarnings("unused")
    private static TypeToken<?> token(Type type) {
        return TypeToken.get(type);
    }
}
