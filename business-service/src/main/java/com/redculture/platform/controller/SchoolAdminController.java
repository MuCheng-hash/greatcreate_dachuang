package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.vo.SchoolAdminVO;
import com.redculture.platform.vo.request.SchoolCreateRequest;
import com.redculture.platform.vo.request.SchoolUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/schools")
//管理员维护学校：新增、编辑、删除、详情和分页查询。
public class SchoolAdminController {

    private final SchoolService schoolService;

    public SchoolAdminController(SchoolService schoolService) {
        this.schoolService = schoolService;
    }

    //新建学校档案。
    @PostMapping
    public ApiResponse<SchoolAdminVO> create(@RequestBody SchoolCreateRequest request) {
        try {
            return ApiResponse.success("school created", schoolService.createSchool(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //修改某所学校。
    @PutMapping("/{schoolId}")
    public ApiResponse<SchoolAdminVO> update(@PathVariable Long schoolId,
                                             @RequestBody SchoolUpdateRequest request) {
        try {
            return ApiResponse.success("school updated", schoolService.updateSchool(schoolId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //删除某所学校。
    @DeleteMapping("/{schoolId}")
    public ApiResponse<Void> delete(@PathVariable Long schoolId) {
        try {
            schoolService.deleteSchool(schoolId);
            return ApiResponse.success("school deleted", null);
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //查询某所学校的后台详情。
    @GetMapping("/{schoolId}")
    public ApiResponse<SchoolAdminVO> detail(@PathVariable Long schoolId) {
        SchoolAdminVO data = schoolService.getSchoolAdminDetail(schoolId);
        if (data == null) {
            return ApiResponse.fail("school not found");
        }
        return ApiResponse.success(data);
    }

    //分页查询学校列表，并支持按地区和关键字筛选。
    @GetMapping
    public ApiResponse<PageResult<SchoolAdminVO>> page(@RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) Long provinceRegionId,
                                                       @RequestParam(required = false) Long cityRegionId,
                                                       @RequestParam(required = false) Long countyRegionId,
                                                       @RequestParam(required = false) Long townshipRegionId,
                                                       @RequestParam(required = false) Long pageNum,
                                                       @RequestParam(required = false) Long pageSize) {
        return ApiResponse.success(schoolService.pageSchools(
                keyword, provinceRegionId, cityRegionId, countyRegionId, townshipRegionId, pageNum, pageSize
        ));
    }
}
