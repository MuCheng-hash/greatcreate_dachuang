package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.service.AiTeachingPlanService;
import com.redculture.platform.service.AuthService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai/teaching-plans")
public class AiTeachingPlanController {

    private final AiTeachingPlanService aiTeachingPlanService;
    private final AuthService authService;
    private final TeachingActivityPlanService teachingActivityPlanService;

    public AiTeachingPlanController(AiTeachingPlanService aiTeachingPlanService,
                                    AuthService authService,
                                    TeachingActivityPlanService teachingActivityPlanService) {
        this.aiTeachingPlanService = aiTeachingPlanService;
        this.authService = authService;
        this.teachingActivityPlanService = teachingActivityPlanService;
    }

    @PostMapping("/generate")
    public ApiResponse<GeneratedTeachingPlanResponse> generate(@RequestBody TeachingPlanGenerateRequest request,
                                                               HttpSession session) {
        try {
            AuthCurrentUserVO user = requireSchoolAccess(request == null ? null : request.getSchoolId(), session);
            return ApiResponse.success(aiTeachingPlanService.generatePlan(
                    request, user.getAccountId(), session.getId()));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping(value = "/generate/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter generateStream(@RequestBody TeachingPlanGenerateRequest request, HttpSession session) {
        AuthCurrentUserVO user = requireSchoolAccess(request == null ? null : request.getSchoolId(), session);
        return aiTeachingPlanService.generatePlanStream(request, user.getAccountId(), session.getId());
    }

    @PostMapping("/save-draft")
    public ApiResponse<TeachingActivityPlanAdminVO> saveDraft(@RequestBody GeneratedTeachingPlanSaveRequest request,
                                                              HttpSession session) {
        try {
            requireSchoolAccess(request == null ? null : request.getSchoolId(), session);
            return ApiResponse.success("draft activity plan created", aiTeachingPlanService.saveDraft(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/mine")
    public ApiResponse<PageResult<TeachingActivityPlanAdminVO>> mine(HttpSession session) {
        AuthCurrentUserVO user = authService.currentUser(session);
        if (user == null || user.getSchoolId() == null) {
            return ApiResponse.fail("school account is required");
        }
        return ApiResponse.success(teachingActivityPlanService.listBySchoolId(user.getSchoolId(), 1L, 50L));
    }

    private AuthCurrentUserVO requireSchoolAccess(Long schoolId, HttpSession session) {
        AuthCurrentUserVO user = authService.currentUser(session);
        if (user == null || schoolId == null) {
            throw new IllegalArgumentException("school account is required");
        }
        if (!"platform_admin".equals(user.getRoleCode()) && !schoolId.equals(user.getSchoolId())) {
            throw new IllegalArgumentException("cannot access another school");
        }
        return user;
    }
}
