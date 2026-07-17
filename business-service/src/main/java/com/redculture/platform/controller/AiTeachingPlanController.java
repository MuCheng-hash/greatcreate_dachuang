package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.AiTeachingPlanService;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/teaching-plans")
public class AiTeachingPlanController {

    private final AiTeachingPlanService aiTeachingPlanService;

    public AiTeachingPlanController(AiTeachingPlanService aiTeachingPlanService) {
        this.aiTeachingPlanService = aiTeachingPlanService;
    }

    @PostMapping("/generate")
    public ApiResponse<GeneratedTeachingPlanResponse> generate(@RequestBody TeachingPlanGenerateRequest request) {
        try {
            return ApiResponse.success(aiTeachingPlanService.generatePlan(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/save-draft")
    public ApiResponse<TeachingActivityPlanAdminVO> saveDraft(@RequestBody GeneratedTeachingPlanSaveRequest request) {
        try {
            return ApiResponse.success("draft activity plan created", aiTeachingPlanService.saveDraft(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
