package com.equipmock.agent;

import com.equipmock.bootstrap.ISpyHandler;
import com.equipmock.bootstrap.MockResult;

import java.util.logging.Logger;

/**
 * agent 侧 Spy 处理器（02 §5）：premain 反射注入 {@code Spy.HANDLER}，
 * 每次被拦截调用都走本方法——热路径，只做 Map 查找，无锁无分配。
 *
 * <p>M1：查 {@link RouteTable}（硬编码表）；M2/M3 在此串联
 * 插件 MockHandler（写死逻辑优先）→ 配置中心规则（first-match）。
 */
public final class AgentSpyHandler implements ISpyHandler {

    private final Logger log;
    private final RouteTable routeTable;
    /** 全局 Mock 总开关（04 §2 settings.mockEnabled）：false 时全部放行 */
    private volatile boolean globalEnabled;

    public AgentSpyHandler(Logger log, RouteTable routeTable, boolean globalEnabled) {
        this.log = log;
        this.routeTable = routeTable;
        this.globalEnabled = globalEnabled;
    }

    /** 运行期切换总开关（M1 预留，M2 FileWatcher 调用） */
    public void setGlobalEnabled(boolean enabled) {
        this.globalEnabled = enabled;
    }

    @Override
    public Object mock(String className, String methodName, String descriptor,
                       Object self, Object[] args) {
        try {
            if (!globalEnabled) {
                return null; // D8：一键恢复真实
            }
            // 兼容 #t 模板可能出现的内部名（斜杠）形式，统一为点分 FQCN
            String normalized = className.indexOf('/') >= 0
                    ? className.replace('/', '.') : className;
            MockResult result = routeTable.lookup(normalized, methodName, descriptor);
            if (result == null) {
                return null; // 未命中：放行真实方法
            }
            if (result.code == MockResult.REAL) {
                return null;
            }
            return result; // VALUE / THROW / VOID
        } catch (Throwable t) {
            // 02 §5.3：路由异常绝不影响宿主——日志记录后本次放行
            log.warning("route lookup failed for " + className + "#" + methodName + descriptor
                    + ", falling back to real method: " + t);
            return null;
        }
    }
}
