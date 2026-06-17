package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EmbeddingStatus {
    PENDING("pending"),
    DONE("done"),
    FAILED("failed");

    @EnumValue
    private final String value;
}
