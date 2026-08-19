package com.equipmock.agent.config;

import com.google.gson.JsonObject;

import java.util.Map;

/**
 * state.json 写入抽象（04 §6）：ConfigCenter 依赖本接口而非具体 StateWriter，
 * 便于单测注入计数桩。lastError 结构 {time, file, message}（message 可 null）。
 */
public interface StateSink {

    /**
     * 全量写 state.json（02 §8 回写时机全集：启动完成/配置切换/重载成败/运行期错误）。
     *
     * @param activeGroup 当前生效配置组
     * @param mockEnabled 全局开关
     * @param instrumentedClasses 插桩目标类数
     * @param groupFileEntryCounts 活动组各小分组文件条目数（03 §2 第 4 步）；null=省略该字段
     * @param lastError 最近错误；null 表示显式写 "lastError": null
     */
    void writeState(String activeGroup, boolean mockEnabled, int instrumentedClasses,
                    Map<String, Integer> groupFileEntryCounts, JsonObject lastError);
}
