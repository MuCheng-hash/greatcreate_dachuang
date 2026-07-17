package com.redculture.platform.vo;

import lombok.Data;

@Data
public class SchoolResourceItemVO {

    private Long schoolId;

    private Long resourceId;

    private String relationType;

    private Integer distanceMeters;

    private String recommendedTravelMode;

    private Integer estimatedDurationMinutes;

    private String reachabilityLevel;

    private Integer priorityLevel;

    private String educationThemeSummary;

    private LocalEduResourceSummaryVO resource;
}
