package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SchoolLevel {
    KINDERGARTEN("kindergarten"),
    PRIMARY("primary"),
    JUNIOR("junior"),
    SENIOR("senior"),
    NINE_YEAR("nine_year"),
    TWELVE_YEAR("twelve_year"),
    VOCATIONAL("vocational"),
    SPECIAL("special"),
    OTHER("other");

    @EnumValue
    private final String value;

    @JsonCreator
    public static SchoolLevel fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (SchoolLevel item : values()) {
            if (item.value.equalsIgnoreCase(normalized) || item.name().equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unsupported schoolLevel: " + value);
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
