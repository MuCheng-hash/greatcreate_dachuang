package com.redculture.platform.service;

import com.redculture.platform.vo.TownBoundaryVO;
import com.redculture.platform.vo.TownLocateResponse;
import com.redculture.platform.vo.TownMapDetailVO;

import java.math.BigDecimal;
import java.util.List;

public interface TownMapService {

    TownLocateResponse locateTown(BigDecimal longitude, BigDecimal latitude);

    TownMapDetailVO getTownMapDetail(Long regionId);

    TownBoundaryVO getTownBoundary(Long regionId);

    List<TownBoundaryVO> listTownBoundaries();

    List<TownBoundaryVO> listRegionBoundaries(String regionLevel, Long parentRegionId);

    List<TownBoundaryVO> listRegionBoundaries(String regionLevel, Long parentRegionId, Long ancestorRegionId);
}
