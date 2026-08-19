package com.equipmock.agent.config;

import com.google.gson.JsonElement;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 匹配规则的不可变解析结果（03 §1）：
 * {matchType: FULL_MATCH|PATTERN_MATCH, args/argsPattern, action}。
 *
 * <p>PATTERN_MATCH 的正则在加载期预编译缓存于本对象（03 §4 性能要求）；
 * equals 比较正则源串（Pattern 自身无结构 equals）。
 */
public final class MockRule {

    public enum MatchType {FULL_MATCH, PATTERN_MATCH}

    public final MatchType matchType;
    /** FULL_MATCH：配置侧参数 JSON 数组（逐元素） */
    public final List<JsonElement> args;
    /** PATTERN_MATCH：正则源串（equals/幂等比较用） */
    public final List<String> patternSources;
    /** PATTERN_MATCH：预编译正则（与 patternSources 一一对应） */
    public final List<Pattern> patterns;
    public final ActionDef action;
    /** 人读备注（可选） */
    public final String description;

    public MockRule(MatchType matchType, List<JsonElement> args,
                    List<String> patternSources, List<Pattern> patterns,
                    ActionDef action, String description) {
        this.matchType = matchType;
        this.args = args;
        this.patternSources = patternSources;
        this.patterns = patterns;
        this.action = action;
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MockRule)) {
            return false;
        }
        MockRule that = (MockRule) o;
        return matchType == that.matchType
                && Objects.equals(args, that.args)
                && Objects.equals(patternSources, that.patternSources)
                && Objects.equals(action, that.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(matchType, args, patternSources, action);
    }
}
