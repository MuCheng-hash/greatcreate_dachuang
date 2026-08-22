package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.exception.TeachingPlanFeedbackException;
import com.redculture.platform.exception.TeachingPlanGenerationPersistenceException;
import com.redculture.platform.service.AiTeachingPlanService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.service.TeachingPlanFeedbackService;
import com.redculture.platform.service.agent.AgentBusyException;
import com.redculture.platform.service.agent.AgentUpstreamException;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.TeachingPlanFeedbackVO;
import com.redculture.platform.vo.TeachingPlanGenerationVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingPlanFeedbackRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/teaching-plans")
//AI 教学活动方案：生成教案、流式生成、保存草稿、查看本人已生成的方案。
public class AiTeachingPlanController {

    /*
    AiTeachingPlanService负责调用 AI Agent 生成方案、流式转发生成过程、保存 AI 生成的草稿。
    TeachingActivityPlanService负责从业务数据库查询已经保存的教学活动方案。
     */
    private final AiTeachingPlanService aiTeachingPlanService;
    private final TeachingActivityPlanService teachingActivityPlanService;
    private final TeachingPlanFeedbackService teachingPlanFeedbackService;

    @Autowired
    public AiTeachingPlanController(AiTeachingPlanService aiTeachingPlanService,
                                    TeachingActivityPlanService teachingActivityPlanService,
                                    TeachingPlanFeedbackService teachingPlanFeedbackService) {
        this.aiTeachingPlanService = aiTeachingPlanService;
        this.teachingActivityPlanService = teachingActivityPlanService;
        this.teachingPlanFeedbackService = teachingPlanFeedbackService;
    }

    public AiTeachingPlanController(AiTeachingPlanService aiTeachingPlanService,
                                    TeachingActivityPlanService teachingActivityPlanService) {
        this(aiTeachingPlanService, teachingActivityPlanService, null);
    }

    //同步生成教学方案。等待 AI 完整生成后，一次性返回结果。
    @PostMapping("/generate")
    public Mono<ResponseEntity<ApiResponse<GeneratedTeachingPlanResponse>>> generate(
            @RequestBody TeachingPlanGenerateRequest request,
            HttpServletRequest servletRequest) {
        return Mono.defer(() -> {
            AuthCurrentUserVO user = AuthContext.requireUser(servletRequest);
            requireSchoolAccess(request == null ? null : request.getSchoolId(), user);
            return aiTeachingPlanService.generatePlan(
                    request,
                    user.getAccountId(),
                    user.getRoleCode(),
                    request == null ? null : request.getThreadId()
            );
        }).map(value -> ResponseEntity.ok(ApiResponse.success(value)))
                .onErrorResume(IllegalArgumentException.class, error -> Mono.just(
                        ResponseEntity.badRequest().body(ApiResponse.fail(error.getMessage()))
                ))
                .onErrorResume(AgentBusyException.class, error -> Mono.just(
                        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(ApiResponse.fail(503, "agent_busy"))
                ))
                .onErrorResume(AgentUpstreamException.class, error -> Mono.just(
                        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                .body(ApiResponse.fail(502, error.getCode()))
                ))
                .onErrorResume(TeachingPlanGenerationPersistenceException.class, error -> Mono.just(
                        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(ApiResponse.fail(500, "generation_record_save_failed"))
                ));
    }

    //流式生成教学方案。AI 生成一段就推送一段，适用于前端实时展示。
    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> generateStream(
            @RequestBody TeachingPlanGenerateRequest request,
            HttpServletRequest servletRequest) {
        return Flux.defer(() -> {
            AuthCurrentUserVO user = AuthContext.requireUser(servletRequest);
            requireSchoolAccess(request == null ? null : request.getSchoolId(), user);
            return aiTeachingPlanService.generatePlanStream(
                    request,
                    user.getAccountId(),
                    user.getRoleCode(),
                    request == null ? null : request.getThreadId()
            );
        });
    }

    //将 AI 生成的方案保存为草稿，后续可在后台继续编辑或管理。
    @PostMapping("/save-draft")
    public ApiResponse<TeachingActivityPlanAdminVO> saveDraft(@RequestBody GeneratedTeachingPlanSaveRequest request,
                                                              HttpServletRequest servletRequest) {
        try {
            requireSchoolAccess(request == null ? null : request.getSchoolId(), AuthContext.requireUser(servletRequest));
            AuthCurrentUserVO user = AuthContext.requireUser(servletRequest);
            return ApiResponse.success("draft activity plan created",
                    aiTeachingPlanService.saveDraft(request, user.getAccountId()));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        } catch (TeachingPlanFeedbackException exception) {
            return ApiResponse.fail(exception.getStatus().value(), exception.getMessage());
        }
    }

    //查询当前登录用户所在学校的教学活动方案，最多返回前 50 条。
    @GetMapping("/mine")
    public ApiResponse<PageResult<TeachingActivityPlanAdminVO>> mine(HttpServletRequest servletRequest) {
        AuthCurrentUserVO user = AuthContext.currentUser(servletRequest);
        if (user == null || user.getSchoolId() == null) {
            return ApiResponse.fail("school account is required");
        }
        return ApiResponse.success(teachingActivityPlanService.listBySchoolId(user.getSchoolId(), 1L, 50L));
    }

    @GetMapping("/generations/mine")
    public ResponseEntity<ApiResponse<PageResult<TeachingPlanGenerationVO>>> generationHistory(
            @RequestParam(required = false) String feedbackStatus,
            @RequestParam(required = false) Long pageNum,
            @RequestParam(required = false) Long pageSize,
            HttpServletRequest servletRequest) {
        try {
            return ResponseEntity.ok(ApiResponse.success(teachingPlanFeedbackService.mine(
                    AuthContext.requireUser(servletRequest), feedbackStatus, pageNum, pageSize)));
        } catch (TeachingPlanFeedbackException exception) {
            return ResponseEntity.status(exception.getStatus())
                    .body(ApiResponse.fail(exception.getStatus().value(), exception.getMessage()));
        }
    }

    @PutMapping("/generations/{generationId}/feedback")
    public ResponseEntity<ApiResponse<TeachingPlanFeedbackVO>> submitFeedback(
            @PathVariable Long generationId,
            @RequestBody TeachingPlanFeedbackRequest request,
            HttpServletRequest servletRequest) {
        try {
            TeachingPlanFeedbackVO feedback = teachingPlanFeedbackService.submitFeedback(
                    generationId, request, AuthContext.requireUser(servletRequest));
            return ResponseEntity.ok(ApiResponse.success("teaching plan feedback saved", feedback));
        } catch (TeachingPlanFeedbackException exception) {
            return ResponseEntity.status(exception.getStatus())
                    .body(ApiResponse.fail(exception.getStatus().value(), exception.getMessage()));
        }
    }

    //核心权限校验方法
    /*
    情况	结果
未登录，或请求没有学校 ID	抛出 school account is required。
当前用户是 platform_admin	可操作任意学校的数据。
当前用户不是平台管理员，但请求的 schoolId 等于自己的学校 ID	允许。
当前用户不是平台管理员，且请求的是其他学校 ID	拒绝，提示 cannot access another school。
     */
    private void requireSchoolAccess(Long schoolId, AuthCurrentUserVO user) {
        if (user == null || schoolId == null) {
            throw new IllegalArgumentException("school account is required");
        }
        if (!"platform_admin".equals(user.getRoleCode()) && !schoolId.equals(user.getSchoolId())) {
            throw new IllegalArgumentException("cannot access another school");
        }
    }
}
