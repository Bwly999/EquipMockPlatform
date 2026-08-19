package com.equipmock.agent.config;

import java.util.List;
import java.util.Objects;

/**
 * 一个小分组配置文件（config/groups/&lt;组&gt;/&lt;小分组&gt;.json）的不可变解析结果（03 §1）。
 *
 * <p>entries 保留文件内全部 mock 项（含 enabled=false，便于条目计数展示）；
 * {@link MockIndex} 构建时才跳过禁用项。
 */
public final class SubGroupFile {

    /** 文件名（不含 .json），默认即小分组显示名 */
    public final String fileName;
    /** 配置内的 name（缺省=文件名） */
    public final String displayName;
    public final List<MockEntry> entries;

    public SubGroupFile(String fileName, String displayName, List<MockEntry> entries) {
        this.fileName = fileName;
        this.displayName = displayName;
        this.entries = entries;
    }

    public int entryCount() {
        return entries.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SubGroupFile)) {
            return false;
        }
        SubGroupFile that = (SubGroupFile) o;
        return Objects.equals(fileName, that.fileName)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(entries, that.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, displayName, entries);
    }
}
