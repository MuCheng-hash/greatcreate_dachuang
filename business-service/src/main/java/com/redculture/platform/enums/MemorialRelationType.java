package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MemorialRelationType {
    COMMEMORATES("commemorates"),
    EXHIBITS("exhibits"),
    LOCATED_AT("located_at"),
    DISPLAYS("displays"),
    RELATED_TO("related_to");

    @EnumValue
    private final String value;
}
