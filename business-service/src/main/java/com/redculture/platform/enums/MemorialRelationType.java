package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 纪念设施关系类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum MemorialRelationType {
    /**
     * 纪念。
     */
    COMMEMORATES("commemorates"),
    /**
     * 展陈。
     */
    EXHIBITS("exhibits"),
    /**
     * 坐落于。
     */
    LOCATED_AT("located_at"),
    /**
     * 展示。
     */
    DISPLAYS("displays"),
    /**
     * 一般关联。
     */
    RELATED_TO("related_to");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
