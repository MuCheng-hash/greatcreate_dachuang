package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.vo.SchoolAdminVO;
import com.redculture.platform.vo.request.SchoolCreateRequest;
import com.redculture.platform.vo.request.SchoolReviewRequest;
import com.redculture.platform.vo.request.SchoolUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/schools")
public class SchoolAdminController {

    private final SchoolService schoolService;

    public SchoolAdminController(SchoolService schoolService) {
        this.schoolService = schoolService;
    }

    @PostMapping
    public ApiResponse<SchoolAdminVO> create(@RequestBody SchoolCreateRequest request) {
        try {
            return ApiResponse.success("school created", schoolService.createSchool(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/{schoolId}")
    public ApiResponse<SchoolAdminVO> update(@PathVariable Long schoolId,
                                             @RequestBody SchoolUpdateRequest request) {
        try {
            return ApiResponse.success("school updated", schoolService.updateSchool(schoolId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/{schoolId}")
    public ApiResponse<SchoolAdminVO> detail(@PathVariable Long schoolId) {
        SchoolAdminVO data = schoolService.getSchoolAdminDetail(schoolId);
        if (data == null) {
            return ApiResponse.fail("school not found");
        }
        return ApiResponse.success(data);
    }

    @GetMapping
    public ApiResponse<PageResult<SchoolAdminVO>> page(@RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) Long countyRegionId,
                                                       @RequestParam(required = false) Long townshipRegionId,
                                                       @RequestParam(required = false) String schoolLevel,
                                                       @RequestParam(required = false) ReviewStatus reviewStatus,
                                                       @RequestParam(required = false) Long pageNum,
                                                       @RequestParam(required = false) Long pageSize) {
        return ApiResponse.success(schoolService.pageSchools(
                keyword, countyRegionId, townshipRegionId, schoolLevel, reviewStatus, pageNum, pageSize
        ));
    }

    @PostMapping("/{schoolId}/submit-review")
    public ApiResponse<SchoolAdminVO> submitReview(@PathVariable Long schoolId) {
        try {
            return ApiResponse.success("school submitted for review", schoolService.submitReview(schoolId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/{schoolId}/approve")
    public ApiResponse<SchoolAdminVO> approve(@PathVariable Long schoolId,
                                              @RequestBody(required = false) SchoolReviewRequest request) {
        try {
            return ApiResponse.success("school approved", schoolService.approve(schoolId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/{schoolId}/reject")
    public ApiResponse<SchoolAdminVO> reject(@PathVariable Long schoolId,
                                             @RequestBody(required = false) SchoolReviewRequest request) {
        try {
            return ApiResponse.success("school rejected", schoolService.reject(schoolId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
