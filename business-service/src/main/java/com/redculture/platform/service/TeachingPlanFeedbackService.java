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

    Long recordGeneration(TeachingPlanGenerateRequest request,
                          GeneratedTeachingPlanResponse response,
                          Long accountId,
                          String actorRole);

    PageResult<TeachingPlanGenerationVO> mine(AuthCurrentUserVO user,
                                              String feedbackStatus,
                                              Long pageNum,
                                              Long pageSize);

    TeachingPlanFeedbackVO submitFeedback(Long generationId,
                                          TeachingPlanFeedbackRequest request,
                                          AuthCurrentUserVO user);

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
