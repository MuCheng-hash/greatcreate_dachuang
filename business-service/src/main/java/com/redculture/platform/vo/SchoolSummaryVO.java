package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SchoolSummaryVO {

    private Long schoolId;

    private String schoolCode;

    private String schoolName;

    private String schoolLevel;

    private String schoolType;

    private String schoolNature;

    private Boolean ruralSchool;

    private Boolean teachingPoint;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private String geoConfidence;

    private Double distanceKm;
}
