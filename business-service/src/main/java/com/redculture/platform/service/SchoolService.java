package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.baomidou.mybatisplus.extension.service.IService;
import com.redculture.platform.entity.School;
import com.redculture.platform.vo.SchoolAdminVO;
import com.redculture.platform.vo.request.SchoolCreateRequest;
import com.redculture.platform.vo.request.SchoolUpdateRequest;

public interface SchoolService extends IService<School> {

    SchoolAdminVO createSchool(SchoolCreateRequest request);

    SchoolAdminVO updateSchool(Long schoolId, SchoolUpdateRequest request);

    void deleteSchool(Long schoolId);

    SchoolAdminVO getSchoolAdminDetail(Long schoolId);

    PageResult<SchoolAdminVO> pageSchools(String keyword,
                                          Long provinceRegionId,
                                          Long cityRegionId,
                                          Long countyRegionId,
                                          Long townshipRegionId,
                                          Long pageNum,
                                          Long pageSize);
}
