package mock.e2e;

import com.equipmock.api.MockHandler;
import com.equipmock.api.MockInterceptor;
import com.equipmock.api.MockInvocation;
import com.equipmock.api.MockOutcome;
import org.pf4j.Extension;

/**
 * e2e fixture 插件 handler（scripts/e2e-check.sh [plugin] 段现场编译打 jar）：
 * getName 写死 ofValue("PLUGIN-NAME")（写死优先于配置）；readStatus 返回 null
 * 落配置中心规则（05 §2 决策表的两种典型路径各占一个方法）。
 */
@Extension
@MockInterceptor(targetClasses = "com.equip.demo.PowerDevice",
        methods = {"getName", "readStatus"})
public class PowerDeviceHandler implements MockHandler {

    @Override
    public MockOutcome handle(MockInvocation inv) {
        if ("getName".equals(inv.methodName)) {
            return MockOutcome.ofValue("PLUGIN-NAME");
        }
        return null; // readStatus：交给配置中心规则
    }
}
