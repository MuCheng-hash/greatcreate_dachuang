package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TeachingPlanFeedbackVO {
    private Long feedbackId;
    private Long generationId;
    private Boolean adopted;
    private Integer rating;
    private List<String> reasonCodes;
    private String teacherNote;
    private Long savedPlanId;
    private LocalDateTime submittedAt;
}
