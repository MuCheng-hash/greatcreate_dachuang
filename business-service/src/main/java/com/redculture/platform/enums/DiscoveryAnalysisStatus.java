package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DiscoveryAnalysisStatus {
    UNANALYZED("unanalyzed"),
    COMPLETED("completed"),
    FAILED("failed");

    @EnumValue
    private final String value;
}
