package com.redculture.platform.vo;

import lombok.Data;

@Data
public class TeachingActivityPlanVO {

    private Long planId;

    private String planCode;

    private Long schoolId;

    private Long resourceId;

    private String theme;

    private String activityType;

    private String suitableGrade;

    private String objectiveText;

    private String activityContent;

    private String preparationText;

    private String safetyText;

    private String expectedOutcome;

    private Integer durationMinutes;
}
