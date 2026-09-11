package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 地点与事件关系类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum SiteEventRelationType {
    /**
     * 发生于。
     */
    OCCURRED_AT("occurred_at"),
    /**
     * 一般关联。
     */
    RELATED_TO("related_to"),
    /**
     * 纪念于。
     */
    MEMORIALIZED_AT("memorialized_at");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
