package com.redculture.platform.vo;

import lombok.Data;

@Data
public class SchoolResourceCandidateVO {

    private Long relId;

    private Long schoolId;

    private Long resourceId;

    private Boolean alreadyLinked;

    private Integer distanceMeters;

    private String relationType;

    private String recommendedTravelMode;

    private Integer estimatedDurationMinutes;

    private String reachabilityLevel;

    private Integer priorityLevel;

    private String educationThemeSummary;

    private LocalEduResourceSummaryVO resource;
}
