package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ResourceCategory;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ResourceUpdateRequest {

    private String resourceName;

    private String resourceAlias;

    private ResourceCategory resourceCategory;

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

    private Boolean active;
}
