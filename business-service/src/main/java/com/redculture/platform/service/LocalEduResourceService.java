package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.baomidou.mybatisplus.extension.service.IService;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.vo.ResourceAdminVO;
import com.redculture.platform.vo.request.ResourceUpdateRequest;

public interface LocalEduResourceService extends IService<LocalEduResource> {

    ResourceAdminVO updateResource(Long resourceId, ResourceUpdateRequest request);

    ResourceAdminVO getResourceAdminDetail(Long resourceId);

    PageResult<ResourceAdminVO> pageResources(String keyword,
                                              String resourceCategory,
                                              Long countyRegionId,
                                              Long townshipRegionId,
                                              ReviewStatus reviewStatus,
                                              Long pageNum,
                                              Long pageSize);

}
