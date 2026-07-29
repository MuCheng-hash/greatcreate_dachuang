package com.redculture.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.vo.discovery.ApprovedResourceDetailVO;
import org.springframework.stereotype.Service;

@Service
public class ApprovedResourceQueryService {
    private final LocalEduResourceService resourceService;
    private final SchoolResourceRelService relationService;

    public ApprovedResourceQueryService(LocalEduResourceService resourceService,
                                        SchoolResourceRelService relationService) {
        this.resourceService = resourceService;
        this.relationService = relationService;
    }

    public ApprovedResourceDetailVO getApprovedResource(Long schoolId, Long resourceId) {
        SchoolResourceRel relation = relationService.getOne(new LambdaQueryWrapper<SchoolResourceRel>()
                .eq(SchoolResourceRel::getSchoolId, schoolId)
                .eq(SchoolResourceRel::getResourceId, resourceId)
                .eq(SchoolResourceRel::getReviewStatus, ReviewStatus.APPROVED)
                .last("LIMIT 1"));
        LocalEduResource resource = resourceService.getById(resourceId);
        if (relation == null || resource == null || resource.getReviewStatus() != ReviewStatus.APPROVED
                || !Boolean.TRUE.equals(resource.getActive())) {
            throw new IllegalArgumentException("approved resource not found");
        }
        ApprovedResourceDetailVO vo = new ApprovedResourceDetailVO();
        vo.setResourceId(resource.getResourceId()); vo.setResourceName(resource.getResourceName());
        vo.setResourceCategory(value(resource.getResourceCategory())); vo.setResourceSubcategory(resource.getResourceSubcategory());
        vo.setAddress(resource.getAddress()); vo.setLongitude(resource.getLongitude()); vo.setLatitude(resource.getLatitude());
        vo.setOrganizationName(resource.getOrganizationName()); vo.setContactPhone(resource.getContactPhone());
        vo.setOpeningTimeDesc(resource.getOpeningTimeDesc()); vo.setReservationRequired(resource.getReservationRequired());
        vo.setRecommendedVisitMinutes(resource.getRecommendedVisitMinutes()); vo.setIntro(resource.getIntro());
        vo.setEducationValue(resource.getEducationValue()); vo.setActivitySuggestion(resource.getActivitySuggestion());
        vo.setTargetGrade(resource.getTargetGrade()); vo.setSafetyNote(resource.getSafetyNote());
        vo.setExternalProvider(resource.getExternalProvider()); vo.setExternalPlaceId(resource.getExternalPlaceId());
        vo.setSourceCheckedAt(resource.getSourceCheckedAt()); vo.setDistanceMeters(relation.getDistanceMeters());
        vo.setRecommendedTravelMode(value(relation.getRecommendedTravelMode()));
        vo.setEstimatedDurationMinutes(relation.getEstimatedDurationMinutes());
        vo.setEducationThemeSummary(relation.getEducationThemeSummary());
        return vo;
    }

    private String value(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase();
    }
}
