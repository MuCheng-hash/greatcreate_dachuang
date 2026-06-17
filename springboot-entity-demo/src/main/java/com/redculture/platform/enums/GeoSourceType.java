package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GeoSourceType {
    AMAP_POI("amap_poi"),
    MANUAL("manual"),
    SCHOOL_OFFICIAL("school_official"),
    GOVERNMENT_DOC("government_doc"),
    SATELLITE_FIX("satellite_fix"),
    OTHER("other");

    @EnumValue
    private final String value;
}
