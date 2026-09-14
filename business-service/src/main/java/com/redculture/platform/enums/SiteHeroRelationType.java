package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 地点与英雄人物关系类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum SiteHeroRelationType {
    /**
     * 出生于。
     */
    BORN_IN("born_in"),
    /**
     * 战斗于。
     */
    FOUGHT_IN("fought_in"),
    /**
     * 被纪念。
     */
    MEMORIALIZED("memorialized"),
    /**
     * 曾到访。
     */
    VISITED("visited"),
    /**
     * 一般关联。
     */
    RELATED_TO("related_to");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
