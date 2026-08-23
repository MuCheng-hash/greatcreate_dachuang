package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TeachingActivityPlanCreateRequest {

    private String planCode;

    private Long schoolId;

    private Long resourceId;

    private List<Long> resourceIds = new ArrayList<>();

    private Long ownerAccountId;

    private String planPayload;

    private String generationSource;

    private Long aiRunId;

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
