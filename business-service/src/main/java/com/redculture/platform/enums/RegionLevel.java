package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 行政区划层级枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum RegionLevel {
    /**
     * 省级。
     */
    PROVINCE("province"),
    /**
     * 市级。
     */
    CITY("city"),
    /**
     * 县级。
     */
    COUNTY("county"),
    /**
     * 乡镇级。
     */
    TOWNSHIP("township"),
    /**
     * 村级。
     */
    VILLAGE("village");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
