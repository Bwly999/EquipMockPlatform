package com.equipmock.agent.advice;

import com.equipmock.bootstrap.MockResult;
import com.equipmock.bootstrap.SneakyThrow;
import com.equipmock.bootstrap.Spy;
import net.bytebuddy.asm.Advice;

/**
 * void 返回类型方法的插桩模板（M1-4，按返回类型分派的 10 模板之一）：两段式 enter+exit。
 * 背景与约束见 {@link IntAdvice} 类注释。
 *
 * <p>byte-buddy 对 void 方法 + {@code @Advice.Return} 组合会校验拒绝
 * （02 §4 实现要点 4），因此 exit 不带返回值参数——跳过（skipOn）即吞掉真实调用。
 */
public final class VoidAdvice {

    private VoidAdvice() {
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
            return null; // 放行
        }
        MockResult mr = (MockResult) r;
        if (mr.code == MockResult.REAL) {
            return null;
        }
        if (mr.code == MockResult.THROW) {
            SneakyThrow.raise(mr.throwable);
        }
        return mr; // VALUE / VOID：跳过原方法体
    }

    @Advice.OnMethodExit
    public static void exit(@Advice.Enter Object enter) {
        // 放行路径：真实方法体已执行；跳过路径：原方法体未执行，调用被吞掉——均无需动作
    }
}
