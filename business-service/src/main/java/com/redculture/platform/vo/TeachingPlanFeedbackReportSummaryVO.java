package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TeachingPlanFeedbackReportSummaryVO {
    private long generationCount;
    private long feedbackCount;
    private long adoptedCount;
    private long notAdoptedCount;
    private BigDecimal averageRating;
    private long ratingOneCount;
    private long ratingTwoCount;
    private long ratingThreeCount;
    private long ratingFourCount;
    private long ratingFiveCount;
}
