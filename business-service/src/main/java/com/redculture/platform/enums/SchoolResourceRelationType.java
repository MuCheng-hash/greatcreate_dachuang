package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 学校与资源关系类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum SchoolResourceRelationType {
    /**
     * 周边资源。
     */
    NEARBY("nearby"),
    /**
     * 合作资源。
     */
    COOPERATION("cooperation"),
    /**
     * 实践基地。
     */
    PRACTICE("practice"),
    /**
     * 课程支持。
     */
    CURRICULUM_SUPPORT("curriculum_support"),
    /**
     * 志愿服务基地。
     */
    VOLUNTEER_BASE("volunteer_base"),
    /**
     * 研学路线。
     */
    RESEARCH_ROUTE("research_route"),
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
     * 根据持久化值解析学校与资源关系类型。
     *
     * @param value 枚举持久化值
     * @return 匹配的枚举值；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 输入值不受支持时抛出
     */
    @JsonCreator
    public static SchoolResourceRelationType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (SchoolResourceRelationType type : values()) {
            if (type.value.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported relationType: " + value);
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
