package mock.e2e.bad;

import com.equipmock.api.MockHandler;
import com.equipmock.api.MockInterceptor;
import com.equipmock.api.MockInvocation;
import com.equipmock.api.MockOutcome;
import org.pf4j.Extension;

/**
 * e2e fixture：版本硬校验拒绝样例（D19）——manifest 声明
 * Plugin-Requires: equipmock &gt;=9.9.9，加载必须被拒（state REJECTED），
 * 宿主行为不受影响。
 */
@Extension
@MockInterceptor(targetClasses = "com.equip.demo.PowerDevice",
        methods = {"getName"})
public class BadVersionHandler implements MockHandler {

    @Override
    public MockOutcome handle(MockInvocation inv) {
        return MockOutcome.ofValue("BAD-PLUGIN");
    }
}
