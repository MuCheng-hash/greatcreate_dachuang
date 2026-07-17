package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TeachingActivityPlanAdminVO {

    private Long planId;

    private String planCode;

    private Long schoolId;

    private String schoolName;

    private Long resourceId;

    private String resourceName;

    private String theme;

    private String activityType;

    private String suitableGrade;

    private String objectiveText;

    private String activityContent;

    private String preparationText;

    private String safetyText;

    private String expectedOutcome;

    private Integer durationMinutes;

    private Long sourceId;

    private String reviewStatus;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
