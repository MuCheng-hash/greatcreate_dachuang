package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SchoolResourceRelAdminVO {

    private Long relId;

    private Long schoolId;

    private String schoolName;

    private Long resourceId;

    private String resourceName;

    private String relationType;

    private Integer distanceMeters;

    private String recommendedTravelMode;

    private Integer estimatedDurationMinutes;

    private String reachabilityLevel;

    private Integer priorityLevel;

    private String educationThemeSummary;

    private Long sourceId;

    private String reviewStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
