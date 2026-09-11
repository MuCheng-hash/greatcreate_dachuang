package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 业务实体类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum EntityType {
    /**
     * 红色文化地点。
     */
    SITE("site"),
    /**
     * 英雄人物。
     */
    HERO("hero"),
    /**
     * 历史事件。
     */
    EVENT("event"),
    /**
     * 纪念设施。
     */
    MEMORIAL("memorial"),
    /**
     * 红色故事。
     */
    STORY("story"),
    /**
     * 学校。
     */
    SCHOOL("school"),
    /**
     * 教育资源。
     */
    RESOURCE("resource"),
    /**
     * 教学活动方案。
     */
    ACTIVITY_PLAN("activity_plan");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
