package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SiteHeroRelationType {
    BORN_IN("born_in"),
    FOUGHT_IN("fought_in"),
    MEMORIALIZED("memorialized"),
    VISITED("visited"),
    RELATED_TO("related_to");

    @EnumValue
    private final String value;
}
