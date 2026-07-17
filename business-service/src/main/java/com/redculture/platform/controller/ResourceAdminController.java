package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.vo.ResourceAdminVO;
import com.redculture.platform.vo.request.ResourceCreateRequest;
import com.redculture.platform.vo.request.ResourceReviewRequest;
import com.redculture.platform.vo.request.ResourceUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/resources")
public class ResourceAdminController {

    private final LocalEduResourceService localEduResourceService;

    public ResourceAdminController(LocalEduResourceService localEduResourceService) {
        this.localEduResourceService = localEduResourceService;
    }

    @PostMapping
    public ApiResponse<ResourceAdminVO> create(@RequestBody ResourceCreateRequest request) {
        try {
            return ApiResponse.success("resource created", localEduResourceService.createResource(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/{resourceId}")
    public ApiResponse<ResourceAdminVO> update(@PathVariable Long resourceId,
                                               @RequestBody ResourceUpdateRequest request) {
        try {
            return ApiResponse.success("resource updated", localEduResourceService.updateResource(resourceId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/{resourceId}")
    public ApiResponse<ResourceAdminVO> detail(@PathVariable Long resourceId) {
        ResourceAdminVO data = localEduResourceService.getResourceAdminDetail(resourceId);
        if (data == null) {
            return ApiResponse.fail("resource not found");
        }
        return ApiResponse.success(data);
    }

    @GetMapping
    public ApiResponse<PageResult<ResourceAdminVO>> page(@RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String resourceCategory,
                                                         @RequestParam(required = false) Long countyRegionId,
                                                         @RequestParam(required = false) Long townshipRegionId,
                                                         @RequestParam(required = false) ReviewStatus reviewStatus,
                                                         @RequestParam(required = false) Long pageNum,
                                                         @RequestParam(required = false) Long pageSize) {
        return ApiResponse.success(localEduResourceService.pageResources(
                keyword, resourceCategory, countyRegionId, townshipRegionId, reviewStatus, pageNum, pageSize
        ));
    }

    @PostMapping("/{resourceId}/submit-review")
    public ApiResponse<ResourceAdminVO> submitReview(@PathVariable Long resourceId) {
        try {
            return ApiResponse.success("resource submitted for review", localEduResourceService.submitReview(resourceId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/{resourceId}/approve")
    public ApiResponse<ResourceAdminVO> approve(@PathVariable Long resourceId,
                                                @RequestBody(required = false) ResourceReviewRequest request) {
        try {
            return ApiResponse.success("resource approved", localEduResourceService.approve(resourceId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/{resourceId}/reject")
    public ApiResponse<ResourceAdminVO> reject(@PathVariable Long resourceId,
                                               @RequestBody(required = false) ResourceReviewRequest request) {
        try {
            return ApiResponse.success("resource rejected", localEduResourceService.reject(resourceId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
