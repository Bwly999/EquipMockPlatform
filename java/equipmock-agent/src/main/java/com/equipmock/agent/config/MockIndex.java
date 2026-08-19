package com.equipmock.agent.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 不可变 Mock 索引（03 §1）：methodId（className#methodName#descriptor 或
 * className#methodName# 无签名条目）→ 合并后的规则链。
 *
 * <p>构建规则：
 * <ul>
 *   <li>enabled=false 的 mock 项在构建索引时跳过；</li>
 *   <li>同 methodId 跨小分组文件按「文件名自然序 + 文件内序」合并成一条规则链；</li>
 *   <li>查询：精确签名条目优先，无精确条目时兜底 className#methodName#（同名任意签名）。</li>
 * </ul>
 * 全部集合为不可变拷贝，查询路径无锁。
 */
public final class MockIndex {

    private static final List<MockEntry> NO_ENTRIES = Collections.emptyList();

    private final Map<String, List<MockEntry>> byMethodId;
    private final Set<String> targetClasses;
    private final Map<String, Set<String>> classToMethods;

    private MockIndex(Map<String, List<MockEntry>> byMethodId,
                      Set<String> targetClasses,
                      Map<String, Set<String>> classToMethods) {
        this.byMethodId = byMethodId;
        this.targetClasses = targetClasses;
        this.classToMethods = classToMethods;
    }

    /**
     * 从（已按文件名自然序排列的）小分组文件列表构建索引。
     */
    public static MockIndex build(Collection<SubGroupFile> files) {
        // TreeMap + natural 文件序由调用方保证；此处按文件内序追加合并
        Map<String, List<MockEntry>> merged = new LinkedHashMap<String, List<MockEntry>>();
        Map<String, Set<String>> classToMethods = new LinkedHashMap<String, Set<String>>();
        for (SubGroupFile file : files) {
            for (MockEntry entry : file.entries) {
                if (!entry.enabled) {
                    continue; // 03 §1：enabled=false 索引期跳过
                }
                List<MockEntry> list = merged.get(entry.methodId());
                if (list == null) {
                    list = new ArrayList<MockEntry>();
                    merged.put(entry.methodId(), list);
                }
                list.add(entry);
                Set<String> methods = classToMethods.get(entry.className);
                if (methods == null) {
                    methods = new LinkedHashSet<String>();
                    classToMethods.put(entry.className, methods);
                }
                methods.add(entry.methodName);
            }
        }
        Map<String, List<MockEntry>> immutable = new LinkedHashMap<String, List<MockEntry>>();
        for (Map.Entry<String, List<MockEntry>> e : merged.entrySet()) {
            immutable.put(e.getKey(), Collections.unmodifiableList(
                    new ArrayList<MockEntry>(e.getValue())));
        }
        return new MockIndex(Collections.unmodifiableMap(immutable),
                Collections.unmodifiableSet(new LinkedHashSet<String>(classToMethods.keySet())),
                immutableClassMap(classToMethods));
    }

    private static Map<String, Set<String>> immutableClassMap(
            Map<String, Set<String>> classToMethods) {
        Map<String, Set<String>> out = new LinkedHashMap<String, Set<String>>();
        for (Map.Entry<String, Set<String>> e : classToMethods.entrySet()) {
            out.put(e.getKey(), Collections.unmodifiableSet(
                    new LinkedHashSet<String>(e.getValue())));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 查询：精确签名（methodId 含 descriptor）命中优先；
     * 无精确条目时兜底 className#methodName#（无签名条目，作用于全部重载）。
     */
    public List<MockEntry> lookup(String className, String methodName, String descriptor) {
        List<MockEntry> exact = byMethodId.get(
                className + "#" + methodName + "#" + descriptor);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }
        List<MockEntry> anySignature = byMethodId.get(className + "#" + methodName + "#");
        return anySignature == null ? NO_ENTRIES : anySignature;
    }

    /** 全部目标类名（enabled 条目派生；插桩注册与 state.instrumentedClasses 数据源） */
    public Set<String> targetClasses() {
        return targetClasses;
    }

    /** 某目标类上需要织入 advice 的方法名集合 */
    public Set<String> methodNames(String className) {
        Set<String> methods = classToMethods.get(className);
        return methods == null ? Collections.<String>emptySet() : methods;
    }

    /** 仅测试/日志用：全部 methodId（自然序视图） */
    Map<String, List<MockEntry>> tableForDebug() {
        return Collections.unmodifiableMap(new TreeMap<String, List<MockEntry>>(byMethodId));
    }
}
