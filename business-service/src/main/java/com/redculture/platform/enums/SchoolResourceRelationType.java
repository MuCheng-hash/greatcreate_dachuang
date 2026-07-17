package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SchoolResourceRelationType {
    NEARBY("nearby"),
    COOPERATION("cooperation"),
    PRACTICE("practice"),
    CURRICULUM_SUPPORT("curriculum_support"),
    VOLUNTEER_BASE("volunteer_base"),
    RESEARCH_ROUTE("research_route"),
    OTHER("other");

    @EnumValue
    private final String value;
}
