package com.redculture.platform.vo;

import com.redculture.platform.common.PageResult;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class TeachingPlanFeedbackReportVO {
    private long generationCount;
    private long feedbackCount;
    private BigDecimal feedbackRate;
    private long adoptedCount;
    private long notAdoptedCount;
    private BigDecimal adoptionRate;
    private BigDecimal averageRating;
    private Map<Integer, Long> ratingDistribution = new LinkedHashMap<>();
    private Map<String, Long> reasonDistribution = new LinkedHashMap<>();
    private PageResult<TeachingPlanFeedbackReportItemVO> details;
}
