package com.equipmock.agent;

import com.equipmock.agent.advice.BooleanAdvice;
import com.equipmock.agent.advice.ByteAdvice;
import com.equipmock.agent.advice.CharAdvice;
import com.equipmock.agent.advice.DoubleAdvice;
import com.equipmock.agent.advice.FloatAdvice;
import com.equipmock.agent.advice.IntAdvice;
import com.equipmock.agent.advice.LongAdvice;
import com.equipmock.agent.advice.ObjectAdvice;
import com.equipmock.agent.advice.ShortAdvice;
import com.equipmock.agent.advice.VoidAdvice;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * ByteBuddy 插桩注册（M1-4，02 §4）。
 *
 * <ul>
 *   <li>{@code disableClassFormatChanges + RETRANSFORMATION}：retransform 兼容
 *       （只加 advice 代码不改类结构，D8/D9）。</li>
 *   <li>ignore 掉平台自身与 JDK/非宿主包，防误伤。</li>
 *   <li>目标类用 {@code named(fqcn)} 精确匹配，逐类 transform。</li>
 *   <li>方法匹配 = "路由表已声明方法名集合 contains" AND 返回类型；按返回类型把 10 个
 *       advice 模板分别织入（{@code ForDeclaredMethods.method(matcher, Advice.to(X))}
 *       组合，一个 visitor 覆盖全部返回类型分支）。</li>
 * </ul>
 */
public final class InstrumentationRegistrar {

    private InstrumentationRegistrar() {
    }

    /**
     * 注册全部目标类的插桩。
     *
     * @param targets 类名 → 该类已声明方法名集合（来自 RouteTable）
     * @return 监听器（可读插桩计数）
     */
    public static InstrumentationListener register(Instrumentation inst, Logger log,
                                                   Map<String, Set<String>> targets) {
        InstrumentationListener listener = new InstrumentationListener(log);

        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(listener)
                // 非宿主包一律忽略：平台自身、JDK、shade 产物、PF4J（02 §4.2）
                .ignore(ElementMatchers.<TypeDescription>nameStartsWith("com.equipmock.")
                        .or(ElementMatchers.nameStartsWith("io.equipmock.shaded."))
                        .or(ElementMatchers.nameStartsWith("java."))
                        .or(ElementMatchers.nameStartsWith("javax."))
                        .or(ElementMatchers.nameStartsWith("sun."))
                        .or(ElementMatchers.nameStartsWith("com.sun."))
                        .or(ElementMatchers.nameStartsWith("jdk."))
                        .or(ElementMatchers.nameStartsWith("org.pf4j"))
                        .or(ElementMatchers.nameStartsWith("net.bytebuddy"))
                        .or(ElementMatchers.nameStartsWith("com.google.gson")));

        for (Map.Entry<String, Set<String>> entry : targets.entrySet()) {
            final String className = entry.getKey();
            final ElementMatcher.Junction<MethodDescription> declaredNames =
                    declaredMethodNames(entry.getValue());
            builder = builder
                    .type(ElementMatchers.named(className))
                    .transform((DynamicType.Builder<?> b, TypeDescription type,
                                ClassLoader classLoader, JavaModule module) ->
                            b.visit(adviceByReturnType(declaredNames)));
        }
        builder.installOn(inst);
        log.info("instrumentation registered for " + targets.size() + " target class(es)");
        return listener;
    }

    /** 方法名集合 → "已声明方法名 contains" 匹配器 */
    private static ElementMatcher.Junction<MethodDescription> declaredMethodNames(
            Set<String> methodNames) {
        ElementMatcher.Junction<MethodDescription> matcher =
                ElementMatchers.<MethodDescription>none();
        for (String name : methodNames) {
            matcher = matcher.or(ElementMatchers.named(name));
        }
        return matcher;
    }

    /**
     * 按返回类型分派 10 个 advice 模板：同一 visitor 上按
     * (声明方法名 && 返回类型) 精确绑定，一个类内不同方法可分到不同模板。
     */
    private static AsmVisitorWrapper adviceByReturnType(
            ElementMatcher.Junction<MethodDescription> declaredNames) {
        return new AsmVisitorWrapper.ForDeclaredMethods()
                .method(declaredNames.and(ElementMatchers.returns(int.class)),
                        Advice.to(IntAdvice.class))
                .method(declaredNames.and(ElementMatchers.returns(long.class)),
                        Advice.to(LongAdvice.class))
                .method(declaredNames.and(ElementMatchers.returns(double.class)),
                        Advice.to(DoubleAdvice.class))
                .method(declaredNames.and(ElementMatchers.returns(float.class)),
                        Advice.to(FloatAdvice.class))
                .method(declaredNames.and(ElementMatchers.returns(boolean.class)),
                        Advice.to(BooleanAdvice.class))
                .method(declaredNames.and(ElementMatchers.returns(short.class)),
                        Advice.to(ShortAdvice.class))
                .method(declaredNames.and(ElementMatchers.returns(byte.class)),
                        Advice.to(ByteAdvice.class))
                .method(declaredNames.and(ElementMatchers.returns(char.class)),
                        Advice.to(CharAdvice.class))
                .method(declaredNames.and(ElementMatchers.returns(void.class)),
                        Advice.to(VoidAdvice.class))
                // 引用类型（含数组/String/POJO）：非基本类型即引用
                .method(declaredNames.and(ElementMatchers
                                .<MethodDescription>returns(ElementMatchers
                                        .not(ElementMatchers.isPrimitive()))),
                        Advice.to(ObjectAdvice.class));
    }
}
