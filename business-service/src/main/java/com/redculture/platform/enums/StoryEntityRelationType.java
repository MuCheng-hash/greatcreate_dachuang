package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum StoryEntityRelationType {
    ABOUT("about"),
    MENTIONS("mentions"),
    TEACHES("teaches");

    @EnumValue
    private final String value;
}
