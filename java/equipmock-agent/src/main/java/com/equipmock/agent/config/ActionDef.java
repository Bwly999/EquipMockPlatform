package com.equipmock.agent.config;

import com.google.gson.JsonElement;

import java.util.Objects;

/**
 * Action 的不可变解析结果（03 §1）：{type: VALUE|THROW|VOID, value?, exception?, message?}。
 *
 * <p>equals/hashCode 覆盖：快照幂等比较（内容未变不替换）依赖结构相等，
 * gson JsonElement 本身是结构化 equals。
 */
public final class ActionDef {

    public enum Type {VALUE, THROW, VOID}

    public final Type type;
    /** type=VALUE 时有效（配置侧 JSON 字面量，可为 JsonNull 表示显式 null 返回） */
    public final JsonElement value;
    /** type=THROW 时有效（异常 FQCN，加载期已校验格式） */
    public final String exception;
    /** type=THROW 时可选（异常 message） */
    public final String message;

    public ActionDef(Type type, JsonElement value, String exception, String message) {
        this.type = type;
        this.value = value;
        this.exception = exception;
        this.message = message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ActionDef)) {
            return false;
        }
        ActionDef that = (ActionDef) o;
        return type == that.type
                && Objects.equals(value, that.value)
                && Objects.equals(exception, that.exception)
                && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value, exception, message);
    }

    @Override
    public String toString() {
        return "Action{" + type
                + (value != null ? ", value=" + value : "")
                + (exception != null ? ", exception=" + exception : "")
                + (message != null ? ", message='" + message + '\'' : "")
                + '}';
    }
}
