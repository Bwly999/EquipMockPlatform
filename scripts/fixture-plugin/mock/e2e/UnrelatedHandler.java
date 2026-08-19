package mock.e2e;

import com.equipmock.api.MockHandler;
import com.equipmock.api.MockInterceptor;
import com.equipmock.api.MockInvocation;
import com.equipmock.api.MockOutcome;
import org.pf4j.Extension;

/**
 * e2e fixture：拦截<b>不在任何配置组</b>中的 UnrelatedService.hello——
 * 该类在插件导入前已被宿主加载且从未插桩，是"热导入对已加载类 retransform
 * 补齐"（D9）的真实验证点（PowerDevice 各方法自始被配置覆盖织入，
 * 无法区分 retransform 增量）。
 */
@Extension
@MockInterceptor(targetClasses = "com.equip.demo.UnrelatedService",
        methods = {"hello"})
public class UnrelatedHandler implements MockHandler {

    @Override
    public MockOutcome handle(MockInvocation inv) {
        return MockOutcome.ofValue("PLUGIN-HELLO");
    }
}
