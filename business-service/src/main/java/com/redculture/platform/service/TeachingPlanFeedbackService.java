package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReportVO;
import com.redculture.platform.vo.TeachingPlanFeedbackVO;
import com.redculture.platform.vo.TeachingPlanGenerationVO;
import com.redculture.platform.vo.request.TeachingActivityPlanCreateRequest;
import com.redculture.platform.vo.request.TeachingPlanFeedbackRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;

import java.time.LocalDate;

public interface TeachingPlanFeedbackService {

    /**
     * 记录一次完成的方案生成并返回 generationId。
     * accountId 与 actorRole 来自认证边界，是后续历史查询、反馈及草稿保存的所有权依据。
     */
    Long recordGeneration(TeachingPlanGenerateRequest request,
                          GeneratedTeachingPlanResponse response,
                          Long accountId,
                          String actorRole);

    /** 在当前用户所有权范围内分页查询生成历史，可按反馈状态筛选。 */
    PageResult<TeachingPlanGenerationVO> mine(AuthCurrentUserVO user,
                                              String feedbackStatus,
                                              Long pageNum,
                                              Long pageSize);

    /** 提交生成结果反馈；服务拒绝非所有者、无效状态及重复状态迁移。 */
    TeachingPlanFeedbackVO submitFeedback(Long generationId,
                                          TeachingPlanFeedbackRequest request,
                                          AuthCurrentUserVO user);

    /** 从当前账号拥有的生成记录创建草稿，防止任意 generationId 被跨账号复用。 */
    TeachingActivityPlanAdminVO saveDraftForGeneration(Long generationId,
                                                        Long accountId,
                                                        TeachingActivityPlanCreateRequest createRequest);

    TeachingPlanFeedbackReportVO report(Long schoolId,
                                        LocalDate startDate,
                                        LocalDate endDate,
                                        String theme,
                                        String feedbackStatus,
                                        Boolean adopted,
                                        Boolean lowScoreOnly,
                                        String reasonCode,
                                        Long pageNum,
                                        Long pageSize);
}
