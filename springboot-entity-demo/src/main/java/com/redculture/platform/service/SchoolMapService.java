package com.redculture.platform.service;

import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolSummaryVO;

import java.math.BigDecimal;
import java.util.List;

public interface SchoolMapService {

    List<SchoolSummaryVO> listSchools(Long countyRegionId,
                                     Long townshipRegionId,
                                     String keyword,
                                     Integer limit);

    List<SchoolSummaryVO> findNearbySchools(BigDecimal longitude,
                                            BigDecimal latitude,
                                            Double radiusKm,
                                            Integer limit);

    SchoolMapDetailVO getSchoolDetail(Long schoolId);
}
