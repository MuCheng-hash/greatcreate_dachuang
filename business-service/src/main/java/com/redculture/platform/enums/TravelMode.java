package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 出行方式枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum TravelMode {
    /**
     * 步行。
     */
    WALK("walk"),
    /**
     * 骑行。
     */
    BIKE("bike"),
    /**
     * 公共交通。
     */
    BUS("bus"),
    /**
     * 驾车。
     */
    DRIVE("drive"),
    /**
     * 混合出行方式。
     */
    MIXED("mixed"),
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
     * 根据持久化值解析出行方式。
     *
     * @param value 枚举持久化值
     * @return 匹配的枚举值；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 输入值不受支持时抛出
     */
    @JsonCreator
    public static TravelMode fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (TravelMode mode : values()) {
            if (mode.value.equalsIgnoreCase(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("不支持的 travelMode：" + value);
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
