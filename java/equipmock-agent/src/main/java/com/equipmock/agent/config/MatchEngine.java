package com.equipmock.agent.config;

import com.equipmock.bootstrap.MockResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 匹配引擎（03 §4 ConfigDrivenHandler.decide + §4.3 Action 执行）。
 *
 * <p>流程：index.lookup（精确签名优先、无签名条目兜底）→ 逐 entry 的 rules
 * first-match（FULL_MATCH 深度相等 / PATTERN_MATCH 逐参预编译正则 matches()）→
 * defaultAction 兜底 → null=REAL。
 *
 * <p>Action 执行：
 * <ul>
 *   <li>VALUE：按目标返回类型经 {@link ValueConverter} 反序列化并装箱——<b>必须在产出
 *       MockResult 前完成</b>，转换失败/类型不符 → 日志 + lastError + REAL，
 *       绝不让 checkcast 异常打断宿主（M1 报告遗留约束）；void 方法配 VALUE=运行期
 *       签名错配 → 日志 + REAL。</li>
 *   <li>THROW：反射实例化，优先 String 构造传 message，其次无参构造；
 *       失败 → 日志 + REAL（类可能仅宿主可见，加载期不报）。</li>
 *   <li>VOID：仅 void 方法有效；非 void 方法配 VOID → 日志 + REAL。</li>
 * </ul>
 *
 * <p>返回类型解析：优先反射宿主类 Method（取泛型返回类型，List/Map 元素类型由此而来），
 * 首次命中解析并缓存；类不可见时按 descriptor 兜底解析（无泛型信息）。
 */
public final class MatchEngine {

    /** 运行期错误上报（写 state.lastError；file=来源小分组文件） */
    public interface ErrorReporter {
        void report(String file, String message);
    }

    private final Logger log;
    private final ErrorReporter errorReporter;
    /** methodId → 泛型返回类型（含 List/Map 元素类型）缓存 */
    private final ConcurrentHashMap<String, Type> returnTypeCache =
            new ConcurrentHashMap<String, Type>();
    /** methodId → 是否 void 返回 */
    private final ConcurrentHashMap<String, Boolean> voidCache =
            new ConcurrentHashMap<String, Boolean>();

    public MatchEngine(Logger log, ErrorReporter errorReporter) {
        this.log = log;
        this.errorReporter = errorReporter;
    }

    /**
     * 决策一次拦截调用。
     *
     * @return MockResult；null = REAL（放行真实方法）
     */
    public MockResult decide(GroupSnapshot snapshot, String className, String methodName,
                             String descriptor, Object[] args) {
        if (snapshot == null) {
            return null;
        }
        String methodId = className + "#" + methodName + "#" + descriptor;
        try {
            List<MockEntry> entries = snapshot.index().lookup(className, methodName, descriptor);
            if (entries.isEmpty()) {
                return null;
            }
            Object[] safeArgs = args == null ? new Object[0] : args;
            for (MockEntry entry : entries) {
                for (MockRule rule : entry.rules) {
                    boolean hit = rule.matchType == MockRule.MatchType.FULL_MATCH
                            ? fullMatch(rule.args, safeArgs)
                            : patternMatch(rule.patterns, safeArgs);
                    if (hit) {
                        return executeAction(rule.action, entry, methodId, descriptor);
                    }
                }
                if (entry.defaultAction != null) {
                    return executeAction(entry.defaultAction, entry, methodId, descriptor);
                }
            }
            return null;
        } catch (Throwable t) {
            log.warning("match engine error on " + methodId + ", falling back to REAL: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // FULL_MATCH：逐参深度相等（03 §4）
    // ------------------------------------------------------------------

    private boolean fullMatch(List<JsonElement> ruleArgs, Object[] actual) {
        if (ruleArgs.size() != actual.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (!deepEqual(ruleArgs.get(i), actual[i])) {
                return false;
            }
        }
        return true;
    }

    /** 配置 JSON 元素 vs 运行期实参：深度相等 */
    static boolean deepEqual(JsonElement config, Object actual) {
        if (config == null || config.isJsonNull()) {
            return actual == null;
        }
        if (actual == null) {
            return false;
        }
        if (config.isJsonPrimitive()) {
            JsonPrimitive p = config.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return actual instanceof Boolean
                        && ((Boolean) actual).booleanValue() == p.getAsBoolean();
            }
            if (p.isNumber()) {
                return actual instanceof Number
                        && numberEquals(p.getAsNumber(), (Number) actual);
            }
            if (p.isString()) {
                String s = p.getAsString();
                if (actual instanceof String) {
                    return s.equals(actual);
                }
                if (actual instanceof Character) {
                    return s.length() == 1 && s.charAt(0) == (Character) actual;
                }
                if (actual instanceof Enum) {
                    return s.equals(((Enum<?>) actual).name());
                }
                return false;
            }
            return false;
        }
        if (config.isJsonObject()) {
            JsonObject o = config.getAsJsonObject();
            // $hex/$b64 类型标签 → byte[]/char[] 逐字节比较
            if (o.size() == 1 && (o.has("$hex") || o.has("$b64"))) {
                try {
                    byte[] expected = ValueConverter.taggedBytes(o);
                    if (expected != null && actual instanceof byte[]) {
                        return Arrays.equals(expected, (byte[]) actual);
                    }
                } catch (ValueConverter.ConversionException invalidTag) {
                    return false; // 标签非法 → 不匹配（加载期已拦，双保险）
                }
                if (actual instanceof char[]) {
                    try {
                        char[] expectedChars = (char[]) ValueConverter.fromJson(o, char[].class);
                        return Arrays.equals(expectedChars, (char[]) actual);
                    } catch (ValueConverter.ConversionException e) {
                        return false;
                    }
                }
                return false;
            }
            if (actual instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) actual;
                if (map.size() != o.size()) {
                    return false;
                }
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    Object v = map.get(e.getKey());
                    if (v == null && !map.containsKey(e.getKey())) {
                        return false;
                    }
                    if (!deepEqual(e.getValue(), v)) {
                        return false;
                    }
                }
                return true;
            }
            // POJO：走 §5 序列化串比较（gson + key 排序，两侧同一 canonical 形式）
            String actualCanon = ValueConverter.normalize(actual);
            String configCanon = ValueConverter.canonical(o);
            return configCanon.equals(actualCanon);
        }
        if (config.isJsonArray()) {
            JsonArray array = config.getAsJsonArray();
            if (actual instanceof List) {
                List<?> list = (List<?>) actual;
                if (list.size() != array.size()) {
                    return false;
                }
                for (int i = 0; i < list.size(); i++) {
                    if (!deepEqual(array.get(i), list.get(i))) {
                        return false;
                    }
                }
                return true;
            }
            if (actual.getClass().isArray()) {
                int len = Array.getLength(actual);
                if (len != array.size()) {
                    return false;
                }
                for (int i = 0; i < len; i++) {
                    if (!deepEqual(array.get(i), Array.get(actual, i))) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }
        return false;
    }

    /** 数值按值相等（1 == 1.0 == 1e0；BigDecimal compareTo 语义） */
    private static boolean numberEquals(Number a, Number b) {
        try {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        } catch (NumberFormatException e) {
            // NaN/Infinity
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
    }

    // ------------------------------------------------------------------
    // PATTERN_MATCH：逐参预编译正则 matches()（03 §4）
    // ------------------------------------------------------------------

    private boolean patternMatch(List<java.util.regex.Pattern> patterns, Object[] actual) {
        if (patterns.size() != actual.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            String canonical = ValueConverter.normalize(actual[i]);
            if (!patterns.get(i).matcher(canonical).matches()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Action 执行（03 §4.3）
    // ------------------------------------------------------------------

    private MockResult executeAction(ActionDef action, MockEntry entry,
                                     String methodId, String descriptor) {
        boolean voidMethod = isVoidMethod(methodId, descriptor);
        switch (action.type) {
            case VALUE:
                if (voidMethod) {
                    return runtimeMismatch(entry, methodId,
                            "VALUE 配置在 void 方法上（运行期签名错配），已放行 REAL");
                }
                Type returnType = resolveReturnType(methodId);
                try {
                    Object value = ValueConverter.fromJson(action.value, returnType);
                    Class<?> raw = ValueConverter.rawType(returnType);
                    if (value != null && !raw.isPrimitive() && !raw.isInstance(value)) {
                        throw new ValueConverter.ConversionException(
                                "转换结果 " + value.getClass().getName()
                                        + " 不是目标返回类型 " + raw.getName());
                    }
                    if (value != null && raw.isPrimitive()) {
                        // 装箱类必须精确匹配（int ← Integer），防 advice 拆箱异常
                        Class<?> boxed = boxedType(raw);
                        if (!boxed.isInstance(value)) {
                            throw new ValueConverter.ConversionException(
                                    "基本类型 " + raw.getName() + " 需要装箱值 "
                                            + boxed.getName() + "，实际 "
                                            + value.getClass().getName());
                        }
                    }
                    return new MockResult(MockResult.VALUE, value, null);
                } catch (ValueConverter.ConversionException e) {
                    return runtimeMismatch(entry, methodId,
                            "VALUE 类型转换失败，已放行 REAL: " + e.getMessage());
                }
            case THROW:
                Throwable throwable = instantiateThrowable(action.exception, action.message);
                if (throwable == null) {
                    return runtimeMismatch(entry, methodId,
                            "THROW 实例化失败，已放行 REAL: " + action.exception);
                }
                return new MockResult(MockResult.THROW, null, throwable);
            case VOID:
            default:
                if (!voidMethod) {
                    return runtimeMismatch(entry, methodId,
                            "VOID 配置在非 void 方法上（运行期签名错配），已放行 REAL");
                }
                return new MockResult(MockResult.VOID, null, null);
        }
    }

    /** 运行期错配/失败：日志 + lastError + REAL（null） */
    private MockResult runtimeMismatch(MockEntry entry, String methodId, String message) {
        log.warning(methodId + ": " + message);
        if (errorReporter != null) {
            errorReporter.report(entry.sourceFile, message + " [" + methodId + "]");
        }
        return null;
    }

    /** 反射实例化异常：优先 String 构造传 message，其次无参构造 */
    private Throwable instantiateThrowable(String fqcn, String message) {
        try {
            Class<?> clazz = Class.forName(fqcn);
            if (!Throwable.class.isAssignableFrom(clazz)) {
                log.warning("THROW 目标不是 Throwable: " + fqcn);
                return null;
            }
            if (message != null) {
                try {
                    Constructor<?> ctor = clazz.getConstructor(String.class);
                    return (Throwable) ctor.newInstance(message);
                } catch (NoSuchMethodException noStringCtor) {
                    // 落到无参构造
                }
            }
            return (Throwable) clazz.newInstance();
        } catch (Throwable t) {
            log.warning("THROW 实例化失败 " + fqcn + ": " + t);
            return null;
        }
    }

    private boolean isVoidMethod(String methodId, String descriptor) {
        Boolean cached = voidCache.get(methodId);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean voidMethod = descriptor != null
                && descriptor.substring(descriptor.lastIndexOf(')') + 1).equals("V");
        voidCache.put(methodId, Boolean.valueOf(voidMethod));
        return voidMethod;
    }

    /** 返回类型：反射宿主 Method（泛型）→ descriptor 兜底；首次解析后缓存 */
    private Type resolveReturnType(String methodId) {
        Type cached = returnTypeCache.get(methodId);
        if (cached != null) {
            return cached;
        }
        int first = methodId.indexOf('#');
        int second = methodId.indexOf('#', first + 1);
        String className = methodId.substring(0, first);
        String methodName = methodId.substring(first + 1, second);
        String descriptor = methodId.substring(second + 1);
        Type resolved = null;
        try {
            Class<?> clazz = Class.forName(className);
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)
                        && descriptorOf(m).equals(descriptor)) {
                    resolved = m.getGenericReturnType();
                    break;
                }
            }
            if (resolved == null) {
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(methodName)
                            && descriptorOf(m).equals(descriptor)) {
                        resolved = m.getGenericReturnType();
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 类在宿主 classpath 不可见（延迟解析失败）→ descriptor 兜底
        }
        if (resolved == null) {
            resolved = descriptorReturnType(descriptor);
        }
        returnTypeCache.put(methodId, resolved);
        return resolved;
    }

    /** Method → JVM descriptor（缓存判定用） */
    static String descriptorOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(classDescriptor(p));
        }
        sb.append(')').append(classDescriptor(m.getReturnType()));
        return sb.toString();
    }

    private static String classDescriptor(Class<?> c) {
        if (c == void.class) {
            return "V";
        }
        if (c == boolean.class) {
            return "Z";
        }
        if (c == byte.class) {
            return "B";
        }
        if (c == char.class) {
            return "C";
        }
        if (c == short.class) {
            return "S";
        }
        if (c == int.class) {
            return "I";
        }
        if (c == long.class) {
            return "J";
        }
        if (c == float.class) {
            return "F";
        }
        if (c == double.class) {
            return "D";
        }
        if (c.isArray()) {
            return "[" + classDescriptor(c.getComponentType());
        }
        return "L" + c.getName().replace('.', '/') + ";";
    }

    /** descriptor 返回段 → Class（无泛型信息的兜底；类不可见退化为 Object） */
    static Class<?> descriptorReturnType(String descriptor) {
        String ret = descriptor.substring(descriptor.lastIndexOf(')') + 1);
        return descriptorToClass(ret);
    }

    private static Class<?> descriptorToClass(String d) {
        char c = d.charAt(0);
        switch (c) {
            case 'V': return void.class;
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'S': return short.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case '[':
                int dims = 0;
                while (dims < d.length() && d.charAt(dims) == '[') {
                    dims++;
                }
                Class<?> component = descriptorToClass(d.substring(dims));
                return Array.newInstance(component, new int[dims]).getClass();
            case 'L':
                String name = d.substring(1, d.length() - 1).replace('/', '.');
                try {
                    return Class.forName(name);
                } catch (ClassNotFoundException e) {
                    return Object.class; // 宿主类不可见：转换大概率失败 → REAL
                }
            default:
                return Object.class;
        }
    }

    private static Class<?> boxedType(Class<?> primitive) {
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        if (primitive == char.class) {
            return Character.class;
        }
        return Object.class;
    }
}
