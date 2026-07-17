package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ResourceAdminVO {

    private Long resourceId;

    private String resourceCode;

    private String resourceName;

    private String resourceAlias;

    private String resourceCategory;

    private String resourceSubcategory;

    private Long regionId;

    private Long countyRegionId;

    private Long townshipRegionId;

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

    private Long sourceId;

    private String reviewStatus;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
