package com.redculture.platform.vo.ai;

import com.fasterxml.jackson.annotation.JsonValue;

/** 知识检索状态枚举。 */
public enum KnowledgeRetrievalStatus {

    /** 检索正常完成并获得至少一类有效证据。 */
    OK("ok"),
    /** 检索正常完成，但没有命中证据。 */
    EMPTY("empty"),
    /** 部分检索能力不可用，但仍可能包含可用证据。 */
    DEGRADED("degraded");

    /** 枚举对应的序列化值。 */
    private final String value;

    KnowledgeRetrievalStatus(String value) {
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
}
