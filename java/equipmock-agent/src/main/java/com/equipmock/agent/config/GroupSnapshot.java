package com.equipmock.agent.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一个配置组的完整不可变快照（03 §1）：groupName + 有序小分组文件表 + Mock 索引。
 *
 * <p>重载时整体 new + 对 volatile 单引用原子替换（查询路径无锁）。
 * equals 覆盖用于幂等：快照内容未变不替换、不重写 state（04 §8）。
 * 首启失败时使用 {@link #empty(String)}（空组 = 全部 REAL）。
 */
public final class GroupSnapshot {

    private final String groupName;
    /** 文件名(不含 .json) → 文件内容（自然序） */
    private final Map<String, SubGroupFile> files;
    private final MockIndex index;

    public GroupSnapshot(String groupName, Map<String, SubGroupFile> files, MockIndex index) {
        this.groupName = groupName;
        this.files = files;
        this.index = index;
    }

    /** 空组（首启失败=全部 REAL，03 §2） */
    public static GroupSnapshot empty(String groupName) {
        return new GroupSnapshot(groupName,
                Collections.<String, SubGroupFile>emptyMap(), MockIndex.build(
                        Collections.<SubGroupFile>emptyList()));
    }

    public String groupName() {
        return groupName;
    }

    public Map<String, SubGroupFile> files() {
        return files;
    }

    public MockIndex index() {
        return index;
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /** 各文件条目数（state.json 的 groupFileEntryCounts 数据源，保持文件自然序） */
    public Map<String, Integer> entryCounts() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, SubGroupFile> e : files.entrySet()) {
            counts.put(e.getKey(), Integer.valueOf(e.getValue().entryCount()));
        }
        return counts;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupSnapshot)) {
            return false;
        }
        GroupSnapshot that = (GroupSnapshot) o;
        return Objects.equals(groupName, that.groupName) && Objects.equals(files, that.files);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupName, files);
    }
}
