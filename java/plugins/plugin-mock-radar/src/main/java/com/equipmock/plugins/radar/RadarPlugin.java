package com.equipmock.plugins.radar;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

import java.util.logging.Logger;

/**
 * 雷达伺服示例插件入口（05 §1：PF4J 插件入口，可选）。
 *
 * <p>Mock 行为全部在 {@link RadarServoHandler}。
 */
public class RadarPlugin extends Plugin {

    private final Logger log = Logger.getLogger(RadarPlugin.class.getName());

    public RadarPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        log.info("mock-radar started: RadarServo 雷达伺服仿真");
    }

    @Override
    public void stop() {
        log.info("mock-radar stopped");
    }
}
