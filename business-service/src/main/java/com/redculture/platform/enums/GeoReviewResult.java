package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 地理信息复核结果枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum GeoReviewResult {
    /**
     * 等待人工复核。
     */
    PENDING("pending"),
    /**
     * 已确认。
     */
    CONFIRMED("confirmed"),
    /**
     * 已修正。
     */
    CORRECTED("corrected"),
    /**
     * 已驳回。
     */
    REJECTED("rejected");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
