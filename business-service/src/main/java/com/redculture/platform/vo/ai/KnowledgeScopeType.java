package com.redculture.platform.vo.ai;

import java.util.Locale;

public enum KnowledgeScopeType {

    SCHOOL,
    REGION,
    RESOURCE;

    public static KnowledgeScopeType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("scopeType must be SCHOOL, REGION or RESOURCE");
        }
    }
}
