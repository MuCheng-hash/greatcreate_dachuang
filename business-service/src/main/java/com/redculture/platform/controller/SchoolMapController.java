package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.ApprovedResourceQueryService;
import com.redculture.platform.service.SchoolAccessService;
import com.redculture.platform.vo.discovery.ApprovedResourceDetailVO;
import jakarta.servlet.http.HttpServletRequest;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/school-map")
public class SchoolMapController {

    private final SchoolMapService schoolMapService;
    private final ApprovedResourceQueryService approvedResourceQueryService;
    private final SchoolAccessService schoolAccessService;

    public SchoolMapController(SchoolMapService schoolMapService,
                               ApprovedResourceQueryService approvedResourceQueryService,
                               SchoolAccessService schoolAccessService) {
        this.schoolMapService = schoolMapService;
        this.approvedResourceQueryService = approvedResourceQueryService;
        this.schoolAccessService = schoolAccessService;
    }

    @GetMapping("/schools")
    public ApiResponse<List<SchoolSummaryVO>> listSchools(@RequestParam(required = false) Long countyRegionId,
                                                           @RequestParam(required = false) Long townshipRegionId,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(schoolMapService.listSchools(countyRegionId, townshipRegionId, keyword, limit));
    }

    @GetMapping("/schools/nearby")
    public ApiResponse<List<SchoolSummaryVO>> nearbySchools(@RequestParam BigDecimal longitude,
                                                            @RequestParam BigDecimal latitude,
                                                            @RequestParam(required = false) Double radiusKm,
                                                            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(schoolMapService.findNearbySchools(longitude, latitude, radiusKm, limit));
    }

    @GetMapping("/schools/{schoolId}/detail")
    public ApiResponse<SchoolMapDetailVO> schoolDetail(@PathVariable Long schoolId, HttpServletRequest request) {
        try {
            schoolAccessService.requireSchoolAccess(schoolId, AuthContext.currentUser(request));
            SchoolMapDetailVO detailVO = schoolMapService.getSchoolDetail(schoolId);
            if (detailVO == null) {
                return ApiResponse.fail("school not found");
            }
            return ApiResponse.success(detailVO);
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/schools/{schoolId}/resources/{resourceId}")
    public ApiResponse<ApprovedResourceDetailVO> approvedResource(@PathVariable Long schoolId,
                                                                   @PathVariable Long resourceId,
                                                                   HttpServletRequest servletRequest) {
        try {
            schoolAccessService.requireSchoolAccess(schoolId, AuthContext.currentUser(servletRequest));
            return ApiResponse.success(approvedResourceQueryService.getApprovedResource(schoolId, resourceId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
