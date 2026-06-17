package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ResourceCategory {
    RED_CULTURE("red_culture"),
    INTANGIBLE_CULTURE("intangible_culture"),
    TRADITIONAL_CULTURE("traditional_culture"),
    LOCAL_HISTORY("local_history"),
    PUBLIC_CULTURE("public_culture"),
    LABOR_EDUCATION("labor_education"),
    PUBLIC_WELFARE("public_welfare"),
    ECOLOGICAL_CIVILIZATION("ecological_civilization"),
    PATRIOTISM_BASE("patriotism_base"),
    SOCIAL_PRACTICE("social_practice"),
    OTHER("other");

    @EnumValue
    private final String value;
}
