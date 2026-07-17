package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AccountStatus {
    PENDING_ACTIVATION("pending_activation"),
    ACTIVE("active"),
    DISABLED("disabled");

    @EnumValue
    private final String value;
}
