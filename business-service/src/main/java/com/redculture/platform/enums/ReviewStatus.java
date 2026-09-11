package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 内容审核状态枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum ReviewStatus {
    /**
     * 草稿。
     */
    DRAFT("draft"),
    /**
     * 已提交，等待审核。
     */
    PENDING("pending"),
    /**
     * 已审核通过。
     */
    APPROVED("approved"),
    /**
     * 已采纳。
     */
    ADOPTED("adopted"),
    /**
     * 已驳回。
     */
    REJECTED("rejected");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;

    /**
     * 根据持久化值解析内容审核状态。
     *
     * @param value 枚举持久化值
     * @return 匹配的枚举值；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 输入值不受支持时抛出
     */
    @JsonCreator
    public static ReviewStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (ReviewStatus status : values()) {
            if (status.value.equalsIgnoreCase(normalized) || status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unsupported reviewStatus: " + value);
    }

    /**
     * 获取写入数据库或接口的枚举值。
     *
     * @return 枚举持久化值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
