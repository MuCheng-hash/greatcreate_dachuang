package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ActivityType {
    CLASSROOM("classroom"),
    FIELD_TRIP("field_trip"),
    VOLUNTEER_SERVICE("volunteer_service"),
    RESEARCH_STUDY("research_study"),
    LABOR_PRACTICE("labor_practice"),
    CLUB_ACTIVITY("club_activity"),
    SCHOOL_BASED_COURSE("school_based_course"),
    OTHER("other");

    @EnumValue
    private final String value;

    @JsonCreator
    public static ActivityType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (ActivityType item : values()) {
            if (item.value.equalsIgnoreCase(normalized) || item.name().equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unsupported activityType: " + value);
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
