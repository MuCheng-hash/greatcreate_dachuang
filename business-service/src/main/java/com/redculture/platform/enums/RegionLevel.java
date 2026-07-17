package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RegionLevel {
    PROVINCE("province"),
    CITY("city"),
    COUNTY("county"),
    TOWNSHIP("township"),
    VILLAGE("village");

    @EnumValue
    private final String value;
}
