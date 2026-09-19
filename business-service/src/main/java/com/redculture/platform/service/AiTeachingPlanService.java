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

    /**
     * 生成完整教学方案并在结果可用后一次性返回。
     * 基础重载仅适用于不需要账号归属的兼容调用；生产 HTTP 调用应使用携带 accountId 的重载，
     * 以便将生成轮次绑定到操作者并在 Agent 不可用时传递失败原因。
     */
    Mono<GeneratedTeachingPlanResponse> generatePlan(TeachingPlanGenerateRequest request);

    /** 按账号和会话标识生成方案，使后续保存、反馈与历史查询能够归属到同一操作者。 */
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

    /** 以 SSE 推送方案生成过程；最终事件承载完整方案，调用方负责在认证边界记录生成历史。 */
    Flux<ServerSentEvent<Map<String, Object>>> generatePlanStream(
            TeachingPlanGenerateRequest request);

    /** 以账号和会话标识流式生成方案，支持前端在同一会话中恢复展示。 */
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

    /**
     * 将 AI 返回的方案保存为可编辑草稿。
     * 保存前必须完成输入与资源归属校验，避免未经审核或跨学校资源进入教师方案。
     */
    TeachingActivityPlanAdminVO saveDraft(GeneratedTeachingPlanSaveRequest request);

    /** 将草稿所有权固定到指定账号，供认证 HTTP 边界调用。 */
    default TeachingActivityPlanAdminVO saveDraft(GeneratedTeachingPlanSaveRequest request, Long accountId) {
        return saveDraft(request);
    }
}
