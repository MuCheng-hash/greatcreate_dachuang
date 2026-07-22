package com.redculture.platform.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AgentGenerationStatus {

    COMPLETED("completed"),
    DEGRADED("degraded"),
    SKIPPED("skipped");

    private final String value;

    AgentGenerationStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

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
        throw new IllegalArgumentException("generationStatus must be completed, degraded or skipped");
    }
}
