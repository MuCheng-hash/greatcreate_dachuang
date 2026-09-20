package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.vo.ResourceAdminVO;
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
@RequestMapping({"/api/admin/resources", "/admin/resources"})
//管理员查看并维护由图谱导入产生的思政教育资源。
public class ResourceAdminController {

    private final LocalEduResourceService localEduResourceService;

    public ResourceAdminController(LocalEduResourceService localEduResourceService) {
        this.localEduResourceService = localEduResourceService;
    }

    //修改指定资源。
    @PutMapping("/{resourceId}")
    public ApiResponse<ResourceAdminVO> update(@PathVariable Long resourceId,
                                               @RequestBody ResourceUpdateRequest request) {
        try {
            return ApiResponse.success("resource updated", localEduResourceService.updateResource(resourceId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //查询指定资源的后台详情。
    @GetMapping("/{resourceId}")
    public ApiResponse<ResourceAdminVO> detail(@PathVariable Long resourceId) {
        ResourceAdminVO data = localEduResourceService.getResourceAdminDetail(resourceId);
        if (data == null) {
            return ApiResponse.fail("资源不存在");
        }
        return ApiResponse.success(data);
    }

    //按条件分页查询资源列表。
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

}
