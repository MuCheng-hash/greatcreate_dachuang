package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.entity.TeachingActivityPlan;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.SchoolResourceRelService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolResourceItemVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.TeachingActivityPlanVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SchoolMapServiceImpl implements SchoolMapService {

    private static final double DEFAULT_RADIUS_KM = 30D;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final SchoolService schoolService;
    private final LocalEduResourceService localEduResourceService;
    private final SchoolResourceRelService schoolResourceRelService;
    private final TeachingActivityPlanService teachingActivityPlanService;

    public SchoolMapServiceImpl(SchoolService schoolService,
                                LocalEduResourceService localEduResourceService,
                                SchoolResourceRelService schoolResourceRelService,
                                TeachingActivityPlanService teachingActivityPlanService) {
        this.schoolService = schoolService;
        this.localEduResourceService = localEduResourceService;
        this.schoolResourceRelService = schoolResourceRelService;
        this.teachingActivityPlanService = teachingActivityPlanService;
    }

    @Override
    public List<SchoolSummaryVO> listSchools(Long countyRegionId,
                                             Long townshipRegionId,
                                             String keyword,
                                             Integer limit) {
        LambdaQueryWrapper<School> wrapper = baseSchoolWrapper()
                .orderByAsc(School::getSchoolName);

        if (countyRegionId != null) {
            wrapper.eq(School::getCountyRegionId, countyRegionId);
        }
        if (townshipRegionId != null) {
            wrapper.eq(School::getTownshipRegionId, townshipRegionId);
        }
        if (StringUtils.hasText(keyword)) {
            String cleanKeyword = keyword.trim();
            wrapper.and(item -> item.like(School::getSchoolName, cleanKeyword)
                    .or()
                    .like(School::getSchoolAlias, cleanKeyword)
                    .or()
                    .like(School::getAddress, cleanKeyword));
        }
        wrapper.last("LIMIT " + effectiveLimit(limit, MAX_LIMIT));

        return schoolService.list(wrapper).stream()
                .map(school -> toSchoolSummary(school, null))
                .collect(Collectors.toList());
    }

    @Override
    public List<SchoolSummaryVO> findNearbySchools(BigDecimal longitude,
                                                   BigDecimal latitude,
                                                   Double radiusKm,
                                                   Integer limit) {
        if (longitude == null || latitude == null) {
            return Collections.emptyList();
        }

        double effectiveRadiusKm = radiusKm == null || radiusKm <= 0 ? DEFAULT_RADIUS_KM : radiusKm;
        int effectiveLimit = effectiveLimit(limit, DEFAULT_LIMIT);

        return schoolService.list(baseSchoolWrapper()
                        .isNotNull(School::getLongitude)
                        .isNotNull(School::getLatitude))
                .stream()
                .map(school -> toSchoolSummary(school, calculateDistanceKm(
                        latitude.doubleValue(),
                        longitude.doubleValue(),
                        school.getLatitude().doubleValue(),
                        school.getLongitude().doubleValue()
                )))
                .filter(school -> school.getDistanceKm() != null && school.getDistanceKm() <= effectiveRadiusKm)
                .sorted(Comparator.comparing(SchoolSummaryVO::getDistanceKm))
                .limit(effectiveLimit)
                .collect(Collectors.toList());
    }

    @Override
    public SchoolMapDetailVO getSchoolDetail(Long schoolId) {
        if (schoolId == null) {
            return null;
        }

        School school = schoolService.getById(schoolId);
        if (school == null || !Boolean.TRUE.equals(school.getActive())
                || school.getReviewStatus() != ReviewStatus.APPROVED) {
            return null;
        }

        List<SchoolResourceRel> relations = schoolResourceRelService.list(
                new LambdaQueryWrapper<SchoolResourceRel>()
                        .eq(SchoolResourceRel::getSchoolId, schoolId)
                        .eq(SchoolResourceRel::getReviewStatus, ReviewStatus.APPROVED)
                        .orderByAsc(SchoolResourceRel::getPriorityLevel)
                        .orderByAsc(SchoolResourceRel::getDistanceMeters)
        );

        Map<Long, LocalEduResource> resourceById = loadResources(relations);
        List<SchoolResourceItemVO> resourceItems = relations.stream()
                .map(rel -> toSchoolResourceItem(rel, resourceById.get(rel.getResourceId())))
                .filter(item -> item.getResource() != null)
                .collect(Collectors.toList());

        List<TeachingActivityPlanVO> activityPlans = teachingActivityPlanService.list(
                        new LambdaQueryWrapper<TeachingActivityPlan>()
                                .eq(TeachingActivityPlan::getSchoolId, schoolId)
                                .eq(TeachingActivityPlan::getReviewStatus, ReviewStatus.APPROVED)
                                .eq(TeachingActivityPlan::getActive, true)
                                .orderByAsc(TeachingActivityPlan::getPlanId)
                )
                .stream()
                .map(this::toTeachingActivityPlan)
                .collect(Collectors.toList());

        SchoolMapDetailVO detailVO = new SchoolMapDetailVO();
        detailVO.setSchool(toSchoolSummary(school, null));
        detailVO.setResources(resourceItems);
        detailVO.setActivityPlans(activityPlans);
        detailVO.setResourceCount(resourceItems.size());
        detailVO.setActivityPlanCount(activityPlans.size());
        return detailVO;
    }

    private LambdaQueryWrapper<School> baseSchoolWrapper() {
        return new LambdaQueryWrapper<School>()
                .eq(School::getReviewStatus, ReviewStatus.APPROVED)
                .eq(School::getActive, true);
    }

    private Map<Long, LocalEduResource> loadResources(List<SchoolResourceRel> relations) {
        Set<Long> resourceIds = relations.stream()
                .map(SchoolResourceRel::getResourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (resourceIds.isEmpty()) {
            return Collections.emptyMap();
        }

        return localEduResourceService.list(new LambdaQueryWrapper<LocalEduResource>()
                        .in(LocalEduResource::getResourceId, resourceIds)
                        .eq(LocalEduResource::getReviewStatus, ReviewStatus.APPROVED)
                        .eq(LocalEduResource::getActive, true))
                .stream()
                .collect(Collectors.toMap(
                        LocalEduResource::getResourceId,
                        resource -> resource,
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
    }

    private SchoolSummaryVO toSchoolSummary(School school, Double distanceKm) {
        SchoolSummaryVO vo = new SchoolSummaryVO();
        vo.setSchoolId(school.getSchoolId());
        vo.setSchoolCode(school.getSchoolCode());
        vo.setSchoolName(school.getSchoolName());
        vo.setSchoolLevel(enumValue(school.getSchoolLevel()));
        vo.setSchoolType(school.getSchoolType());
        vo.setSchoolNature(enumValue(school.getSchoolNature()));
        vo.setRuralSchool(school.getRuralSchool());
        vo.setTeachingPoint(school.getTeachingPoint());
        vo.setAddress(school.getAddress());
        vo.setLongitude(school.getLongitude());
        vo.setLatitude(school.getLatitude());
        vo.setGeoConfidence(enumValue(school.getGeoConfidence()));
        vo.setDistanceKm(distanceKm);
        return vo;
    }

    private SchoolResourceItemVO toSchoolResourceItem(SchoolResourceRel rel, LocalEduResource resource) {
        SchoolResourceItemVO vo = new SchoolResourceItemVO();
        vo.setSchoolId(rel.getSchoolId());
        vo.setResourceId(rel.getResourceId());
        vo.setRelationType(enumValue(rel.getRelationType()));
        vo.setDistanceMeters(rel.getDistanceMeters());
        vo.setRecommendedTravelMode(enumValue(rel.getRecommendedTravelMode()));
        vo.setEstimatedDurationMinutes(rel.getEstimatedDurationMinutes());
        vo.setReachabilityLevel(enumValue(rel.getReachabilityLevel()));
        vo.setPriorityLevel(rel.getPriorityLevel());
        vo.setEducationThemeSummary(rel.getEducationThemeSummary());
        vo.setResource(resource == null ? null : toLocalEduResourceSummary(resource));
        return vo;
    }

    private LocalEduResourceSummaryVO toLocalEduResourceSummary(LocalEduResource resource) {
        LocalEduResourceSummaryVO vo = new LocalEduResourceSummaryVO();
        vo.setResourceId(resource.getResourceId());
        vo.setResourceCode(resource.getResourceCode());
        vo.setResourceName(resource.getResourceName());
        vo.setResourceCategory(enumValue(resource.getResourceCategory()));
        vo.setResourceSubcategory(resource.getResourceSubcategory());
        vo.setAddress(resource.getAddress());
        vo.setLongitude(resource.getLongitude());
        vo.setLatitude(resource.getLatitude());
        vo.setIntro(resource.getIntro());
        vo.setEducationValue(resource.getEducationValue());
        vo.setTargetGrade(resource.getTargetGrade());
        return vo;
    }

    private TeachingActivityPlanVO toTeachingActivityPlan(TeachingActivityPlan plan) {
        TeachingActivityPlanVO vo = new TeachingActivityPlanVO();
        vo.setPlanId(plan.getPlanId());
        vo.setPlanCode(plan.getPlanCode());
        vo.setSchoolId(plan.getSchoolId());
        vo.setResourceId(plan.getResourceId());
        vo.setTheme(plan.getTheme());
        vo.setActivityType(enumValue(plan.getActivityType()));
        vo.setSuitableGrade(plan.getSuitableGrade());
        vo.setObjectiveText(plan.getObjectiveText());
        vo.setActivityContent(plan.getActivityContent());
        vo.setPreparationText(plan.getPreparationText());
        vo.setSafetyText(plan.getSafetyText());
        vo.setExpectedOutcome(plan.getExpectedOutcome());
        vo.setDurationMinutes(plan.getDurationMinutes());
        return vo;
    }

    private int effectiveLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private Double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371.0D;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return BigDecimal.valueOf(earthRadius * c).setScale(2, RoundingMode.HALF_UP).doubleValue();
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
