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
import com.redculture.platform.vo.request.ResourceCreateRequest;
import com.redculture.platform.vo.request.ResourceReviewRequest;
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
    public ResourceAdminVO createResource(ResourceCreateRequest request) {
        validateCreateRequest(request);
        ensureResourceCodeUnique(request.getResourceCode(), null);

        LocalEduResource resource = new LocalEduResource();
        fillResourceForCreate(resource, request);
        resource.setReviewStatus(ReviewStatus.DRAFT);
        resource.setActive(true);
        save(resource);
        return toResourceAdminVO(resource);
    }

    @Override
    @Transactional
    public ResourceAdminVO updateResource(Long resourceId, ResourceUpdateRequest request) {
        LocalEduResource resource = requireResource(resourceId);
        fillResourceForUpdate(resource, request);
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
                    .like(LocalEduResource::getResourceAlias, cleanKeyword)
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

    @Override
    @Transactional
    public ResourceAdminVO submitReview(Long resourceId) {
        LocalEduResource resource = requireResource(resourceId);
        resource.setReviewStatus(ReviewStatus.PENDING);
        updateById(resource);
        return toResourceAdminVO(getById(resourceId));
    }

    @Override
    @Transactional
    public ResourceAdminVO approve(Long resourceId, ResourceReviewRequest request) {
        LocalEduResource resource = requireResource(resourceId);
        resource.setReviewStatus(ReviewStatus.APPROVED);
        updateById(resource);
        return toResourceAdminVO(getById(resourceId));
    }

    @Override
    @Transactional
    public ResourceAdminVO reject(Long resourceId, ResourceReviewRequest request) {
        LocalEduResource resource = requireResource(resourceId);
        resource.setReviewStatus(ReviewStatus.REJECTED);
        updateById(resource);
        return toResourceAdminVO(getById(resourceId));
    }

    private void validateCreateRequest(ResourceCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (!StringUtils.hasText(request.getResourceCode())) {
            throw new IllegalArgumentException("resourceCode is required");
        }
        if (!StringUtils.hasText(request.getResourceName())) {
            throw new IllegalArgumentException("resourceName is required");
        }
    }

    private void ensureResourceCodeUnique(String resourceCode, Long excludeResourceId) {
        LambdaQueryWrapper<LocalEduResource> wrapper = new LambdaQueryWrapper<LocalEduResource>()
                .eq(LocalEduResource::getResourceCode, resourceCode.trim());
        if (excludeResourceId != null) {
            wrapper.ne(LocalEduResource::getResourceId, excludeResourceId);
        }
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("resourceCode already exists");
        }
    }

    private LocalEduResource requireResource(Long resourceId) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
        LocalEduResource resource = getById(resourceId);
        if (resource == null) {
            throw new IllegalArgumentException("resource not found");
        }
        return resource;
    }

    private void fillResourceForCreate(LocalEduResource resource, ResourceCreateRequest request) {
        resource.setResourceCode(request.getResourceCode().trim());
        resource.setResourceName(clean(request.getResourceName()));
        resource.setResourceAlias(clean(request.getResourceAlias()));
        resource.setResourceCategory(defaultCategory(request.getResourceCategory()));
        resource.setResourceSubcategory(clean(request.getResourceSubcategory()));
        resource.setRegionId(request.getRegionId());
        resource.setCountyRegionId(request.getCountyRegionId());
        resource.setTownshipRegionId(request.getTownshipRegionId());
        resource.setAddress(clean(request.getAddress()));
        resource.setLongitude(request.getLongitude());
        resource.setLatitude(request.getLatitude());
        resource.setOrganizationName(clean(request.getOrganizationName()));
        resource.setContactPhone(clean(request.getContactPhone()));
        resource.setOpeningTimeDesc(clean(request.getOpeningTimeDesc()));
        resource.setReservationRequired(defaultBoolean(request.getReservationRequired(), false));
        resource.setRecommendedVisitMinutes(request.getRecommendedVisitMinutes());
        resource.setIntro(clean(request.getIntro()));
        resource.setEducationValue(clean(request.getEducationValue()));
        resource.setActivitySuggestion(clean(request.getActivitySuggestion()));
        resource.setTargetGrade(clean(request.getTargetGrade()));
        resource.setSafetyNote(clean(request.getSafetyNote()));
        resource.setSourceId(request.getSourceId());
    }

    private void fillResourceForUpdate(LocalEduResource resource, ResourceUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        resource.setResourceName(valueOrOriginal(request.getResourceName(), resource.getResourceName()));
        resource.setResourceAlias(valueOrOriginal(request.getResourceAlias(), resource.getResourceAlias()));
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
        vo.setResourceAlias(resource.getResourceAlias());
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

    private Boolean defaultBoolean(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private ResourceCategory defaultCategory(ResourceCategory value) {
        return value == null ? ResourceCategory.OTHER : value;
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
