package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OperationType {
    INSERT("insert"),
    UPDATE("update"),
    DELETE("delete"),
    REVIEW("review");

    @EnumValue
    private final String value;
}
