package com.redculture.platform.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public enum TeachingPlanFeedbackReason {
    GRADE_MISMATCH,
    THEME_DEVIATION,
    RESOURCE_MISMATCH,
    HARD_TO_IMPLEMENT,
    DURATION_UNREASONABLE,
    SAFETY_RISK,
    CONTENT_INCOMPLETE,
    UNCLEAR_EXPRESSION,
    OTHER;

    private static final Set<String> CODES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    public static boolean supports(String code) {
        return CODES.contains(code);
    }

    public static List<String> orderedCodes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
