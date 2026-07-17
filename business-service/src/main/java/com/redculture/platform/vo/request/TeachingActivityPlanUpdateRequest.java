package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;

@Data
public class TeachingActivityPlanUpdateRequest {

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

    private ReviewStatus reviewStatus;

    private Boolean active;
}
