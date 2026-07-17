package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.TeachingActivityPlanCreateRequest;
import com.redculture.platform.vo.request.TeachingActivityPlanUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class TeachingActivityPlanAdminController {

    private final TeachingActivityPlanService teachingActivityPlanService;

    public TeachingActivityPlanAdminController(TeachingActivityPlanService teachingActivityPlanService) {
        this.teachingActivityPlanService = teachingActivityPlanService;
    }

    @PostMapping("/activity-plans")
    public ApiResponse<TeachingActivityPlanAdminVO> create(@RequestBody TeachingActivityPlanCreateRequest request) {
        try {
            return ApiResponse.success("activity plan created", teachingActivityPlanService.createPlan(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/activity-plans/{planId}")
    public ApiResponse<TeachingActivityPlanAdminVO> update(@PathVariable Long planId,
                                                           @RequestBody TeachingActivityPlanUpdateRequest request) {
        try {
            return ApiResponse.success("activity plan updated", teachingActivityPlanService.updatePlan(planId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/activity-plans/{planId}")
    public ApiResponse<TeachingActivityPlanAdminVO> detail(@PathVariable Long planId) {
        TeachingActivityPlanAdminVO data = teachingActivityPlanService.getPlanAdminDetail(planId);
        if (data == null) {
            return ApiResponse.fail("activity plan not found");
        }
        return ApiResponse.success(data);
    }

    @GetMapping("/activity-plans")
    public ApiResponse<PageResult<TeachingActivityPlanAdminVO>> page(@RequestParam(required = false) Long schoolId,
                                                                     @RequestParam(required = false) Long resourceId,
                                                                     @RequestParam(required = false) String theme,
                                                                     @RequestParam(required = false) String activityType,
                                                                     @RequestParam(required = false) ReviewStatus reviewStatus,
                                                                     @RequestParam(required = false) Long pageNum,
                                                                     @RequestParam(required = false) Long pageSize) {
        return ApiResponse.success(teachingActivityPlanService.pagePlans(
                schoolId, resourceId, theme, activityType, reviewStatus, pageNum, pageSize
        ));
    }

    @GetMapping("/schools/{schoolId}/activity-plans")
    public ApiResponse<PageResult<TeachingActivityPlanAdminVO>> listBySchool(@PathVariable Long schoolId,
                                                                              @RequestParam(required = false) Long pageNum,
                                                                              @RequestParam(required = false) Long pageSize) {
        try {
            return ApiResponse.success(teachingActivityPlanService.listBySchoolId(schoolId, pageNum, pageSize));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
