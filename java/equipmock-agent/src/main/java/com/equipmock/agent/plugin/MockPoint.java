package com.equipmock.agent.plugin;

import com.equipmock.api.MockHandler;

import java.util.Collections;
import java.util.Set;

/**
 * 插件路由点（02 §5.1）：一个 handler 对一个目标类的拦截声明
 * （{@code @MockInterceptor} 的 targetClasses 展开为逐类 MockPoint）。
 *
 * <p>{@link #pluginEnabled} 是 volatile 路由开关（D8）：registry.enabled 翻转
 * 即时生效，无字节码操作；advice 织入不回滚。
 */
public final class MockPoint {

    /** 目标方法通配（05 §3：类中全部声明方法，declaredOnly 语义） */
    public static final String ALL_METHODS = "*";

    public final String pluginId;
    public final String className;
    /** 方法名集合（或含 {@link #ALL_METHODS}） */
    public final Set<String> methodNames;
    volatile boolean pluginEnabled;
    final MockHandler handler;

    MockPoint(String pluginId, String className, Set<String> methodNames,
              boolean pluginEnabled, MockHandler handler) {
        this.pluginId = pluginId;
        this.className = className;
        this.methodNames = Collections.unmodifiableSet(methodNames);
        this.pluginEnabled = pluginEnabled;
        this.handler = handler;
    }

    /** D8 路由开关翻转（registry.enabled 变化） */
    public void setPluginEnabled(boolean enabled) {
        this.pluginEnabled = enabled;
    }

    public boolean isPluginEnabled() {
        return pluginEnabled;
    }

    /** 方法名是否命中本声明（含通配） */
    public boolean matchesMethod(String methodName) {
        return pluginEnabled && (methodNames.contains(methodName)
                || methodNames.contains(ALL_METHODS));
    }

    /** 是否声明了通配（插桩 matcher 用） */
    public boolean isWildcard() {
        return methodNames.contains(ALL_METHODS);
    }

    @Override
    public String toString() {
        return "MockPoint[" + pluginId + " " + className + methodNames
                + (pluginEnabled ? "" : " (disabled)") + "]";
    }
}
