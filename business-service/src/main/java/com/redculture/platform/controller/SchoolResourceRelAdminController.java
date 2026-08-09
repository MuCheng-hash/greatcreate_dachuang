package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.service.SchoolResourceRelService;
import com.redculture.platform.vo.SchoolResourceCandidateResultVO;
import com.redculture.platform.vo.SchoolResourceRelAdminVO;
import com.redculture.platform.vo.request.SchoolResourceRelBatchCreateRequest;
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
//管理“学校—教育资源”关联关系：新增、修改、删除、推荐候选资源、批量关联及双向查询
public class SchoolResourceRelAdminController {

    private final SchoolResourceRelService schoolResourceRelService;

    public SchoolResourceRelAdminController(SchoolResourceRelService schoolResourceRelService) {
        this.schoolResourceRelService = schoolResourceRelService;
    }

    //新增学校与资源之间的关联关系
    @PostMapping("/school-resource-rel")
    public ApiResponse<SchoolResourceRelAdminVO> create(@RequestBody SchoolResourceRelCreateRequest request) {
        try {
            return ApiResponse.success("relation created", schoolResourceRelService.createRelation(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //更新关联关系
    @PutMapping("/school-resource-rel/{relId}")
    public ApiResponse<SchoolResourceRelAdminVO> update(@PathVariable Long relId,
                                                        @RequestBody SchoolResourceRelUpdateRequest request) {
        try {
            return ApiResponse.success("relation updated", schoolResourceRelService.updateRelation(relId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //删除关联关系
    @DeleteMapping("/school-resource-rel/{relId}")
    public ApiResponse<Boolean> delete(@PathVariable Long relId) {
        try {
            return ApiResponse.success("relation deleted", schoolResourceRelService.deleteRelation(relId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //为某学校推荐附近可关联的候选资源。
    @GetMapping("/schools/{schoolId}/resource-candidates")
    public ApiResponse<SchoolResourceCandidateResultVO> resourceCandidates(@PathVariable Long schoolId,
                                                                           @RequestParam(required = false) Double radiusKm) {
        try {
            return ApiResponse.success(schoolResourceRelService.listResourceCandidates(schoolId, radiusKm));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //从候选资源中一次性批量创建关联。
    @PostMapping("/schools/{schoolId}/resource-relations/batch")
    public ApiResponse<SchoolResourceCandidateResultVO> batchCreate(@PathVariable Long schoolId,
                                                                    @RequestBody SchoolResourceRelBatchCreateRequest request) {
        try {
            return ApiResponse.success("relations created", schoolResourceRelService.batchCreateRelations(schoolId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //分页查询某所学校已关联的资源。
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

    //分页查询某项资源关联了哪些学校。
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
