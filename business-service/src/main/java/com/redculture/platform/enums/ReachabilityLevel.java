package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ReachabilityLevel {
    NEAR("near"),
    MEDIUM("medium"),
    FAR("far"),
    VERY_FAR("very_far"),
    UNKNOWN("unknown");

    @EnumValue
    private final String value;
}
