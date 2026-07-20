package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ResourceCategory;
import lombok.Data;

@Data
public class DiscoveryCandidateReviewRequest {
    private String resourceName;
    private ResourceCategory resourceCategory;
    private String resourceSubcategory;
    private String educationValue;
    private String activitySuggestion;
    private String targetGrade;
    private String reviewerName;
    private String reviewRemark;
}
