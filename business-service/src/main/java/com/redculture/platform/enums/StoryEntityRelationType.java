package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 故事与实体关系类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum StoryEntityRelationType {
    /**
     * 以该实体为主题。
     */
    ABOUT("about"),
    /**
     * 正文中提及。
     */
    MENTIONS("mentions"),
    /**
     * 用于教学。
     */
    TEACHES("teaches");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
