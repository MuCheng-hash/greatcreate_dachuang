package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 资料来源类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum SourceType {
    /**
     * 政府来源。
     */
    GOVERNMENT("government"),
    /**
     * 百科来源。
     */
    ENCYCLOPEDIA("encyclopedia"),
    /**
     * 新闻来源。
     */
    NEWS("news"),
    /**
     * 博物馆来源。
     */
    MUSEUM("museum"),
    /**
     * 论文来源。
     */
    PAPER("paper"),
    /**
     * 其他。
     */
    OTHER("other");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
