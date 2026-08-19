package com.equipmock.agent.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 插件路由快照（02 §5.1 MockRouter 的不可变形态）：每次插件变更后整体重建、
 * volatile 单引用替换，查询路径无锁。
 *
 * <ul>
 *   <li>{@link #byClass}：目标类 → MockPoint 列表（插件注册序，多插件串联
 *       first-match 的执行序，05 §3）；</li>
 *   <li>{@link #targetClasses}/{@link #methodNames}/{@link #allMethods}：供
 *       CompositeRouteTable 与插桩 matcher 派生「插件声明 ∪ 配置派生」集合。</li>
 * </ul>
 */
public final class PluginRouting {

    /** 空快照（无插件） */
    public static final PluginRouting EMPTY = new PluginRouting(
            Collections.<String, List<MockPoint>>emptyMap(),
            Collections.<String, Set<String>>emptyMap());

    private final Map<String, List<MockPoint>> byClass;
    private final Map<String, Set<String>> classToMethods;

    private PluginRouting(Map<String, List<MockPoint>> byClass,
                          Map<String, Set<String>> classToMethods) {
        this.byClass = byClass;
        this.classToMethods = classToMethods;
    }

    /** 由全部 MockPoint（注册序）构建快照 */
    public static PluginRouting build(List<MockPoint> points) {
        Map<String, List<MockPoint>> byClass = new LinkedHashMap<String, List<MockPoint>>();
        Map<String, Set<String>> classToMethods = new LinkedHashMap<String, Set<String>>();
        for (MockPoint point : points) {
            List<MockPoint> list = byClass.get(point.className);
            if (list == null) {
                list = new java.util.ArrayList<MockPoint>();
                byClass.put(point.className, list);
                classToMethods.put(point.className, new LinkedHashSet<String>());
            }
            list.add(point);
            classToMethods.get(point.className).addAll(point.methodNames);
        }
        Map<String, List<MockPoint>> immutable = new LinkedHashMap<String, List<MockPoint>>();
        for (Map.Entry<String, List<MockPoint>> e : byClass.entrySet()) {
            immutable.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        Map<String, Set<String>> immutableMethods = new LinkedHashMap<String, Set<String>>();
        for (Map.Entry<String, Set<String>> e : classToMethods.entrySet()) {
            immutableMethods.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        return new PluginRouting(Collections.unmodifiableMap(immutable),
                Collections.unmodifiableMap(immutableMethods));
    }

    /** 目标类 → 注册序 MockPoint 列表（无声明返回 null） */
    public List<MockPoint> points(String className) {
        return byClass.get(className);
    }

    /** 全部插件目标类 */
    public Set<String> targetClasses() {
        return byClass.keySet();
    }

    /** 某类上插件声明的方法名集合（含 {@link MockPoint#ALL_METHODS} 标记） */
    public Set<String> methodNames(String className) {
        Set<String> methods = classToMethods.get(className);
        return methods == null ? Collections.<String>emptySet() : methods;
    }

    /** 某类是否被声明了 methods={"*"}（插桩全方法织入） */
    public boolean allMethods(String className) {
        return methodNames(className).contains(MockPoint.ALL_METHODS);
    }
}
