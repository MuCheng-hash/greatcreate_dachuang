package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 数据操作类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum OperationType {
    /**
     * 新增。
     */
    INSERT("insert"),
    /**
     * 更新。
     */
    UPDATE("update"),
    /**
     * 删除。
     */
    DELETE("delete"),
    /**
     * 审核。
     */
    REVIEW("review");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
