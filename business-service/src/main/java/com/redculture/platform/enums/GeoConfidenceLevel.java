package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 地理坐标可信度枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum GeoConfidenceLevel {
    /**
     * 高可信度。
     */
    HIGH("high"),
    /**
     * 中等可信度。
     */
    MEDIUM("medium"),
    /**
     * 低可信度。
     */
    LOW("low"),
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
     * 根据持久化值解析地理坐标可信度。
     *
     * @param value 枚举持久化值
     * @return 匹配的枚举值；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 输入值不受支持时抛出
     */
    @JsonCreator
    public static GeoConfidenceLevel fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (GeoConfidenceLevel item : values()) {
            if (item.value.equalsIgnoreCase(normalized) || item.name().equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unsupported geoConfidence: " + value);
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
