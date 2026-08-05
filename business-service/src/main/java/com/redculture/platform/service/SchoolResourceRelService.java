package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.baomidou.mybatisplus.extension.service.IService;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.vo.SchoolResourceCandidateResultVO;
import com.redculture.platform.vo.SchoolResourceRelAdminVO;
import com.redculture.platform.vo.request.SchoolResourceRelBatchCreateRequest;
import com.redculture.platform.vo.request.SchoolResourceRelCreateRequest;
import com.redculture.platform.vo.request.SchoolResourceRelUpdateRequest;

public interface SchoolResourceRelService extends IService<SchoolResourceRel> {

    SchoolResourceRelAdminVO createRelation(SchoolResourceRelCreateRequest request);

    SchoolResourceRelAdminVO updateRelation(Long relId, SchoolResourceRelUpdateRequest request);

    boolean deleteRelation(Long relId);

    SchoolResourceCandidateResultVO listResourceCandidates(Long schoolId, Double radiusKm);

    SchoolResourceCandidateResultVO batchCreateRelations(Long schoolId, SchoolResourceRelBatchCreateRequest request);

    PageResult<SchoolResourceRelAdminVO> listBySchoolId(Long schoolId, Long pageNum, Long pageSize);

    PageResult<SchoolResourceRelAdminVO> listByResourceId(Long resourceId, Long pageNum, Long pageSize);
}
