package com.equipmock.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * AgentBuilder 监听器（02 §4.2）：插桩失败记日志并计数（M1 不外泄，M2 计入 state.json）。
 */
final class InstrumentationListener implements AgentBuilder.Listener {

    private final Logger log;
    private final AtomicInteger transformationCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();

    InstrumentationListener(Logger log) {
        this.log = log;
    }

    /** 实际完成插桩的类数 */
    int transformationCount() {
        return transformationCount.get();
    }

    int errorCount() {
        return errorCount.get();
    }

    @Override
    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded) {
        // 无需逐类输出（噪音大）
    }

    @Override
    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                 JavaModule module, boolean loaded, DynamicType dynamicType) {
        transformationCount.incrementAndGet();
        log.info("instrumented class: " + typeDescription.getName()
                + (loaded ? " (retransform)" : " (first load)"));
    }

    @Override
    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
                          JavaModule module, boolean loaded) {
        // 被忽略属正常路径（精确类匹配的另一半）
    }

    @Override
    public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                        boolean loaded, Throwable throwable) {
        errorCount.incrementAndGet();
        log.warning("instrumentation error on " + typeName + ": " + throwable);
    }

    @Override
    public void onComplete(String typeName, ClassLoader classLoader, JavaModule module,
                           boolean loaded) {
        // 无需逐类输出
    }
}
