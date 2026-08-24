package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.baomidou.mybatisplus.extension.service.IService;
import com.redculture.platform.entity.TeachingActivityPlan;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.TeachingActivityPlanCreateRequest;
import com.redculture.platform.vo.request.TeachingActivityPlanUpdateRequest;
import com.redculture.platform.vo.AuthCurrentUserVO;
import java.time.LocalDateTime;
import java.io.IOException;

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

    PageResult<TeachingActivityPlanAdminVO> listMine(AuthCurrentUserVO user,
                                                    String grade,
                                                    String theme,
                                                    Long resourceId,
                                                    LocalDateTime createdFrom,
                                                    LocalDateTime createdTo,
                                                    Long pageNum,
                                                    Long pageSize);

    TeachingActivityPlanAdminVO getMine(Long planId, AuthCurrentUserVO user);

    TeachingActivityPlanAdminVO updateMine(Long planId, TeachingActivityPlanUpdateRequest request, AuthCurrentUserVO user);

    TeachingActivityPlanAdminVO copyMine(Long planId, AuthCurrentUserVO user);

    byte[] exportMine(Long planId, AuthCurrentUserVO user) throws IOException;
}
