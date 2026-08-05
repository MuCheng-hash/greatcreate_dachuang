package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ReviewStatus {
    DRAFT("draft"),
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected");

    @EnumValue
    private final String value;

    @JsonCreator
    public static ReviewStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (ReviewStatus status : values()) {
            if (status.value.equalsIgnoreCase(normalized) || status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unsupported reviewStatus: " + value);
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
