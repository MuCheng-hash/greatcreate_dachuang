package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TeachingPlanGenerationVO {
    private Long generationId;
    private Long schoolId;
    private String threadId;
    private String theme;
    private String grade;
    private String activityType;
    private Integer durationMinutes;
    private Boolean practiceRequired;
    private String generationStatus;
    private String retrievalStatus;
    private String llmProvider;
    private String llmModel;
    private Long savedPlanId;
    private Map<String, Object> plan;
    private TeachingPlanFeedbackVO feedback;
    private LocalDateTime createdAt;
}
