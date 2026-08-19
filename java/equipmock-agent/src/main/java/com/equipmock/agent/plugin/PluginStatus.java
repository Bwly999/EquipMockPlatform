package com.equipmock.agent.plugin;

import com.google.gson.JsonObject;

/**
 * state.json 的 plugins[] 条目（04 §6）。
 *
 * <p>state 语义：{@code STARTED}（已加载已启用）｜{@code RESOLVED}（enabled=false
 * 加载未启用，02 §6.2）｜{@code DISABLED}（运行中由启用改停用——PF4J 侧仍 STARTED，
 * 路由开关已断，D8 仅翻标志不做字节码操作）｜{@code MISSING}（清单有 jar 无）｜
 * {@code REJECTED}（版本硬校验失败，D19）｜{@code FAILED}（异常，error 含原因）。
 */
public final class PluginStatus {

    public static final String STARTED = "STARTED";
    public static final String RESOLVED = "RESOLVED";
    public static final String DISABLED = "DISABLED";
    public static final String MISSING = "MISSING";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";

    public final String id;
    /** manifest Plugin-Version；MISSING 时未知（null） */
    public final String version;
    public final String state;
    /** 已注册 MockPoint 数（含禁用路由点；未加载状态为 0） */
    public final int mockPoints;
    /** null=无错误；04 §6 error 字段显式 serializeNulls */
    public final String error;

    public PluginStatus(String id, String version, String state, int mockPoints,
                        String error) {
        this.id = id;
        this.version = version;
        this.state = state;
        this.mockPoints = mockPoints;
        this.error = error;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("version", version);
        o.addProperty("state", state);
        o.addProperty("mockPoints", mockPoints);
        o.addProperty("error", error);
        return o;
    }

    @Override
    public String toString() {
        return id + "/" + version + "[" + state + " points=" + mockPoints
                + (error == null ? "" : " err=" + error) + "]";
    }
}
