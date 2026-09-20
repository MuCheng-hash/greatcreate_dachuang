package com.redculture.platform.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Agent 生成状态枚举。 */
public enum AgentGenerationStatus {

    /** 回答已正常生成完成。 */
    COMPLETED("completed"),
    /** 回答已生成，但部分能力发生降级。 */
    DEGRADED("degraded"),
    /** 因澄清或业务前置条件未满足而跳过生成。 */
    SKIPPED("skipped");

    /** 枚举对应的序列化值。 */
    private final String value;

    AgentGenerationStatus(String value) {
        this.value = value;
    }

    /**
     * 获取对外序列化使用的状态值。
     *
     * @return 小写状态值
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 根据序列化值或枚举名称解析生成状态，忽略大小写和首尾空白。
     *
     * @param value 待解析的状态值
     * @return 对应状态；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 状态值不受支持时抛出
     */
    @JsonCreator
    public static AgentGenerationStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (AgentGenerationStatus status : values()) {
            if (status.value.equalsIgnoreCase(value.trim())
                    || status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("generationStatus 必须为 completed、degraded 或 skipped");
    }
}
