package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SourceType {
    GOVERNMENT("government"),
    ENCYCLOPEDIA("encyclopedia"),
    NEWS("news"),
    MUSEUM("museum"),
    PAPER("paper"),
    OTHER("other");

    @EnumValue
    private final String value;
}
