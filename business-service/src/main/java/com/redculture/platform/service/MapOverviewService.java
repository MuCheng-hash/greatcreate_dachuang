package com.redculture.platform.service;

import com.redculture.platform.vo.NearbyResourceVO;
import com.redculture.platform.vo.MapOverviewVO;

import java.math.BigDecimal;

public interface MapOverviewService {

    MapOverviewVO getOverviewByRegionId(Long regionId);

    NearbyResourceVO getNearbyResources(BigDecimal longitude, BigDecimal latitude, Double radiusKm, Integer limit);
}
