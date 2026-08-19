package com.equipmock.agent.advice;

import com.equipmock.bootstrap.MockResult;
import com.equipmock.bootstrap.SneakyThrow;
import com.equipmock.bootstrap.Spy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * 引用（对象/数组/String/包装类）返回类型方法的插桩模板（M1-4）：两段式 enter+exit，
 * 差异仅在 exit 的返回值写回。背景与约束见 {@link IntAdvice} 类注释。
 * 引用类型写回需要 typing = DYNAMIC（Object 到实际返回类型的运行期 cast）。
 */
public final class ObjectAdvice {

    private ObjectAdvice() {
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
                            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object ret) {
        if (enter == null) {
            return; // 放行路径：保留真实返回值
        }
        MockResult mr = (MockResult) enter;
        if (mr.code == MockResult.VALUE) {
            ret = mr.value; // DYNAMIC：写回时 cast 到实际返回类型
        }
        // VOID / 其它 code：ret 保持默认值 null（吞掉真实调用）
    }
}
