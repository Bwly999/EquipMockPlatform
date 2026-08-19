package com.equipmock.agent.advice;

import com.equipmock.bootstrap.MockResult;
import com.equipmock.bootstrap.SneakyThrow;
import com.equipmock.bootstrap.Spy;
import net.bytebuddy.asm.Advice;

/**
 * 按返回类型分派的插桩模板（M1-4）：两段式 enter+exit，差异仅在 exit 的返回值写回类型。背景与约束见 {@link IntAdvice} 类注释。
 */
public final class FloatAdvice {

    private FloatAdvice() {
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
    public static void exit(@Advice.Enter Object enter,
            @Advice.Return(readOnly = false) float ret) {
        if (enter == null) {
            return; // 放行路径：保留真实返回值
        }
        MockResult mr = (MockResult) enter;
        if (mr.code == MockResult.VALUE) {
            ret = ((Number) mr.value).floatValue();
        }
    }
}
