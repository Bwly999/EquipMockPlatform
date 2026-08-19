package com.equipmock.agent.config;

import java.util.List;
import java.util.Objects;

/**
 * 小分组配置中一个 mocks[] 元素解析后的不可变对象（03 §1）：
 * {className, methodName, descriptor(可空), enabled, rules, defaultAction(可空)}。
 *
 * <p>descriptor 为空 = 作用于同名全部重载（索引键 {@code className#methodName#}）。
 */
public final class MockEntry {

    public final String className;
    public final String methodName;
    /** JVM descriptor（如 (ILjava/lang/String;)I）；null=同名全部重载 */
    public final String descriptor;
    public final boolean enabled;
    public final List<MockRule> rules;
    public final ActionDef defaultAction;
    /** 来源小分组文件名（相对 home，用于错误定位与 state.lastError） */
    public final String sourceFile;

    public MockEntry(String className, String methodName, String descriptor, boolean enabled,
                     List<MockRule> rules, ActionDef defaultAction, String sourceFile) {
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.enabled = enabled;
        this.rules = rules;
        this.defaultAction = defaultAction;
        this.sourceFile = sourceFile;
    }

    /** methodId：className#methodName#descriptor（descriptor 为空时以空串结尾=无签名条目） */
    public String methodId() {
        return className + "#" + methodName + "#" + (descriptor == null ? "" : descriptor);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MockEntry)) {
            return false;
        }
        MockEntry that = (MockEntry) o;
        return enabled == that.enabled
                && Objects.equals(className, that.className)
                && Objects.equals(methodName, that.methodName)
                && Objects.equals(descriptor, that.descriptor)
                && Objects.equals(rules, that.rules)
                && Objects.equals(defaultAction, that.defaultAction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, descriptor, enabled, rules, defaultAction);
    }
}
