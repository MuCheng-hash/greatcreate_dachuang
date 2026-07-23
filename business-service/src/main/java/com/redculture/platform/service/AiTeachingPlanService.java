package com.redculture.platform.service;

import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AiTeachingPlanService {

    GeneratedTeachingPlanResponse generatePlan(TeachingPlanGenerateRequest request);

    default GeneratedTeachingPlanResponse generatePlan(TeachingPlanGenerateRequest request,
                                                         Long accountId,
                                                         String sessionId) {
        return generatePlan(request);
    }

    SseEmitter generatePlanStream(TeachingPlanGenerateRequest request);

    default SseEmitter generatePlanStream(TeachingPlanGenerateRequest request,
                                          Long accountId,
                                          String sessionId) {
        return generatePlanStream(request);
    }

    TeachingActivityPlanAdminVO saveDraft(GeneratedTeachingPlanSaveRequest request);
}
