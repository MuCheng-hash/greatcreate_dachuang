package com.redculture.platform.service;

import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;

public interface AiTeachingPlanService {

    GeneratedTeachingPlanResponse generatePlan(TeachingPlanGenerateRequest request);

    TeachingActivityPlanAdminVO saveDraft(GeneratedTeachingPlanSaveRequest request);
}
