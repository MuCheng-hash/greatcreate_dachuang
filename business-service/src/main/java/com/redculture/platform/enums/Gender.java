package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 性别枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum Gender {
    /**
     * 男性。
     */
    MALE("male"),
    /**
     * 女性。
     */
    FEMALE("female"),
    /**
     * 未知或未确定。
     */
    UNKNOWN("unknown");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
