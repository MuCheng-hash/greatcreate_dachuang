package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 适用年龄阶段枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum AgeGroup {
    /**
     * 小学阶段。
     */
    PRIMARY("primary"),
    /**
     * 初中阶段。
     */
    MIDDLE("middle"),
    /**
     * 高中阶段。
     */
    HIGH("high"),
    /**
     * 高校阶段。
     */
    COLLEGE("college"),
    /**
     * 通用。
     */
    GENERAL("general");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
