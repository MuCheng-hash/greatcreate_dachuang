package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.enums.ResourceCategory;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.LocalEduResourceMapper;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.vo.ResourceAdminVO;
import com.redculture.platform.vo.request.ResourceUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LocalEduResourceServiceImpl extends ServiceImpl<LocalEduResourceMapper, LocalEduResource>
        implements LocalEduResourceService {

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 100L;

    @Override
    @Transactional
    public ResourceAdminVO updateResource(Long resourceId, ResourceUpdateRequest request) {
        LocalEduResource resource = requireResource(resourceId);
        fillResourceForUpdate(resource, request);
        resource.setReviewStatus(ReviewStatus.APPROVED);
        resource.setActive(true);
        updateById(resource);
        return toResourceAdminVO(getById(resourceId));
    }

    @Override
    public ResourceAdminVO getResourceAdminDetail(Long resourceId) {
        LocalEduResource resource = getById(resourceId);
        return resource == null ? null : toResourceAdminVO(resource);
    }

    @Override
    public PageResult<ResourceAdminVO> pageResources(String keyword,
                                                     String resourceCategory,
                                                     Long countyRegionId,
                                                     Long townshipRegionId,
                                                     ReviewStatus reviewStatus,
                                                     Long pageNum,
                                                     Long pageSize) {
        long safePageNum = pageNum == null || pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
        long safePageSize = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<LocalEduResource> wrapper = new LambdaQueryWrapper<LocalEduResource>()
                .eq(countyRegionId != null, LocalEduResource::getCountyRegionId, countyRegionId)
                .eq(townshipRegionId != null, LocalEduResource::getTownshipRegionId, townshipRegionId)
                .eq(reviewStatus != null, LocalEduResource::getReviewStatus, reviewStatus)
                .orderByDesc(LocalEduResource::getCreatedAt);

        if (StringUtils.hasText(keyword)) {
            String cleanKeyword = keyword.trim();
            wrapper.and(item -> item.like(LocalEduResource::getResourceName, cleanKeyword)
                    .or()
                    .like(LocalEduResource::getResourceCode, cleanKeyword)
                    .or()
                    .like(LocalEduResource::getAddress, cleanKeyword)
                    .or()
                    .like(LocalEduResource::getOrganizationName, cleanKeyword));
        }
        if (StringUtils.hasText(resourceCategory)) {
            wrapper.apply("resource_category = {0}", resourceCategory.trim());
        }

        Page<LocalEduResource> page = page(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(
                page.getRecords().stream().map(this::toResourceAdminVO).toList(),
                page.getTotal(),
                safePageNum,
                safePageSize
        );
    }

    private LocalEduResource requireResource(Long resourceId) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId 不能为空");
        }
        LocalEduResource resource = getById(resourceId);
        if (resource == null) {
            throw new IllegalArgumentException("资源不存在");
        }
        return resource;
    }

    private void fillResourceForUpdate(LocalEduResource resource, ResourceUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        resource.setResourceName(valueOrOriginal(request.getResourceName(), resource.getResourceName()));
        resource.setResourceCategory(valueOrOriginal(request.getResourceCategory(), resource.getResourceCategory()));
        resource.setResourceSubcategory(valueOrOriginal(request.getResourceSubcategory(), resource.getResourceSubcategory()));
        resource.setRegionId(valueOrOriginal(request.getRegionId(), resource.getRegionId()));
        resource.setCountyRegionId(valueOrOriginal(request.getCountyRegionId(), resource.getCountyRegionId()));
        resource.setTownshipRegionId(valueOrOriginal(request.getTownshipRegionId(), resource.getTownshipRegionId()));
        resource.setAddress(valueOrOriginal(request.getAddress(), resource.getAddress()));
        resource.setLongitude(valueOrOriginal(request.getLongitude(), resource.getLongitude()));
        resource.setLatitude(valueOrOriginal(request.getLatitude(), resource.getLatitude()));
        resource.setOrganizationName(valueOrOriginal(request.getOrganizationName(), resource.getOrganizationName()));
        resource.setContactPhone(valueOrOriginal(request.getContactPhone(), resource.getContactPhone()));
        resource.setOpeningTimeDesc(valueOrOriginal(request.getOpeningTimeDesc(), resource.getOpeningTimeDesc()));
        resource.setReservationRequired(valueOrOriginal(request.getReservationRequired(), resource.getReservationRequired()));
        resource.setRecommendedVisitMinutes(valueOrOriginal(request.getRecommendedVisitMinutes(), resource.getRecommendedVisitMinutes()));
        resource.setIntro(valueOrOriginal(request.getIntro(), resource.getIntro()));
        resource.setEducationValue(valueOrOriginal(request.getEducationValue(), resource.getEducationValue()));
        resource.setActivitySuggestion(valueOrOriginal(request.getActivitySuggestion(), resource.getActivitySuggestion()));
        resource.setTargetGrade(valueOrOriginal(request.getTargetGrade(), resource.getTargetGrade()));
        resource.setSafetyNote(valueOrOriginal(request.getSafetyNote(), resource.getSafetyNote()));
        resource.setSourceId(valueOrOriginal(request.getSourceId(), resource.getSourceId()));
        resource.setActive(valueOrOriginal(request.getActive(), resource.getActive()));
    }

    private ResourceAdminVO toResourceAdminVO(LocalEduResource resource) {
        ResourceAdminVO vo = new ResourceAdminVO();
        vo.setResourceId(resource.getResourceId());
        vo.setResourceCode(resource.getResourceCode());
        vo.setResourceName(resource.getResourceName());
        vo.setResourceCategory(enumValue(resource.getResourceCategory()));
        vo.setResourceSubcategory(resource.getResourceSubcategory());
        vo.setRegionId(resource.getRegionId());
        vo.setCountyRegionId(resource.getCountyRegionId());
        vo.setTownshipRegionId(resource.getTownshipRegionId());
        vo.setAddress(resource.getAddress());
        vo.setLongitude(resource.getLongitude());
        vo.setLatitude(resource.getLatitude());
        vo.setOrganizationName(resource.getOrganizationName());
        vo.setContactPhone(resource.getContactPhone());
        vo.setOpeningTimeDesc(resource.getOpeningTimeDesc());
        vo.setReservationRequired(resource.getReservationRequired());
        vo.setRecommendedVisitMinutes(resource.getRecommendedVisitMinutes());
        vo.setIntro(resource.getIntro());
        vo.setEducationValue(resource.getEducationValue());
        vo.setActivitySuggestion(resource.getActivitySuggestion());
        vo.setTargetGrade(resource.getTargetGrade());
        vo.setSafetyNote(resource.getSafetyNote());
        vo.setSourceId(resource.getSourceId());
        vo.setReviewStatus(enumValue(resource.getReviewStatus()));
        vo.setActive(resource.getActive());
        vo.setCreatedAt(resource.getCreatedAt());
        vo.setUpdatedAt(resource.getUpdatedAt());
        return vo;
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private <T> T valueOrOriginal(T newValue, T originalValue) {
        return newValue == null ? originalValue : newValue;
    }

    private String valueOrOriginal(String newValue, String originalValue) {
        return newValue == null ? originalValue : clean(newValue);
    }

    private String enumValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Object enumValue = value.getClass().getMethod("getValue").invoke(value);
            return enumValue == null ? null : String.valueOf(enumValue);
        } catch (ReflectiveOperationException exception) {
            return String.valueOf(value);
        }
    }
}
