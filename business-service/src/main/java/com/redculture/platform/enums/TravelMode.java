package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
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

    @JsonCreator
    public static TravelMode fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (TravelMode mode : values()) {
            if (mode.value.equalsIgnoreCase(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unsupported travelMode: " + value);
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
