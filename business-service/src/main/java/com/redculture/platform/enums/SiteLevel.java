package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SiteLevel {
    NATIONAL("national"),
    PROVINCIAL("provincial"),
    MUNICIPAL("municipal"),
    COUNTY("county"),
    OTHER("other");

    @EnumValue
    private final String value;
}
