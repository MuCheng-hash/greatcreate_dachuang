package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.enums.ReachabilityLevel;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.enums.SchoolResourceRelationType;
import com.redculture.platform.enums.TravelMode;
import com.redculture.platform.mapper.SchoolResourceRelMapper;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.SchoolResourceRelService;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolResourceCandidateResultVO;
import com.redculture.platform.vo.SchoolResourceCandidateVO;
import com.redculture.platform.vo.SchoolResourceRelAdminVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.request.SchoolResourceRelBatchCreateRequest;
import com.redculture.platform.vo.request.SchoolResourceRelCreateRequest;
import com.redculture.platform.vo.request.SchoolResourceRelUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SchoolResourceRelServiceImpl extends ServiceImpl<SchoolResourceRelMapper, SchoolResourceRel>
        implements SchoolResourceRelService {

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 100L;
    private static final double DEFAULT_RADIUS_KM = 5D;
    private static final double MAX_RADIUS_KM = 50D;

    private final SchoolService schoolService;
    private final LocalEduResourceService localEduResourceService;

    public SchoolResourceRelServiceImpl(SchoolService schoolService,
                                        LocalEduResourceService localEduResourceService) {
        this.schoolService = schoolService;
        this.localEduResourceService = localEduResourceService;
    }

    @Override
    @Transactional
    public SchoolResourceRelAdminVO createRelation(SchoolResourceRelCreateRequest request) {
        validateCreateRequest(request);
        ensureSchoolExists(request.getSchoolId());
        ensureResourceExists(request.getResourceId());
        ensureRelationUnique(request.getSchoolId(), request.getResourceId(), request.getRelationType(), null);

        SchoolResourceRel relation = new SchoolResourceRel();
        relation.setSchoolId(request.getSchoolId());
        relation.setResourceId(request.getResourceId());
        relation.setRelationType(defaultRelationType(request.getRelationType()));
        relation.setDistanceMeters(request.getDistanceMeters());
        relation.setRecommendedTravelMode(defaultTravelMode(request.getRecommendedTravelMode()));
        relation.setEstimatedDurationMinutes(request.getEstimatedDurationMinutes());
        relation.setReachabilityLevel(defaultReachability(request.getReachabilityLevel()));
        relation.setPriorityLevel(defaultPriority(request.getPriorityLevel()));
        relation.setEducationThemeSummary(clean(request.getEducationThemeSummary()));
        relation.setSourceId(request.getSourceId());
        relation.setReviewStatus(ReviewStatus.DRAFT);
        save(relation);
        return buildAdminVO(getById(relation.getRelId()));
    }

    @Override
    @Transactional
    public SchoolResourceRelAdminVO updateRelation(Long relId, SchoolResourceRelUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        SchoolResourceRel relation = requireRelation(relId);
        SchoolResourceRelationType nextRelationType = valueOrOriginal(request.getRelationType(), relation.getRelationType());
        ensureRelationUnique(relation.getSchoolId(), relation.getResourceId(), nextRelationType, relId);

        relation.setRelationType(nextRelationType);
        relation.setDistanceMeters(valueOrOriginal(request.getDistanceMeters(), relation.getDistanceMeters()));
        relation.setRecommendedTravelMode(valueOrOriginal(request.getRecommendedTravelMode(), relation.getRecommendedTravelMode()));
        relation.setEstimatedDurationMinutes(valueOrOriginal(request.getEstimatedDurationMinutes(), relation.getEstimatedDurationMinutes()));
        relation.setReachabilityLevel(valueOrOriginal(request.getReachabilityLevel(), relation.getReachabilityLevel()));
        relation.setPriorityLevel(valueOrOriginal(request.getPriorityLevel(), relation.getPriorityLevel()));
        relation.setEducationThemeSummary(valueOrOriginal(request.getEducationThemeSummary(), relation.getEducationThemeSummary()));
        relation.setSourceId(valueOrOriginal(request.getSourceId(), relation.getSourceId()));
        relation.setReviewStatus(valueOrOriginal(request.getReviewStatus(), relation.getReviewStatus()));
        updateById(relation);
        return buildAdminVO(getById(relId));
    }

    @Override
    @Transactional
    public boolean deleteRelation(Long relId) {
        requireRelation(relId);
        return removeById(relId);
    }

    @Override
    public SchoolResourceCandidateResultVO listResourceCandidates(Long schoolId, Double radiusKm) {
        School school = requireActiveSchoolWithLocation(schoolId);
        double effectiveRadiusKm = effectiveRadiusKm(radiusKm);
        List<LocalEduResource> resources = localEduResourceService.list(
                new LambdaQueryWrapper<LocalEduResource>()
                        .eq(LocalEduResource::getReviewStatus, ReviewStatus.APPROVED)
                        .eq(LocalEduResource::getActive, true)
                        .isNotNull(LocalEduResource::getLongitude)
                        .isNotNull(LocalEduResource::getLatitude)
        );
        Map<Long, SchoolResourceRel> existingRelations = existingRelationsByResourceId(schoolId);
        List<SchoolResourceCandidateVO> candidates = resources.stream()
                .map(resource -> toCandidate(school, resource, existingRelations.get(resource.getResourceId())))
                .filter(candidate -> candidate.getDistanceMeters() != null
                        && candidate.getDistanceMeters() <= Math.round(effectiveRadiusKm * 1000D))
                .sorted(Comparator
                        .comparing(SchoolResourceCandidateVO::getAlreadyLinked).reversed()
                        .thenComparing(SchoolResourceCandidateVO::getDistanceMeters))
                .toList();
        return buildCandidateResult(school, effectiveRadiusKm, candidates);
    }

    @Override
    @Transactional
    public SchoolResourceCandidateResultVO batchCreateRelations(Long schoolId, SchoolResourceRelBatchCreateRequest request) {
        if (request == null || request.getResourceIds() == null || request.getResourceIds().isEmpty()) {
            throw new IllegalArgumentException("resourceIds is required");
        }
        School school = requireActiveSchoolWithLocation(schoolId);
        double effectiveRadiusKm = effectiveRadiusKm(request.getRadiusKm());
        Set<Long> requestedIds = request.getResourceIds().stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        if (requestedIds.isEmpty()) {
            throw new IllegalArgumentException("resourceIds is required");
        }
        Map<Long, SchoolResourceRel> existingRelations = existingRelationsByResourceId(schoolId);
        List<LocalEduResource> resources = localEduResourceService.list(
                new LambdaQueryWrapper<LocalEduResource>()
                        .in(LocalEduResource::getResourceId, requestedIds)
                        .eq(LocalEduResource::getReviewStatus, ReviewStatus.APPROVED)
                        .eq(LocalEduResource::getActive, true)
                        .isNotNull(LocalEduResource::getLongitude)
                        .isNotNull(LocalEduResource::getLatitude)
        );
        List<SchoolResourceRel> relations = new ArrayList<>();
        for (LocalEduResource resource : resources) {
            if (existingRelations.containsKey(resource.getResourceId())) {
                continue;
            }
            SchoolResourceCandidateVO candidate = toCandidate(school, resource, null);
            if (candidate.getDistanceMeters() == null
                    || candidate.getDistanceMeters() > Math.round(effectiveRadiusKm * 1000D)) {
                continue;
            }
            relations.add(toRelation(schoolId, resource.getResourceId(), request.getRelationType(), candidate));
        }
        if (!relations.isEmpty()) {
            saveBatch(relations);
        }
        return listResourceCandidates(schoolId, effectiveRadiusKm);
    }

    @Override
    public PageResult<SchoolResourceRelAdminVO> listBySchoolId(Long schoolId, Long pageNum, Long pageSize) {
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        return pageRelations(new LambdaQueryWrapper<SchoolResourceRel>()
                .eq(SchoolResourceRel::getSchoolId, schoolId)
                .orderByAsc(SchoolResourceRel::getPriorityLevel)
                .orderByAsc(SchoolResourceRel::getDistanceMeters), pageNum, pageSize);
    }

    @Override
    public PageResult<SchoolResourceRelAdminVO> listByResourceId(Long resourceId, Long pageNum, Long pageSize) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
        return pageRelations(new LambdaQueryWrapper<SchoolResourceRel>()
                .eq(SchoolResourceRel::getResourceId, resourceId)
                .orderByAsc(SchoolResourceRel::getPriorityLevel)
                .orderByAsc(SchoolResourceRel::getDistanceMeters), pageNum, pageSize);
    }

    private PageResult<SchoolResourceRelAdminVO> pageRelations(LambdaQueryWrapper<SchoolResourceRel> wrapper,
                                                               Long pageNum,
                                                               Long pageSize) {
        long safePageNum = pageNum == null || pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
        long safePageSize = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<SchoolResourceRel> page = page(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(
                page.getRecords().stream().map(this::buildAdminVO).toList(),
                page.getTotal(),
                safePageNum,
                safePageSize
        );
    }

    private void validateCreateRequest(SchoolResourceRelCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (request.getSchoolId() == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        if (request.getResourceId() == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
    }

    private void ensureSchoolExists(Long schoolId) {
        if (schoolService.getById(schoolId) == null) {
            throw new IllegalArgumentException("school not found");
        }
    }

    private void ensureResourceExists(Long resourceId) {
        if (localEduResourceService.getById(resourceId) == null) {
            throw new IllegalArgumentException("resource not found");
        }
    }

    private School requireActiveSchoolWithLocation(Long schoolId) {
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        School school = schoolService.getById(schoolId);
        if (school == null || !Boolean.TRUE.equals(school.getActive())) {
            throw new IllegalArgumentException("school not found");
        }
        if (school.getLongitude() == null || school.getLatitude() == null) {
            throw new IllegalArgumentException("school location is required");
        }
        return school;
    }

    private Map<Long, SchoolResourceRel> existingRelationsByResourceId(Long schoolId) {
        return list(new LambdaQueryWrapper<SchoolResourceRel>()
                .eq(SchoolResourceRel::getSchoolId, schoolId))
                .stream()
                .collect(Collectors.toMap(SchoolResourceRel::getResourceId, Function.identity(),
                        (first, second) -> first));
    }

    private SchoolResourceCandidateVO toCandidate(School school,
                                                  LocalEduResource resource,
                                                  SchoolResourceRel existingRelation) {
        Integer distanceMeters = calculateDistanceMeters(
                school.getLatitude(),
                school.getLongitude(),
                resource.getLatitude(),
                resource.getLongitude()
        );
        ReachabilityLevel reachability = inferReachability(distanceMeters);
        SchoolResourceCandidateVO vo = new SchoolResourceCandidateVO();
        vo.setRelId(existingRelation == null ? null : existingRelation.getRelId());
        vo.setSchoolId(school.getSchoolId());
        vo.setResourceId(resource.getResourceId());
        vo.setAlreadyLinked(existingRelation != null);
        vo.setDistanceMeters(existingRelation == null ? distanceMeters : valueOrOriginal(existingRelation.getDistanceMeters(), distanceMeters));
        vo.setRelationType(enumValue(existingRelation == null ? SchoolResourceRelationType.NEARBY : existingRelation.getRelationType()));
        vo.setRecommendedTravelMode(enumValue(existingRelation == null
                ? inferTravelMode(distanceMeters)
                : existingRelation.getRecommendedTravelMode()));
        vo.setEstimatedDurationMinutes(existingRelation == null
                ? inferDurationMinutes(distanceMeters)
                : existingRelation.getEstimatedDurationMinutes());
        vo.setReachabilityLevel(enumValue(existingRelation == null ? reachability : existingRelation.getReachabilityLevel()));
        vo.setPriorityLevel(existingRelation == null ? inferPriority(distanceMeters) : existingRelation.getPriorityLevel());
        vo.setEducationThemeSummary(existingRelation == null
                ? defaultThemeSummary(resource)
                : existingRelation.getEducationThemeSummary());
        vo.setResource(toResourceSummary(resource));
        return vo;
    }

    private SchoolResourceRel toRelation(Long schoolId,
                                         Long resourceId,
                                         SchoolResourceRelationType relationType,
                                         SchoolResourceCandidateVO candidate) {
        SchoolResourceRel relation = new SchoolResourceRel();
        relation.setSchoolId(schoolId);
        relation.setResourceId(resourceId);
        relation.setRelationType(defaultRelationType(relationType));
        relation.setDistanceMeters(candidate.getDistanceMeters());
        relation.setRecommendedTravelMode(parseTravelMode(candidate.getRecommendedTravelMode()));
        relation.setEstimatedDurationMinutes(candidate.getEstimatedDurationMinutes());
        relation.setReachabilityLevel(parseReachability(candidate.getReachabilityLevel()));
        relation.setPriorityLevel(candidate.getPriorityLevel());
        relation.setEducationThemeSummary(candidate.getEducationThemeSummary());
        relation.setReviewStatus(ReviewStatus.APPROVED);
        return relation;
    }

    private SchoolResourceCandidateResultVO buildCandidateResult(School school,
                                                                 double radiusKm,
                                                                 List<SchoolResourceCandidateVO> candidates) {
        SchoolResourceCandidateResultVO vo = new SchoolResourceCandidateResultVO();
        vo.setSchool(toSchoolSummary(school));
        vo.setRadiusKm(radiusKm);
        vo.setCandidateCount(candidates.size());
        vo.setLinkedCount((int) candidates.stream().filter(candidate -> Boolean.TRUE.equals(candidate.getAlreadyLinked())).count());
        vo.setCandidates(candidates);
        return vo;
    }

    private SchoolSummaryVO toSchoolSummary(School school) {
        SchoolSummaryVO vo = new SchoolSummaryVO();
        vo.setSchoolId(school.getSchoolId());
        vo.setSchoolName(school.getSchoolName());
        vo.setProvinceRegionId(school.getProvinceRegionId());
        vo.setCityRegionId(school.getCityRegionId());
        vo.setCountyRegionId(school.getCountyRegionId());
        vo.setTownshipRegionId(school.getTownshipRegionId());
        vo.setSchoolType(school.getSchoolType());
        vo.setAddress(school.getAddress());
        vo.setLongitude(school.getLongitude());
        vo.setLatitude(school.getLatitude());
        return vo;
    }

    private LocalEduResourceSummaryVO toResourceSummary(LocalEduResource resource) {
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

    private double effectiveRadiusKm(Double radiusKm) {
        if (radiusKm == null || radiusKm <= 0) {
            return DEFAULT_RADIUS_KM;
        }
        return Math.min(radiusKm, MAX_RADIUS_KM);
    }

    private Integer calculateDistanceMeters(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return null;
        }
        double earthRadiusMeters = 6371000D;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1.doubleValue())) * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return BigDecimal.valueOf(earthRadiusMeters * c).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private TravelMode inferTravelMode(Integer distanceMeters) {
        if (distanceMeters == null) {
            return TravelMode.UNKNOWN;
        }
        if (distanceMeters <= 1000) {
            return TravelMode.WALK;
        }
        if (distanceMeters <= 3000) {
            return TravelMode.BIKE;
        }
        return TravelMode.DRIVE;
    }

    private Integer inferDurationMinutes(Integer distanceMeters) {
        if (distanceMeters == null) {
            return null;
        }
        if (distanceMeters <= 1000) {
            return Math.max(5, (int) Math.round(distanceMeters / 70D));
        }
        if (distanceMeters <= 3000) {
            return Math.max(8, (int) Math.round(distanceMeters / 180D));
        }
        return Math.max(10, (int) Math.round(distanceMeters / 500D));
    }

    private ReachabilityLevel inferReachability(Integer distanceMeters) {
        if (distanceMeters == null) {
            return ReachabilityLevel.UNKNOWN;
        }
        if (distanceMeters <= 1000) {
            return ReachabilityLevel.NEAR;
        }
        if (distanceMeters <= 5000) {
            return ReachabilityLevel.MEDIUM;
        }
        if (distanceMeters <= 15000) {
            return ReachabilityLevel.FAR;
        }
        return ReachabilityLevel.VERY_FAR;
    }

    private Integer inferPriority(Integer distanceMeters) {
        if (distanceMeters == null) {
            return 5;
        }
        if (distanceMeters <= 1000) {
            return 1;
        }
        if (distanceMeters <= 3000) {
            return 2;
        }
        if (distanceMeters <= 5000) {
            return 3;
        }
        return 5;
    }

    private String defaultThemeSummary(LocalEduResource resource) {
        return firstNonBlank(resource.getEducationValue(), resource.getIntro(), resource.getResourceSubcategory());
    }

    private void ensureRelationUnique(Long schoolId,
                                      Long resourceId,
                                      SchoolResourceRelationType relationType,
                                      Long excludeRelId) {
        LambdaQueryWrapper<SchoolResourceRel> wrapper = new LambdaQueryWrapper<SchoolResourceRel>()
                .eq(SchoolResourceRel::getSchoolId, schoolId)
                .eq(SchoolResourceRel::getResourceId, resourceId)
                .eq(SchoolResourceRel::getRelationType, relationType);
        if (excludeRelId != null) {
            wrapper.ne(SchoolResourceRel::getRelId, excludeRelId);
        }
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("relation already exists");
        }
    }

    private SchoolResourceRel requireRelation(Long relId) {
        if (relId == null) {
            throw new IllegalArgumentException("relId is required");
        }
        SchoolResourceRel relation = getById(relId);
        if (relation == null) {
            throw new IllegalArgumentException("relation not found");
        }
        return relation;
    }

    private SchoolResourceRelAdminVO buildAdminVO(SchoolResourceRel relation) {
        School school = schoolService.getById(relation.getSchoolId());
        LocalEduResource resource = localEduResourceService.getById(relation.getResourceId());

        SchoolResourceRelAdminVO vo = new SchoolResourceRelAdminVO();
        vo.setRelId(relation.getRelId());
        vo.setSchoolId(relation.getSchoolId());
        vo.setSchoolName(school == null ? null : school.getSchoolName());
        vo.setResourceId(relation.getResourceId());
        vo.setResourceName(resource == null ? null : resource.getResourceName());
        vo.setRelationType(enumValue(relation.getRelationType()));
        vo.setDistanceMeters(relation.getDistanceMeters());
        vo.setRecommendedTravelMode(enumValue(relation.getRecommendedTravelMode()));
        vo.setEstimatedDurationMinutes(relation.getEstimatedDurationMinutes());
        vo.setReachabilityLevel(enumValue(relation.getReachabilityLevel()));
        vo.setPriorityLevel(relation.getPriorityLevel());
        vo.setEducationThemeSummary(relation.getEducationThemeSummary());
        vo.setSourceId(relation.getSourceId());
        vo.setReviewStatus(enumValue(relation.getReviewStatus()));
        vo.setCreatedAt(relation.getCreatedAt());
        vo.setUpdatedAt(relation.getUpdatedAt());
        return vo;
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private SchoolResourceRelationType defaultRelationType(SchoolResourceRelationType value) {
        return value == null ? SchoolResourceRelationType.NEARBY : value;
    }

    private TravelMode defaultTravelMode(TravelMode value) {
        return value == null ? TravelMode.UNKNOWN : value;
    }

    private ReachabilityLevel defaultReachability(ReachabilityLevel value) {
        return value == null ? ReachabilityLevel.UNKNOWN : value;
    }

    private Integer defaultPriority(Integer value) {
        return value == null ? 3 : value;
    }

    private TravelMode parseTravelMode(String value) {
        if (!StringUtils.hasText(value)) {
            return TravelMode.UNKNOWN;
        }
        for (TravelMode mode : TravelMode.values()) {
            if (mode.getValue().equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return TravelMode.UNKNOWN;
    }

    private ReachabilityLevel parseReachability(String value) {
        if (!StringUtils.hasText(value)) {
            return ReachabilityLevel.UNKNOWN;
        }
        for (ReachabilityLevel level : ReachabilityLevel.values()) {
            if (level.getValue().equalsIgnoreCase(value) || level.name().equalsIgnoreCase(value)) {
                return level;
            }
        }
        return ReachabilityLevel.UNKNOWN;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
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
