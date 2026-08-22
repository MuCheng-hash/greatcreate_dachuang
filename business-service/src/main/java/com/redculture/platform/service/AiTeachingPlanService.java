package com.redculture.platform.service;

import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface AiTeachingPlanService {

    Mono<GeneratedTeachingPlanResponse> generatePlan(TeachingPlanGenerateRequest request);

    default Mono<GeneratedTeachingPlanResponse> generatePlan(
            TeachingPlanGenerateRequest request,
            Long accountId,
            String sessionId) {
        return generatePlan(request);
    }

    default Mono<GeneratedTeachingPlanResponse> generatePlan(
            TeachingPlanGenerateRequest request,
            Long accountId,
            String actorRole,
            String sessionId) {
        return generatePlan(request, accountId, sessionId);
    }

    Flux<ServerSentEvent<Map<String, Object>>> generatePlanStream(
            TeachingPlanGenerateRequest request);

    default Flux<ServerSentEvent<Map<String, Object>>> generatePlanStream(
            TeachingPlanGenerateRequest request,
            Long accountId,
            String sessionId) {
        return generatePlanStream(request);
    }

    default Flux<ServerSentEvent<Map<String, Object>>> generatePlanStream(
            TeachingPlanGenerateRequest request,
            Long accountId,
            String actorRole,
            String sessionId) {
        return generatePlanStream(request, accountId, sessionId);
    }

    TeachingActivityPlanAdminVO saveDraft(GeneratedTeachingPlanSaveRequest request);

    default TeachingActivityPlanAdminVO saveDraft(GeneratedTeachingPlanSaveRequest request, Long accountId) {
        return saveDraft(request);
    }
}
