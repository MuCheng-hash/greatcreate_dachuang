package com.redculture.platform.vo.ai;

import com.fasterxml.jackson.annotation.JsonValue;

public enum KnowledgeRetrievalStatus {

    OK("ok"),
    EMPTY("empty"),
    DEGRADED("degraded");

    private final String value;

    KnowledgeRetrievalStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
