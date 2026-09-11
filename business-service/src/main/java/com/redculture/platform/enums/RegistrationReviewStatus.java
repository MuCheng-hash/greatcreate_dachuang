package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 注册审核状态枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum RegistrationReviewStatus {
    /**
     * 等待注册审核。
     */
    PENDING("pending"),
    /**
     * 已审核通过。
     */
    APPROVED("approved"),
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
