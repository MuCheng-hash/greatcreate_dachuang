package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 历史事件与英雄人物关系类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum EventHeroRelationType {
    /**
     * 参与者。
     */
    PARTICIPANT("participant"),
    /**
     * 领导者。
     */
    LEADER("leader"),
    /**
     * 见证者。
     */
    WITNESS("witness"),
    /**
     * 烈士。
     */
    MARTYR("martyr"),
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
