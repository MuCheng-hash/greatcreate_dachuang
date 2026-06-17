package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class LocalEduResourceSummaryVO {

    private Long resourceId;

    private String resourceCode;

    private String resourceName;

    private String resourceCategory;

    private String resourceSubcategory;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private String intro;

    private String educationValue;

    private String targetGrade;
}
