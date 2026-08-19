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
import java.util.Set;
import java.util.logging.Logger;

/**
 * ByteBuddy 插桩注册（M1-4，02 §4；M2-6 起数据源改为 {@link RouteTable} 动态查询）。
 *
 * <ul>
 *   <li>{@code disableClassFormatChanges + RETRANSFORMATION}：retransform 兼容
 *       （只加 advice 代码不改类结构，D8/D9）。</li>
 *   <li>ignore 掉平台自身与 JDK/非宿主包，防误伤。</li>
 *   <li><b>类型/方法匹配均为 RouteTable 动态查询</b>：目标类用「类名 ∈
 *       routeTable.targetClasses()」精确匹配、方法用「已声明方法名集合 contains」；
 *       运行期配置新增的<b>未加载</b>类首次加载即被织入（M2 语义）；
 *       已加载类在 M2 阶段仅记 info 日志（TargetClassChangeMonitor），
 *       M3 的 retransform 机制解决补齐。</li>
 *   <li>方法匹配 = "路由表已声明方法名集合 contains" AND 返回类型；按返回类型把 10 个
 *       advice 模板分别织入（{@code ForDeclaredMethods.method(matcher, Advice.to(X))}
 *       组合，一个 visitor 覆盖全部返回类型分支）——与 M1 完全一致，零语义改动。</li>
 * </ul>
 */
public final class InstrumentationRegistrar {

    private InstrumentationRegistrar() {
    }

    /**
     * 注册插桩（动态 RouteTable 数据源）。
     *
     * @param routeTable 路由表（配置中心/插件组合数据源）
     * @return 监听器（可读插桩计数）
     */
    public static InstrumentationListener register(Instrumentation inst, Logger log,
                                                   RouteTable routeTable) {
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
                        .or(ElementMatchers.nameStartsWith("com.google.gson")))
                // 类型匹配动态查询 RouteTable：类加载事件发生时以最新 targetClasses 判定
                .type(new ElementMatcher<TypeDescription>() {
                    @Override
                    public boolean matches(TypeDescription target) {
                        return routeTable.targetClasses().contains(target.getName());
                    }
                })
                // 方法匹配在 transform 时按该类最新 methodNames 判定
                .transform((DynamicType.Builder<?> b, TypeDescription type,
                            ClassLoader classLoader, JavaModule module) ->
                        b.visit(adviceByReturnType(
                                declaredMethods(routeTable, type.getName()))));
        builder.installOn(inst);
        log.info("instrumentation registered (dynamic RouteTable matcher, "
                + routeTable.targetClasses().size() + " target class(es) at install time)");
        return listener;
    }

    /** 方法名集合 → "已声明方法名 contains" 匹配器（M3：methods={"*"} 时织入全部声明方法） */
    private static ElementMatcher.Junction<MethodDescription> declaredMethods(
            RouteTable routeTable, String className) {
        if (routeTable.interceptAllMethods(className)) {
            // 05 §3 declaredOnly 语义：ForDeclaredMethods 本身只作用于本类声明的方法，
            // isMethod 排除构造器/类型初始化器
            return ElementMatchers.<MethodDescription>isMethod();
        }
        return declaredMethodNames(routeTable.methodNames(className));
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
