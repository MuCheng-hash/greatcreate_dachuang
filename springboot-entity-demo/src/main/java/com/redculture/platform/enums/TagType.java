package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TagType {
    THEME("theme"),
    PERIOD("period"),
    REGION("region"),
    EDUCATION("education"),
    ROUTE("route"),
    OTHER("other");

    @EnumValue
    private final String value;
}
