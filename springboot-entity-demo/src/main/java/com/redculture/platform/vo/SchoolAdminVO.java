package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SchoolAdminVO {

    private Long schoolId;

    private String schoolCode;

    private String schoolName;

    private String schoolAlias;

    private Long regionId;

    private Long countyRegionId;

    private Long townshipRegionId;

    private Long villageRegionId;

    private String schoolLevel;

    private String schoolType;

    private String schoolNature;

    private Boolean ruralSchool;

    private Boolean teachingPoint;

    private String address;

    private String postcode;

    private String contactPhone;

    private String principalName;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private String geoSourceType;

    private String poiName;

    private String poiAddress;

    private String poiType;

    private String geoConfidence;

    private Boolean geoVerified;

    private String intro;

    private Long sourceId;

    private String reviewStatus;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
