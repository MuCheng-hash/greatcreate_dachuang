package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 学校学段枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum SchoolLevel {
    /**
     * 幼儿园。
     */
    KINDERGARTEN("kindergarten"),
    /**
     * 小学阶段。
     */
    PRIMARY("primary"),
    /**
     * 初中。
     */
    JUNIOR("junior"),
    /**
     * 高中。
     */
    SENIOR("senior"),
    /**
     * 九年一贯制。
     */
    NINE_YEAR("nine_year"),
    /**
     * 十二年一贯制。
     */
    TWELVE_YEAR("twelve_year"),
    /**
     * 职业学校。
     */
    VOCATIONAL("vocational"),
    /**
     * 特殊教育学校。
     */
    SPECIAL("special"),
    /**
     * 其他。
     */
    OTHER("other");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;

    /**
     * 根据持久化值解析学校学段。
     *
     * @param value 枚举持久化值
     * @return 匹配的枚举值；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 输入值不受支持时抛出
     */
    @JsonCreator
    public static SchoolLevel fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (SchoolLevel item : values()) {
            if (item.value.equalsIgnoreCase(normalized) || item.name().equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unsupported schoolLevel: " + value);
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
