package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.baomidou.mybatisplus.extension.service.IService;
import com.redculture.platform.entity.School;
import com.redculture.platform.vo.SchoolAdminVO;
import com.redculture.platform.vo.request.SchoolCreateRequest;
import com.redculture.platform.vo.request.SchoolReviewRequest;
import com.redculture.platform.vo.request.SchoolUpdateRequest;
import com.redculture.platform.enums.ReviewStatus;

public interface SchoolService extends IService<School> {

    SchoolAdminVO createSchool(SchoolCreateRequest request);

    SchoolAdminVO updateSchool(Long schoolId, SchoolUpdateRequest request);

    SchoolAdminVO getSchoolAdminDetail(Long schoolId);

    PageResult<SchoolAdminVO> pageSchools(String keyword,
                                          Long countyRegionId,
                                          Long townshipRegionId,
                                          String schoolLevel,
                                          ReviewStatus reviewStatus,
                                          Long pageNum,
                                          Long pageSize);

    SchoolAdminVO submitReview(Long schoolId);

    SchoolAdminVO approve(Long schoolId, SchoolReviewRequest request);

    SchoolAdminVO reject(Long schoolId, SchoolReviewRequest request);
}
