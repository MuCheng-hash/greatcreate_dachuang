package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
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
}
