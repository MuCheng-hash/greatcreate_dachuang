package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SchoolNature {
    PUBLIC("public"),
    PRIVATE("private"),
    OTHER("other");

    @EnumValue
    private final String value;

    @JsonCreator
    public static SchoolNature fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (SchoolNature item : values()) {
            if (item.value.equalsIgnoreCase(normalized) || item.name().equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unsupported schoolNature: " + value);
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
