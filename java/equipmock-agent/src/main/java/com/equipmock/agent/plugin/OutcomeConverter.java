package com.equipmock.agent.plugin;

import com.equipmock.agent.config.ValueConverter;
import com.equipmock.bootstrap.MockResult;
import com.equipmock.api.MockOutcome;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * {@link MockOutcome} → {@link MockResult} 转换（02 §5.2 第 3/4 步）。
 *
 * <ul>
 *   <li>THROW：直接携带插件给定异常对象；</li>
 *   <li>VALUE：按目标方法返回类型做 isInstance 守卫/装箱校验；类型不符时经
 *       gson JsonTree 走 M2 {@link ValueConverter}（List/Map/POJO 等复用同一
 *       转换器）；转换失败/void 方法配 VALUE → 日志 + REAL（03 §4.3 同语义）；</li>
 *   <li>VOID：仅 void 方法有效，非 void → 日志 + REAL。</li>
 * </ul>
 *
 * <p>返回类型解析参照 {@code MatchEngine}：优先反射宿主类 Method（Class.forName，
 * agent 位于 system classpath，宿主类可见），失败按 descriptor 兜底。
 */
final class OutcomeConverter {

    private static final Gson GSON = new Gson();

    /** methodCache 的"解析失败"哨兵（ConcurrentHashMap 不接受 null 值） */
    private static final Method UNRESOLVED;

    static {
        Method sentinel;
        try {
            // Object 声明了 toString，必存在；仅作非 null 哨兵，不用于反射调用
            sentinel = Object.class.getDeclaredMethod("toString");
        } catch (NoSuchMethodException e) {
            sentinel = null; // 不可能发生
        }
        UNRESOLVED = sentinel;
    }

    private final Logger log;

    /** methodId → 目标方法（UNRESOLVED=解析失败） */
    private final ConcurrentHashMap<String, Method> methodCache =
            new ConcurrentHashMap<String, Method>();
    /** methodId → 返回类型（Class 形式；void 为 void.class） */
    private final ConcurrentHashMap<String, Class<?>> returnTypeCache =
            new ConcurrentHashMap<String, Class<?>>();

    OutcomeConverter(Logger log) {
        this.log = log;
    }

    /** 目标 Method（invocation.reflectedMethod 用）；解析失败返回 null */
    Method resolveMethod(String className, String methodName, String descriptor) {
        String key = className + "#" + methodName + "#" + descriptor;
        Method cached = methodCache.get(key);
        if (cached != null) {
            return cached == UNRESOLVED ? null : cached;
        }
        Method resolved = null;
        try {
            Class<?> clazz = Class.forName(className);
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)
                        && descriptorOf(m).equals(descriptor)) {
                    resolved = m;
                    break;
                }
            }
            if (resolved == null) {
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(methodName)
                            && descriptorOf(m).equals(descriptor)) {
                        resolved = m;
                        break;
                    }
                }
            }
            if (resolved != null) {
                resolved.setAccessible(true);
            }
        } catch (Throwable ignored) {
            // 宿主类不可见（理论上不会发生，agent 在 system classpath）→ null
        }
        methodCache.put(key, resolved == null ? UNRESOLVED : resolved);
        return resolved;
    }

    /**
     * 插件 Outcome → MockResult。
     *
     * @return null = 放行 REAL（转换失败/签名错配，原因已记日志）
     */
    MockResult convert(MockOutcome outcome, String className, String methodName,
                       String descriptor) {
        String methodId = className + "#" + methodName + "#" + descriptor;
        switch (outcome.getType()) {
            case THROW:
                return new MockResult(MockResult.THROW, null, outcome.getThrowable());
            case VOID:
                if (!isVoid(methodId, descriptor)) {
                    log.warning("plugin outcome VOID on non-void method " + methodId
                            + ", falling back to REAL");
                    return null;
                }
                return new MockResult(MockResult.VOID, null, null);
            case VALUE:
            default:
                return convertValue(outcome.getValue(), methodId, descriptor);
        }
    }

    private MockResult convertValue(Object value, String methodId, String descriptor) {
        if (isVoid(methodId, descriptor)) {
            log.warning("plugin outcome VALUE on void method " + methodId
                    + ", falling back to REAL");
            return null;
        }
        Class<?> returnType = returnType(methodId, descriptor);
        try {
            if (value == null) {
                if (returnType.isPrimitive()) {
                    log.warning("plugin outcome VALUE null on primitive-return method "
                            + methodId + ", falling back to REAL");
                    return null;
                }
                return new MockResult(MockResult.VALUE, null, null);
            }
            if (returnType.isPrimitive()) {
                // 基本类型：装箱类精确匹配（int ← Integer），防 advice 拆箱异常
                Class<?> boxed = boxedType(returnType);
                if (boxed.isInstance(value)) {
                    return new MockResult(MockResult.VALUE, value, null);
                }
                // 装箱不匹配 → 走 JSON 转换（如 ofValue(5L) 给 int 方法经归一化）
                Object converted = ValueConverter.fromJson(GSON.toJsonTree(value),
                        boxed);
                if (boxed.isInstance(converted)) {
                    return new MockResult(MockResult.VALUE, converted, null);
                }
                throw new ValueConverter.ConversionException("VALUE " + value
                        + " (" + value.getClass().getSimpleName()
                        + ") 无法转换为目标返回类型 " + returnType.getName());
            }
            if (returnType.isInstance(value)) {
                return new MockResult(MockResult.VALUE, value, null);
            }
            // 引用类型不符 → 复用 M2 转换器（String→POJO/数组/List 等统一语义）
            Object converted = ValueConverter.fromJson(GSON.toJsonTree(value),
                    returnType);
            if (converted == null || returnType.isInstance(converted)) {
                return new MockResult(MockResult.VALUE, converted, null);
            }
            throw new ValueConverter.ConversionException("VALUE " + value
                    + " (" + value.getClass().getSimpleName()
                    + ") 无法转换为目标返回类型 " + returnType.getName());
        } catch (Throwable t) {
            log.warning("plugin outcome VALUE conversion failed on " + methodId
                    + ", falling back to REAL: " + t);
            return null;
        }
    }

    private boolean isVoid(String methodId, String descriptor) {
        return descriptor != null
                && descriptor.substring(descriptor.lastIndexOf(')') + 1).equals("V");
    }

    private Class<?> returnType(String methodId, String descriptor) {
        Class<?> cached = returnTypeCache.get(methodId);
        if (cached != null) {
            return cached;
        }
        int first = methodId.indexOf('#');
        int second = methodId.indexOf('#', first + 1);
        Class<?> resolved = null;
        Method m = resolveMethod(methodId.substring(0, first),
                methodId.substring(first + 1, second), descriptor);
        if (m != null) {
            resolved = m.getReturnType();
        }
        if (resolved == null) {
            resolved = descriptorReturnType(descriptor);
        }
        returnTypeCache.put(methodId, resolved);
        return resolved;
    }

    // ------------------------------------------------------------------
    // descriptor 工具（与 config.MatchEngine 同规则；独立副本避免跨包暴露）
    // ------------------------------------------------------------------

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

    private static Class<?> descriptorReturnType(String descriptor) {
        String ret = descriptor.substring(descriptor.lastIndexOf(')') + 1);
        char c = ret.charAt(0);
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
                return java.lang.reflect.Array.newInstance(
                        Object.class, 0).getClass(); // 近似：数组元素类型未知
            case 'L':
                String name = ret.substring(1, ret.length() - 1).replace('/', '.');
                try {
                    return Class.forName(name);
                } catch (ClassNotFoundException e) {
                    return Object.class;
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
