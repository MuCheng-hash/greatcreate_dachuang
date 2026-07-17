package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GeoReviewResult {
    PENDING("pending"),
    CONFIRMED("confirmed"),
    CORRECTED("corrected"),
    REJECTED("rejected");

    @EnumValue
    private final String value;
}
