package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 交通可达性等级枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum ReachabilityLevel {
    /**
     * 较近。
     */
    NEAR("near"),
    /**
     * 中等。
     */
    MEDIUM("medium"),
    /**
     * 较远。
     */
    FAR("far"),
    /**
     * 很远。
     */
    VERY_FAR("very_far"),
    /**
     * 未知或未确定。
     */
    UNKNOWN("unknown");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;

    /**
     * 根据持久化值解析交通可达性等级。
     *
     * @param value 枚举持久化值
     * @return 匹配的枚举值；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 输入值不受支持时抛出
     */
    @JsonCreator
    public static ReachabilityLevel fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (ReachabilityLevel level : values()) {
            if (level.value.equalsIgnoreCase(normalized) || level.name().equalsIgnoreCase(normalized)) {
                return level;
            }
        }
        throw new IllegalArgumentException("unsupported reachabilityLevel: " + value);
    }

    /**
     * 获取写入数据库或接口的枚举值。
     *
     * @return 枚举持久化值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
