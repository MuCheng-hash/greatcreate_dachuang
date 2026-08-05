package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
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

    @JsonCreator
    public static ReachabilityLevel fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (ReachabilityLevel level : values()) {
            if (level.value.equalsIgnoreCase(normalized) || level.name().equalsIgnoreCase(normalized)) {
                return level;
            }
        }
        throw new IllegalArgumentException("unsupported reachabilityLevel: " + value);
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
