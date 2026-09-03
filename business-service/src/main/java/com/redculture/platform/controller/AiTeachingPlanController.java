package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.AiTeachingPlanService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.service.TeachingPlanFeedbackService;
import com.redculture.platform.service.agent.AgentBusyException;
import com.redculture.platform.service.agent.AgentUpstreamException;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    private final TeachingPlanFeedbackService feedbackService;

    public AiTeachingPlanController(AiTeachingPlanService aiTeachingPlanService,
                                    TeachingActivityPlanService teachingActivityPlanService,
                                    TeachingPlanFeedbackService feedbackService) {
        this.aiTeachingPlanService = aiTeachingPlanService;
        this.teachingActivityPlanService = teachingActivityPlanService;
        this.feedbackService = feedbackService;
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
                    request == null ? null : request.getThreadId()
            ).map(value -> recordGeneration(request, value, user));
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
                    request == null ? null : request.getThreadId()
            ).map(event -> recordStreamGeneration(request, user, event));
        });
    }

    //将 AI 生成的方案保存为草稿，后续可在后台继续编辑或管理。
    @PostMapping("/save-draft")
    public ApiResponse<TeachingActivityPlanAdminVO> saveDraft(@RequestBody GeneratedTeachingPlanSaveRequest request,
                                                              HttpServletRequest servletRequest) {
        try {
            AuthCurrentUserVO user = AuthContext.requireUser(servletRequest);
            requireSchoolAccess(request == null ? null : request.getSchoolId(), user);
            TeachingActivityPlanAdminVO saved = request != null && request.getGenerationId() != null
                    ? feedbackService.saveDraftForGeneration(request.getGenerationId(), user.getAccountId(), null)
                    : aiTeachingPlanService.saveDraft(request, user.getAccountId());
            return ApiResponse.success("draft activity plan created", saved);
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //查询当前登录用户所在学校的教学活动方案，最多返回前 50 条。
    @GetMapping("/mine")
    public ResponseEntity<?> mine(
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String theme,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) Long pageNum,
            @RequestParam(required = false) Long pageSize,
            HttpServletRequest servletRequest) {
        AuthCurrentUserVO user = AuthContext.currentUser(servletRequest);
        if (user == null || user.getSchoolId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(400, "school account is required"));
        }
        try {
            LocalDateTime from = createdFrom == null ? null : createdFrom.atStartOfDay();
            LocalDateTime to = createdTo == null ? null : createdTo.atTime(LocalTime.MAX);
            return ResponseEntity.ok(ApiResponse.success(teachingActivityPlanService.listMine(user, grade, theme, resourceId, from, to, pageNum, pageSize)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(400, exception.getMessage()));
        }
    }

    @GetMapping("/mine/{planId}/export")
    public ResponseEntity<?> exportMine(@org.springframework.web.bind.annotation.PathVariable Long planId,
                                        HttpServletRequest servletRequest) {
        try {
            TeachingActivityPlanAdminVO plan = teachingActivityPlanService.getMine(planId, AuthContext.requireUser(servletRequest));
            AuthCurrentUserVO user = AuthContext.requireUser(servletRequest);
            byte[] content = teachingActivityPlanService.exportMine(planId, user);
            teachingActivityPlanService.adoptMine(planId, user);
            String fileName = "教学方案-" + safeFilePart(plan.getTheme()) + ".docx";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                    .body(content);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(exception.getMessage()));
        } catch (java.io.IOException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail("DOCX export failed"));
        }
    }

    @GetMapping("/generations/mine")
    public ResponseEntity<?> generationHistory(@RequestParam(required = false) String feedbackStatus,
                                                @RequestParam(required = false) Long pageNum,
                                                @RequestParam(required = false) Long pageSize,
                                                HttpServletRequest servletRequest) {
        try {
            return ResponseEntity.ok(ApiResponse.success(feedbackService.mine(
                    AuthContext.requireUser(servletRequest), feedbackStatus, pageNum, pageSize)));
        } catch (com.redculture.platform.exception.TeachingPlanFeedbackException exception) {
            return ResponseEntity.status(exception.getStatus())
                    .body(ApiResponse.fail(exception.getStatus().value(), exception.getMessage()));
        }
    }

    @PutMapping("/generations/{generationId}/feedback")
    public ResponseEntity<?> submitGenerationFeedback(@PathVariable Long generationId,
                                                       @RequestBody com.redculture.platform.vo.request.TeachingPlanFeedbackRequest request,
                                                       HttpServletRequest servletRequest) {
        try {
            return ResponseEntity.ok(ApiResponse.success(feedbackService.submitFeedback(
                    generationId, request, AuthContext.requireUser(servletRequest))));
        } catch (com.redculture.platform.exception.TeachingPlanFeedbackException exception) {
            return ResponseEntity.status(exception.getStatus())
                    .body(ApiResponse.fail(exception.getStatus().value(), exception.getMessage()));
        }
    }

    private GeneratedTeachingPlanResponse recordGeneration(TeachingPlanGenerateRequest request,
                                                            GeneratedTeachingPlanResponse response,
                                                            AuthCurrentUserVO user) {
        if (response != null && response.getGenerationId() == null) {
            response.setGenerationId(feedbackService.recordGeneration(
                    request, response, user.getAccountId(), user.getRoleCode()));
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private ServerSentEvent<Map<String, Object>> recordStreamGeneration(
            TeachingPlanGenerateRequest request, AuthCurrentUserVO user,
            ServerSentEvent<Map<String, Object>> event) {
        if (!"final".equals(event.event()) || event.data() == null) return event;
        Object response = event.data().get("response");
        if (!(response instanceof Map<?, ?> rawResponse)) return event;
        Object teachingPlan = rawResponse.get("teachingPlan");
        if (!(teachingPlan instanceof GeneratedTeachingPlanResponse plan)) return event;
        recordGeneration(request, plan, user);
        return event;
    }

    private String safeFilePart(String value) {
        String safe = value == null ? "教学方案" : value.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        return safe.isEmpty() ? "教学方案" : safe.substring(0, Math.min(80, safe.length()));
    }

    @GetMapping("/mine/{planId}")
    public ApiResponse<TeachingActivityPlanAdminVO> mineDetail(@org.springframework.web.bind.annotation.PathVariable Long planId,
                                                               HttpServletRequest servletRequest) {
        try {
            return ApiResponse.success(teachingActivityPlanService.getMine(planId, AuthContext.requireUser(servletRequest)));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @org.springframework.web.bind.annotation.PutMapping("/mine/{planId}")
    public ApiResponse<TeachingActivityPlanAdminVO> updateMine(@org.springframework.web.bind.annotation.PathVariable Long planId,
                                                               @RequestBody com.redculture.platform.vo.request.TeachingActivityPlanUpdateRequest request,
                                                               HttpServletRequest servletRequest) {
        try {
            return ApiResponse.success(teachingActivityPlanService.updateMine(planId, request, AuthContext.requireUser(servletRequest)));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/mine/{planId}/copy")
    public ApiResponse<TeachingActivityPlanAdminVO> copyMine(@org.springframework.web.bind.annotation.PathVariable Long planId,
                                                             HttpServletRequest servletRequest) {
        try {
            return ApiResponse.success(teachingActivityPlanService.copyMine(planId, AuthContext.requireUser(servletRequest)));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
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
