package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 向量嵌入状态枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum EmbeddingStatus {
    /**
     * 等待生成向量。
     */
    PENDING("pending"),
    /**
     * 向量生成完成。
     */
    DONE("done"),
    /**
     * 向量生成失败。
     */
    FAILED("failed");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
