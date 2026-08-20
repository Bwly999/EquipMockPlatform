package com.equipmock.plugins.cabinet;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

import java.util.logging.Logger;

/**
 * 机柜电源示例插件入口（05 §1：PF4J 插件入口，可选——无生命周期钩子时可省）。
 *
 * <p>保留入口以演示插件模块两类文件的完整结构；Mock 行为全部在
 * {@link PowerDeviceHandler}。
 */
public class CabinetPlugin extends Plugin {

    private final Logger log = Logger.getLogger(CabinetPlugin.class.getName());

    public CabinetPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        log.info("mock-cabinet started: PowerDevice 机柜电源仿真");
    }

    @Override
    public void stop() {
        log.info("mock-cabinet stopped");
    }
}
