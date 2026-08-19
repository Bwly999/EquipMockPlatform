package com.equipmock.agent.plugin;

import com.equipmock.api.MockHandler;
import com.equipmock.api.MockInterceptor;
import com.equipmock.api.MockInvocation;
import com.equipmock.api.MockOutcome;
import org.pf4j.Extension;

/**
 * 单测 fixture：插件 MockHandler 实现（编译产物由 {@link FixturePluginJar}
 * 拷入最小插件 jar；目标类名随意——路由层不解析目标类也可工作）。
 */
public final class FixtureHandlers {

    private FixtureHandlers() {
    }

    /** 正常 handler：ping → 写死 VALUE "FIXED" */
    @Extension
    @MockInterceptor(targetClasses = "fixture.Target", methods = {"ping"})
    public static class GoodHandler implements MockHandler {
        @Override
        public MockOutcome handle(MockInvocation inv) {
            return MockOutcome.ofValue("FIXED");
        }
    }

    /** null handler：落配置中心规则 */
    @Extension
    @MockInterceptor(targetClasses = "fixture.Target", methods = {"ping"})
    public static class NullHandler implements MockHandler {
        @Override
        public MockOutcome handle(MockInvocation inv) {
            return null;
        }
    }

    /** 缺 @MockInterceptor 注解（05 §3 → 插件 FAILED） */
    @Extension
    public static class NoAnnotationHandler implements MockHandler {
        @Override
        public MockOutcome handle(MockInvocation inv) {
            return MockOutcome.ofValue("X");
        }
    }

    /** 空 targetClasses（05 §3 → 插件 FAILED） */
    @Extension
    @MockInterceptor(targetClasses = {}, methods = {"ping"})
    public static class EmptyTargetsHandler implements MockHandler {
        @Override
        public MockOutcome handle(MockInvocation inv) {
            return MockOutcome.ofValue("X");
        }
    }
}
