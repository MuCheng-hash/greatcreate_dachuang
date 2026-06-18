package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.service.SchoolResourceRelService;
import com.redculture.platform.vo.SchoolResourceRelAdminVO;
import com.redculture.platform.vo.request.SchoolResourceRelCreateRequest;
import com.redculture.platform.vo.request.SchoolResourceRelUpdateRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
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
public class SchoolResourceRelAdminController {

    private final SchoolResourceRelService schoolResourceRelService;

    public SchoolResourceRelAdminController(SchoolResourceRelService schoolResourceRelService) {
        this.schoolResourceRelService = schoolResourceRelService;
    }

    @PostMapping("/school-resource-rel")
    public ApiResponse<SchoolResourceRelAdminVO> create(@RequestBody SchoolResourceRelCreateRequest request) {
        try {
            return ApiResponse.success("relation created", schoolResourceRelService.createRelation(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/school-resource-rel/{relId}")
    public ApiResponse<SchoolResourceRelAdminVO> update(@PathVariable Long relId,
                                                        @RequestBody SchoolResourceRelUpdateRequest request) {
        try {
            return ApiResponse.success("relation updated", schoolResourceRelService.updateRelation(relId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @DeleteMapping("/school-resource-rel/{relId}")
    public ApiResponse<Boolean> delete(@PathVariable Long relId) {
        try {
            return ApiResponse.success("relation deleted", schoolResourceRelService.deleteRelation(relId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/schools/{schoolId}/resources")
    public ApiResponse<PageResult<SchoolResourceRelAdminVO>> listBySchool(@PathVariable Long schoolId,
                                                                          @RequestParam(required = false) Long pageNum,
                                                                          @RequestParam(required = false) Long pageSize) {
        try {
            return ApiResponse.success(schoolResourceRelService.listBySchoolId(schoolId, pageNum, pageSize));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/resources/{resourceId}/schools")
    public ApiResponse<PageResult<SchoolResourceRelAdminVO>> listByResource(@PathVariable Long resourceId,
                                                                            @RequestParam(required = false) Long pageNum,
                                                                            @RequestParam(required = false) Long pageSize) {
        try {
            return ApiResponse.success(schoolResourceRelService.listByResourceId(resourceId, pageNum, pageSize));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
