package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
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

    @JsonCreator
    public static GeoSourceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (GeoSourceType item : values()) {
            if (item.value.equalsIgnoreCase(normalized) || item.name().equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unsupported geoSourceType: " + value);
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
