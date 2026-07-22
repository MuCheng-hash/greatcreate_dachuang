package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.service.ResourceDiscoveryReviewService;
import com.redculture.platform.service.ResourceDiscoveryService;
import com.redculture.platform.vo.discovery.DiscoveryCandidateVO;
import com.redculture.platform.vo.discovery.DiscoveryRunVO;
import com.redculture.platform.vo.request.DiscoveryCandidateReviewRequest;
import com.redculture.platform.vo.request.DiscoveryRunRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class ResourceDiscoveryAdminController {

    private final ResourceDiscoveryReviewService reviewService;
    private final ResourceDiscoveryService discoveryService;

    public ResourceDiscoveryAdminController(ResourceDiscoveryReviewService reviewService,
                                            ResourceDiscoveryService discoveryService) {
        this.reviewService = reviewService;
        this.discoveryService = discoveryService;
    }

    @GetMapping("/resource-discovery-candidates")
    public ApiResponse<PageResult<DiscoveryCandidateVO>> candidates(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String analysisStatus,
            @RequestParam(required = false) String decisionStatus,
            @RequestParam(required = false) Long pageNum,
            @RequestParam(required = false) Long pageSize) {
        return ApiResponse.success(reviewService.pageCandidates(
                schoolId, analysisStatus, decisionStatus, pageNum, pageSize));
    }

    @GetMapping("/resource-discovery-candidates/{candidateId}")
    public ApiResponse<DiscoveryCandidateVO> candidate(@PathVariable Long candidateId) {
        try {
            return ApiResponse.success(reviewService.getCandidate(candidateId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/schools/{schoolId}/discovery-runs")
    public ApiResponse<DiscoveryRunVO> forceDiscovery(@PathVariable Long schoolId,
                                                       @RequestBody(required = false) DiscoveryRunRequest request) {
        try {
            return ApiResponse.success(discoveryService.startRun(
                    schoolId, request == null ? null : request.getRadiusKm(), true));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/resource-discovery-candidates/{candidateId}/approve")
    public ApiResponse<DiscoveryCandidateVO> approve(@PathVariable Long candidateId,
                                                      @RequestBody(required = false) DiscoveryCandidateReviewRequest request) {
        try {
            return ApiResponse.success("candidate approved", reviewService.approve(candidateId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/resource-discovery-candidates/{candidateId}/reject")
    public ApiResponse<DiscoveryCandidateVO> reject(@PathVariable Long candidateId,
                                                     @RequestBody(required = false) DiscoveryCandidateReviewRequest request) {
        try {
            return ApiResponse.success("candidate rejected", reviewService.reject(candidateId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/resource-discovery-candidates/{candidateId}/reopen")
    public ApiResponse<DiscoveryCandidateVO> reopen(@PathVariable Long candidateId,
                                                     @RequestBody(required = false) DiscoveryCandidateReviewRequest request) {
        try {
            return ApiResponse.success("candidate reopened", reviewService.reopen(candidateId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
