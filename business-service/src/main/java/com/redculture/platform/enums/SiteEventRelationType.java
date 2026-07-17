package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SiteEventRelationType {
    OCCURRED_AT("occurred_at"),
    RELATED_TO("related_to"),
    MEMORIALIZED_AT("memorialized_at");

    @EnumValue
    private final String value;
}
