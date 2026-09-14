package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 红色地点级别枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum SiteLevel {
    /**
     * 国家级。
     */
    NATIONAL("national"),
    /**
     * 省级。
     */
    PROVINCIAL("provincial"),
    /**
     * 市级。
     */
    MUNICIPAL("municipal"),
    /**
     * 县级。
     */
    COUNTY("county"),
    /**
     * 其他。
     */
    OTHER("other");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
