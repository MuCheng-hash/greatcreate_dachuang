package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TravelMode {
    WALK("walk"),
    BIKE("bike"),
    BUS("bus"),
    DRIVE("drive"),
    MIXED("mixed"),
    UNKNOWN("unknown");

    @EnumValue
    private final String value;
}
