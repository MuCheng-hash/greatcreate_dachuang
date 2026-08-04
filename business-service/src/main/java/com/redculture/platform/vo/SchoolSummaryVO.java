package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SchoolSummaryVO {

    private Long schoolId;

    private String schoolName;

    private Long provinceRegionId;

    private Long cityRegionId;

    private Long countyRegionId;

    private Long townshipRegionId;

    private String schoolType;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private Double distanceKm;
}
