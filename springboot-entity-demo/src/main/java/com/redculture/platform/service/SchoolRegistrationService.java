package com.redculture.platform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.SchoolRegistration;
import com.redculture.platform.enums.RegistrationReviewStatus;
import com.redculture.platform.vo.SchoolRegistrationAdminVO;
import com.redculture.platform.vo.request.RegistrationReviewRequest;

public interface SchoolRegistrationService extends IService<SchoolRegistration> {

    PageResult<SchoolRegistrationAdminVO> pageRegistrations(String keyword,
                                                            RegistrationReviewStatus reviewStatus,
                                                            Long pageNum,
                                                            Long pageSize);

    SchoolRegistrationAdminVO getRegistrationDetail(Long registrationId);

    SchoolRegistrationAdminVO approveRegistration(Long registrationId, RegistrationReviewRequest request);

    SchoolRegistrationAdminVO rejectRegistration(Long registrationId, RegistrationReviewRequest request);
}
