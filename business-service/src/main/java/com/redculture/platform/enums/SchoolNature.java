package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
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
}
