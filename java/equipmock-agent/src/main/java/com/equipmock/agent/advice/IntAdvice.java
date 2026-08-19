package com.equipmock.agent.advice;

import com.equipmock.bootstrap.MockResult;
import com.equipmock.bootstrap.SneakyThrow;
import com.equipmock.bootstrap.Spy;
import net.bytebuddy.asm.Advice;

/**
 * int 返回类型方法的插桩模板（M1-4，按返回类型分派的 10 模板之一）。
 *
 * <p><b>两段式（enter + exit）</b>——实测 byte-buddy 1.9.16 的 skipOn 跳过路径返回
 * <i>默认值</i>而非 enter 返回值（见 02 §4 实现要点 1 的落地差异），因此：
 * <ul>
 *   <li>enter：返回 Object（即 MockResult）——null=放行（默认值，不跳过），
 *       非 null=跳过原方法体；THROW 直接 SneakyThrow 抛出（永不返回）。
 *       enter 返回值经 @Advice.Enter 传给 exit。</li>
 *   <li>exit：放行路径（enter==null）不动返回值；跳过路径按 code 写回
 *       VALUE（拆箱转换）/保持默认（VOID 等）。</li>
 * </ul>
 * 方法体内只允许引用 bootstrap 契约类（Spy/MockResult/SneakyThrow）与 JDK 核心类型
 * —— 字节码会被内联复制进宿主类。
 */
public final class IntAdvice {

    private IntAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object enter(
            @Advice.This(optional = true) Object self,
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#d") String descriptor,
            @Advice.AllArguments Object[] args) {
        Object r = Spy.mock(className, methodName, descriptor, self, args);
        if (r == null) {
            return null; // 无 MockResult（agent 未就绪或路由未命中）：放行
        }
        MockResult mr = (MockResult) r;
        if (mr.code == MockResult.REAL) {
            return null; // 显式 REAL：放行
        }
        if (mr.code == MockResult.THROW) {
            SneakyThrow.raise(mr.throwable); // 直抛，永不返回
        }
        return mr; // VALUE / VOID：跳过原方法体，交由 exit 写回返回值
    }

    @Advice.OnMethodExit
    public static void exit(@Advice.Enter Object enter,
                            @Advice.Return(readOnly = false) int ret) {
        if (enter == null) {
            return; // 放行路径：保留真实返回值
        }
        MockResult mr = (MockResult) enter;
        if (mr.code == MockResult.VALUE) {
            ret = ((Number) mr.value).intValue();
        }
        // VOID / 其它 code：ret 保持默认值（吞掉真实调用）
    }
}
