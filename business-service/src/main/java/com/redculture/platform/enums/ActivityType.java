package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
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
}
