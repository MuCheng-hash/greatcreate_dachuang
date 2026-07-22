package com.redculture.platform.vo.discovery;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ApprovedResourceDetailVO {
    private Long resourceId;
    private String resourceName;
    private String resourceCategory;
    private String resourceSubcategory;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String organizationName;
    private String contactPhone;
    private String openingTimeDesc;
    private Boolean reservationRequired;
    private Integer recommendedVisitMinutes;
    private String intro;
    private String educationValue;
    private String activitySuggestion;
    private String targetGrade;
    private String safetyNote;
    private String externalProvider;
    private String externalPlaceId;
    private LocalDateTime sourceCheckedAt;
    private Integer distanceMeters;
    private String recommendedTravelMode;
    private Integer estimatedDurationMinutes;
    private String educationThemeSummary;
    private String verificationStatus = "approved";
}
