package com.redculture.platform.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class TeachingPlanFeedbackReportItemVO {
    private Long generationId;
    private Long schoolId;
    private String schoolName;
    private Long accountId;
    private String teacherName;
    private String theme;
    private String grade;
    private String activityType;
    private String generationStatus;
    private String retrievalStatus;
    private String llmProvider;
    private String llmModel;
    private Long savedPlanId;
    @JsonIgnore
    private String responseJson;
    private Map<String, Object> plan;
    private LocalDateTime createdAt;
    private Long feedbackId;
    private Boolean adopted;
    private Integer rating;
    @JsonIgnore
    private String reasonCodesJson;
    private List<String> reasonCodes;
    private String teacherNote;
    private LocalDateTime submittedAt;
}
