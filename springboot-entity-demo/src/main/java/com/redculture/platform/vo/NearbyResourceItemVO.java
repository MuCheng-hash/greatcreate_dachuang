package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class NearbyResourceItemVO {

    private String resourceType;

    private Long resourceId;

    private String resourceName;

    private Long regionId;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private Double distanceKm;

    private String openingTimeDesc;
}
