package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 标签类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum TagType {
    /**
     * 主题标签。
     */
    THEME("theme"),
    /**
     * 时期标签。
     */
    PERIOD("period"),
    /**
     * 地域标签。
     */
    REGION("region"),
    /**
     * 教育标签。
     */
    EDUCATION("education"),
    /**
     * 路线标签。
     */
    ROUTE("route"),
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
