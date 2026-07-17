package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import lombok.Data;

@Data
public class TeachingPlanGenerateRequest {

    private Long schoolId;

    private String grade;

    private String theme;

    private ActivityType activityType;

    private Integer durationMinutes;

    private Boolean practiceRequired;
}
