package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.baomidou.mybatisplus.extension.service.IService;
import com.redculture.platform.entity.School;
import com.redculture.platform.vo.SchoolAdminVO;
import com.redculture.platform.vo.request.SchoolUpdateRequest;
import com.redculture.platform.vo.request.SchoolCsvImportRequest;
import com.redculture.platform.vo.SchoolImportResultVO;
import org.springframework.web.multipart.MultipartFile;

public interface SchoolService extends IService<School> {

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

    SchoolImportResultVO importCsv(SchoolCsvImportRequest request);

    SchoolImportResultVO importExcel(MultipartFile file);

    byte[] buildImportTemplate();
}
