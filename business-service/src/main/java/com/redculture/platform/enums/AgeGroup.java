package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AgeGroup {
    PRIMARY("primary"),
    MIDDLE("middle"),
    HIGH("high"),
    COLLEGE("college"),
    GENERAL("general");

    @EnumValue
    private final String value;
}
