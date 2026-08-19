package com.equipmock.agent;

import com.equipmock.agent.config.ConfigCenter;
import com.equipmock.agent.config.GroupSnapshot;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 目标类变更监控（M2-6 运行期新增目标类语义）：
 * 活动组快照替换后 diff RouteTable 的目标类/方法集合——
 * <ul>
 *   <li>新增类已被 JVM 加载 → info 日志 "takes effect after restart"
 *       （插桩 matcher 是动态的，但已加载类无类加载事件；M3 retransform 解决）；</li>
 *   <li>新增类未加载 → 无需动作（首次加载即被动态 matcher 织入）；</li>
 *   <li>已加载类上新增方法名 → 同样记 info 日志。</li>
 * </ul>
 */
final class TargetClassChangeMonitor implements ConfigCenter.GroupReloadListener {

    private final Instrumentation inst;
    private final RouteTable routeTable;
    private final Logger log;
    /** 已知类 → 方法名集合（初始化时填充） */
    private final Map<String, Set<String>> known = new HashMap<String, Set<String>>();

    TargetClassChangeMonitor(Instrumentation inst, RouteTable routeTable, Logger log) {
        this.inst = inst;
        this.routeTable = routeTable;
        this.log = log;
    }

    /** 初始化基线（AgentPremain 在首次注册后调用，避免把启动集合当新增） */
    synchronized void initBaseline() {
        known.clear();
        for (String className : routeTable.targetClasses()) {
            known.put(className, new HashSet<String>(routeTable.methodNames(className)));
        }
    }

    @Override
    public synchronized void onActiveGroupReplaced(GroupSnapshot newSnapshot) {
        Set<String> loaded = loadedClassNames();
        Set<String> currentClasses = routeTable.targetClasses();

        for (String className : currentClasses) {
            Set<String> knownMethods = known.get(className);
            Set<String> currentMethods = routeTable.methodNames(className);
            if (knownMethods == null) {
                if (loaded.contains(className)) {
                    log.info("new target class " + className
                            + " already loaded; takes effect after restart");
                } else {
                    log.info("new target class " + className
                            + " registered; will be instrumented on first load");
                }
            } else if (loaded.contains(className)) {
                Set<String> added = new HashSet<String>(currentMethods);
                added.removeAll(knownMethods);
                if (!added.isEmpty()) {
                    log.info("new target method(s) " + added + " on loaded class "
                            + className + "; takes effect after restart");
                }
            }
        }

        Map<String, Set<String>> next = new HashMap<String, Set<String>>();
        for (String className : currentClasses) {
            next.put(className, new HashSet<String>(routeTable.methodNames(className)));
        }
        known.clear();
        known.putAll(next);
    }

    private Set<String> loadedClassNames() {
        Set<String> names = new HashSet<String>();
        if (inst == null) {
            return names;
        }
        Class<?>[] all = inst.getAllLoadedClasses();
        for (Class<?> c : all) {
            names.add(c.getName());
        }
        return names;
    }
}
