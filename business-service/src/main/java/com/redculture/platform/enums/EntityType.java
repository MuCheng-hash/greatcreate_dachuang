package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EntityType {
    SITE("site"),
    HERO("hero"),
    EVENT("event"),
    MEMORIAL("memorial"),
    STORY("story"),
    SCHOOL("school"),
    RESOURCE("resource"),
    ACTIVITY_PLAN("activity_plan");

    @EnumValue
    private final String value;
}
