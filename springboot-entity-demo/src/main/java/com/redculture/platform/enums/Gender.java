package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Gender {
    MALE("male"),
    FEMALE("female"),
    UNKNOWN("unknown");

    @EnumValue
    private final String value;
}
