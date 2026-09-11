package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 账号状态枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum AccountStatus {
    /**
     * 等待激活。
     */
    PENDING_ACTIVATION("pending_activation"),
    /**
     * 正常启用。
     */
    ACTIVE("active"),
    /**
     * 已禁用。
     */
    DISABLED("disabled");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
