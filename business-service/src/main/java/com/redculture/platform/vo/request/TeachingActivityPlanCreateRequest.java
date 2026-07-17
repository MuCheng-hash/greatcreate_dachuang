package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import lombok.Data;

@Data
public class TeachingActivityPlanCreateRequest {

    private String planCode;

    private Long schoolId;

    private Long resourceId;

    private String theme;

    private ActivityType activityType;

    private String suitableGrade;

    private String objectiveText;

    private String activityContent;

    private String preparationText;

    private String safetyText;

    private String expectedOutcome;

    private Integer durationMinutes;

    private Long sourceId;
}
