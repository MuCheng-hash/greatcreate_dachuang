package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventHeroRelationType {
    PARTICIPANT("participant"),
    LEADER("leader"),
    WITNESS("witness"),
    MARTYR("martyr"),
    RELATED_TO("related_to");

    @EnumValue
    private final String value;
}
