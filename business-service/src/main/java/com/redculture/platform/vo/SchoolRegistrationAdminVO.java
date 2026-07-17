package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SchoolRegistrationAdminVO {

    private Long registrationId;

    private String applyAccount;

    private String contactName;

    private String contactPhone;

    private String contactEmail;

    private String schoolName;

    private String schoolAlias;

    private String schoolLevel;

    private String schoolType;

    private String schoolNature;

    private Long countyRegionId;

    private Long townshipRegionId;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private String geoSourceType;

    private String geoConfidence;

    private String intro;

    private String reviewStatus;

    private String reviewRemark;

    private String reviewedBy;

    private LocalDateTime reviewedAt;

    private Long linkedSchoolId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
