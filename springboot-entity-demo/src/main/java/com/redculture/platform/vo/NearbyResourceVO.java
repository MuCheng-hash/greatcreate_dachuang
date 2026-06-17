package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class NearbyResourceVO {

    private BigDecimal currentLongitude;

    private BigDecimal currentLatitude;

    private Double radiusKm;

    private Integer totalCount;

    private List<NearbyResourceItemVO> resources;
}
