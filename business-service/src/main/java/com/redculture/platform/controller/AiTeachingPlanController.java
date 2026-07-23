package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.AiTeachingPlanService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/teaching-plans")
public class AiTeachingPlanController {

    private final AiTeachingPlanService aiTeachingPlanService;
    private final TeachingActivityPlanService teachingActivityPlanService;

    public AiTeachingPlanController(AiTeachingPlanService aiTeachingPlanService,
                                    TeachingActivityPlanService teachingActivityPlanService) {
        this.aiTeachingPlanService = aiTeachingPlanService;
        this.teachingActivityPlanService = teachingActivityPlanService;
    }

    @PostMapping("/generate")
    public ApiResponse<GeneratedTeachingPlanResponse> generate(@RequestBody TeachingPlanGenerateRequest request,
                                                               HttpServletRequest servletRequest) {
        try {
            requireSchoolAccess(request == null ? null : request.getSchoolId(), AuthContext.requireUser(servletRequest));
            return ApiResponse.success(aiTeachingPlanService.generatePlan(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/save-draft")
    public ApiResponse<TeachingActivityPlanAdminVO> saveDraft(@RequestBody GeneratedTeachingPlanSaveRequest request,
                                                              HttpServletRequest servletRequest) {
        try {
            requireSchoolAccess(request == null ? null : request.getSchoolId(), AuthContext.requireUser(servletRequest));
            return ApiResponse.success("draft activity plan created", aiTeachingPlanService.saveDraft(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/mine")
    public ApiResponse<PageResult<TeachingActivityPlanAdminVO>> mine(HttpServletRequest servletRequest) {
        AuthCurrentUserVO user = AuthContext.currentUser(servletRequest);
        if (user == null || user.getSchoolId() == null) {
            return ApiResponse.fail("school account is required");
        }
        return ApiResponse.success(teachingActivityPlanService.listBySchoolId(user.getSchoolId(), 1L, 50L));
    }

    private void requireSchoolAccess(Long schoolId, AuthCurrentUserVO user) {
        if (user == null || schoolId == null) {
            throw new IllegalArgumentException("school account is required");
        }
        if (!"platform_admin".equals(user.getRoleCode()) && !schoolId.equals(user.getSchoolId())) {
            throw new IllegalArgumentException("cannot access another school");
        }
    }
}
