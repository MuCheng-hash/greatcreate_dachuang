package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.baomidou.mybatisplus.extension.service.IService;
import com.redculture.platform.entity.TeachingActivityPlan;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.TeachingActivityPlanCreateRequest;
import com.redculture.platform.vo.request.TeachingActivityPlanUpdateRequest;

public interface TeachingActivityPlanService extends IService<TeachingActivityPlan> {

    TeachingActivityPlanAdminVO createPlan(TeachingActivityPlanCreateRequest request);

    TeachingActivityPlanAdminVO updatePlan(Long planId, TeachingActivityPlanUpdateRequest request);

    TeachingActivityPlanAdminVO getPlanAdminDetail(Long planId);

    PageResult<TeachingActivityPlanAdminVO> pagePlans(Long schoolId,
                                                      Long resourceId,
                                                      String theme,
                                                      String activityType,
                                                      ReviewStatus reviewStatus,
                                                      Long pageNum,
                                                      Long pageSize);

    PageResult<TeachingActivityPlanAdminVO> listBySchoolId(Long schoolId, Long pageNum, Long pageSize);
}
