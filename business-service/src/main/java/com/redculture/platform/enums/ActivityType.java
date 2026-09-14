package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 教学活动类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum ActivityType {
    /**
     * 课堂教学。
     */
    CLASSROOM("classroom"),
    /**
     * 实地参访。
     */
    FIELD_TRIP("field_trip"),
    /**
     * 志愿服务。
     */
    VOLUNTEER_SERVICE("volunteer_service"),
    /**
     * 研学活动。
     */
    RESEARCH_STUDY("research_study"),
    /**
     * 劳动实践。
     */
    LABOR_PRACTICE("labor_practice"),
    /**
     * 社团活动。
     */
    CLUB_ACTIVITY("club_activity"),
    /**
     * 校本课程。
     */
    SCHOOL_BASED_COURSE("school_based_course"),
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
     * 获取写入数据库或接口的枚举值。
     *
     * @return 枚举持久化值
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 根据持久化值解析教学活动类型。
     *
     * @param value 枚举持久化值
     * @return 匹配的枚举值；输入为空或无法识别时返回 {@code null}
     */
    @JsonCreator
    public static ActivityType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (ActivityType type : values()) {
            if (type.value.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return null;
    }
}
