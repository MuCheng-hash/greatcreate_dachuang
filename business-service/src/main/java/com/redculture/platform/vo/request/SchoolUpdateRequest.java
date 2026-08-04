package com.redculture.platform.vo.request;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SchoolUpdateRequest {

    private String schoolName;

    private Long provinceRegionId;

    private Long cityRegionId;

    private Long countyRegionId;

    private Long townshipRegionId;

    private String schoolType;

    private String address;

    private String contactPhone;

    private String principalName;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private String intro;

    private Boolean active;
}
