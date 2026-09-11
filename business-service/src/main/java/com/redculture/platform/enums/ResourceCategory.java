package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 教育资源分类枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum ResourceCategory {
    /**
     * 红色文化。
     */
    RED_CULTURE("red_culture"),
    /**
     * 非物质文化遗产。
     */
    INTANGIBLE_CULTURE("intangible_culture"),
    /**
     * 传统文化。
     */
    TRADITIONAL_CULTURE("traditional_culture"),
    /**
     * 地方历史。
     */
    LOCAL_HISTORY("local_history"),
    /**
     * 公共文化。
     */
    PUBLIC_CULTURE("public_culture"),
    /**
     * 劳动教育。
     */
    LABOR_EDUCATION("labor_education"),
    /**
     * 公益服务。
     */
    PUBLIC_WELFARE("public_welfare"),
    /**
     * 生态文明。
     */
    ECOLOGICAL_CIVILIZATION("ecological_civilization"),
    /**
     * 爱国主义教育基地。
     */
    PATRIOTISM_BASE("patriotism_base"),
    /**
     * 社会实践。
     */
    SOCIAL_PRACTICE("social_practice"),
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
     * 根据持久化值解析教育资源分类。
     *
     * @param value 枚举持久化值
     * @return 匹配的枚举值；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 输入值不受支持时抛出
     */
    @JsonCreator
    public static ResourceCategory fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (ResourceCategory category : values()) {
            if (category.value.equalsIgnoreCase(normalized) || category.name().equalsIgnoreCase(normalized)) {
                return category;
            }
        }
        throw new IllegalArgumentException("unsupported resourceCategory: " + value);
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
