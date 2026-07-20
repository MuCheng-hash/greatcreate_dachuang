package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.ResourceDiscoveryCandidate;
import com.redculture.platform.entity.ResourceDiscoveryRun;
import com.redculture.platform.entity.ResourceDiscoveryRunItem;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.enums.DiscoveryAnalysisStatus;
import com.redculture.platform.enums.DiscoveryDecisionStatus;
import com.redculture.platform.enums.DiscoveryRunStatus;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.ResourceDiscoveryCandidateMapper;
import com.redculture.platform.mapper.ResourceDiscoveryRunItemMapper;
import com.redculture.platform.mapper.ResourceDiscoveryRunMapper;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.ResourceDiscoveryService;
import com.redculture.platform.service.SchoolResourceRelService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.discovery.AmapPoiClient;
import com.redculture.platform.service.discovery.ResourceDiscoveryWorker;
import com.redculture.platform.vo.discovery.ApprovedResourceDetailVO;
import com.redculture.platform.vo.discovery.DiscoveryCandidateVO;
import com.redculture.platform.vo.discovery.DiscoveryRunVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ResourceDiscoveryServiceImpl implements ResourceDiscoveryService {

    private static final Set<Integer> ALLOWED_RADII_KM = Set.of(1, 3, 5, 10);

    private final ResourceDiscoveryRunMapper runMapper;
    private final ResourceDiscoveryCandidateMapper candidateMapper;
    private final ResourceDiscoveryRunItemMapper runItemMapper;
    private final SchoolService schoolService;
    private final LocalEduResourceService resourceService;
    private final SchoolResourceRelService relationService;
    private final ResourceDiscoveryWorker worker;
    private final AmapPoiClient amapPoiClient;
    private final AppMapProperties properties;
    private final ObjectMapper objectMapper;

    public ResourceDiscoveryServiceImpl(ResourceDiscoveryRunMapper runMapper,
                                        ResourceDiscoveryCandidateMapper candidateMapper,
                                        ResourceDiscoveryRunItemMapper runItemMapper,
                                        SchoolService schoolService,
                                        LocalEduResourceService resourceService,
                                        SchoolResourceRelService relationService,
                                        ResourceDiscoveryWorker worker,
                                        AmapPoiClient amapPoiClient,
                                        AppMapProperties properties,
                                        ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.candidateMapper = candidateMapper;
        this.runItemMapper = runItemMapper;
        this.schoolService = schoolService;
        this.resourceService = resourceService;
        this.relationService = relationService;
        this.worker = worker;
        this.amapPoiClient = amapPoiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized DiscoveryRunVO startRun(Long schoolId, Integer radiusKm, boolean force) {
        School school = requireSchool(schoolId);
        int effectiveRadiusKm = radiusKm == null ? 5 : radiusKm;
        if (!ALLOWED_RADII_KM.contains(effectiveRadiusKm)) {
            throw new IllegalArgumentException("radiusKm must be one of 1, 3, 5, or 10");
        }
        if (school.getLongitude() == null || school.getLatitude() == null) {
            throw new IllegalArgumentException("school coordinates are required");
        }
        int radiusMeters = effectiveRadiusKm * 1000;
        recoverStaleRuns(schoolId, radiusMeters);

        if (!force) {
            ResourceDiscoveryRun cached = runMapper.selectOne(new LambdaQueryWrapper<ResourceDiscoveryRun>()
                    .eq(ResourceDiscoveryRun::getSchoolId, schoolId)
                    .eq(ResourceDiscoveryRun::getRadiusMeters, radiusMeters)
                    .eq(ResourceDiscoveryRun::getProvider, "amap")
                    .eq(ResourceDiscoveryRun::getStatus, DiscoveryRunStatus.COMPLETED)
                    .gt(ResourceDiscoveryRun::getCacheExpiresAt, LocalDateTime.now())
                    .orderByDesc(ResourceDiscoveryRun::getCompletedAt)
                    .last("LIMIT 1"));
            if (cached != null) {
                return buildRunVO(cached, true, false);
            }
        }

        ResourceDiscoveryRun active = runMapper.selectOne(new LambdaQueryWrapper<ResourceDiscoveryRun>()
                .eq(ResourceDiscoveryRun::getSchoolId, schoolId)
                .eq(ResourceDiscoveryRun::getRadiusMeters, radiusMeters)
                .in(ResourceDiscoveryRun::getStatus, DiscoveryRunStatus.PENDING, DiscoveryRunStatus.RUNNING)
                .orderByDesc(ResourceDiscoveryRun::getCreatedAt)
                .last("LIMIT 1"));
        if (active != null) {
            return buildRunVO(active, false, false);
        }

        ResourceDiscoveryRun previous = latestCompleted(schoolId, radiusMeters);
        ResourceDiscoveryRun run = new ResourceDiscoveryRun();
        run.setSchoolId(schoolId);
        run.setRadiusMeters(radiusMeters);
        run.setProvider("amap");
        run.setStatus(DiscoveryRunStatus.PENDING);
        run.setForced(force);
        run.setProviderCount(0);
        run.setCandidateCount(0);
        run.setAnalysisCount(0);
        runMapper.insert(run);
        worker.executeAsync(run.getRunId());
        DiscoveryRunVO response = buildRunVO(run, false, false);
        if (previous != null) {
            response.setCandidates(loadRunCandidates(previous.getRunId(), false));
        }
        return response;
    }

    @Override
    public DiscoveryRunVO getRun(Long schoolId, Long runId, boolean adminView) {
        ResourceDiscoveryRun run = runMapper.selectById(runId);
        if (run == null || !schoolId.equals(run.getSchoolId())) {
            throw new IllegalArgumentException("discovery run not found");
        }
        return buildRunVO(run, false, adminView);
    }

    @Override
    public DiscoveryCandidateVO getCandidate(Long schoolId, Long candidateId, boolean adminView) {
        ResourceDiscoveryCandidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || !schoolId.equals(candidate.getSchoolId())) {
            throw new IllegalArgumentException("discovery candidate not found");
        }
        if (!adminView && !teacherVisible(candidate)) {
            throw new IllegalArgumentException("discovery candidate not available");
        }
        enrichProviderDetail(candidate);
        return toCandidateVO(candidate);
    }

    @Override
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
        vo.setResourceId(resource.getResourceId());
        vo.setResourceName(resource.getResourceName());
        vo.setResourceCategory(enumValue(resource.getResourceCategory()));
        vo.setResourceSubcategory(resource.getResourceSubcategory());
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
        vo.setExternalProvider(resource.getExternalProvider());
        vo.setExternalPlaceId(resource.getExternalPlaceId());
        vo.setSourceCheckedAt(resource.getSourceCheckedAt());
        vo.setDistanceMeters(relation.getDistanceMeters());
        vo.setRecommendedTravelMode(enumValue(relation.getRecommendedTravelMode()));
        vo.setEstimatedDurationMinutes(relation.getEstimatedDurationMinutes());
        vo.setEducationThemeSummary(relation.getEducationThemeSummary());
        return vo;
    }

    private void recoverStaleRuns(Long schoolId, int radiusMeters) {
        List<ResourceDiscoveryRun> stale = runMapper.selectList(new LambdaQueryWrapper<ResourceDiscoveryRun>()
                .eq(ResourceDiscoveryRun::getSchoolId, schoolId)
                .eq(ResourceDiscoveryRun::getRadiusMeters, radiusMeters)
                .in(ResourceDiscoveryRun::getStatus, DiscoveryRunStatus.PENDING, DiscoveryRunStatus.RUNNING)
                .lt(ResourceDiscoveryRun::getCreatedAt, LocalDateTime.now().minusMinutes(30)));
        for (ResourceDiscoveryRun run : stale) {
            run.setStatus(DiscoveryRunStatus.FAILED);
            run.setErrorMessage("discovery worker timed out");
            run.setCompletedAt(LocalDateTime.now());
            runMapper.updateById(run);
        }
    }

    private ResourceDiscoveryRun latestCompleted(Long schoolId, int radiusMeters) {
        return runMapper.selectOne(new LambdaQueryWrapper<ResourceDiscoveryRun>()
                .eq(ResourceDiscoveryRun::getSchoolId, schoolId)
                .eq(ResourceDiscoveryRun::getRadiusMeters, radiusMeters)
                .eq(ResourceDiscoveryRun::getStatus, DiscoveryRunStatus.COMPLETED)
                .orderByDesc(ResourceDiscoveryRun::getCompletedAt)
                .last("LIMIT 1"));
    }

    private DiscoveryRunVO buildRunVO(ResourceDiscoveryRun run, boolean cacheHit, boolean adminView) {
        DiscoveryRunVO vo = new DiscoveryRunVO();
        vo.setRunId(run.getRunId());
        vo.setSchoolId(run.getSchoolId());
        vo.setRadiusMeters(run.getRadiusMeters());
        vo.setProvider(run.getProvider());
        vo.setStatus(enumValue(run.getStatus()));
        vo.setCacheHit(cacheHit);
        vo.setForced(run.getForced());
        vo.setProviderCount(run.getProviderCount());
        vo.setCandidateCount(run.getCandidateCount());
        vo.setAnalysisCount(run.getAnalysisCount());
        vo.setErrorMessage(run.getErrorMessage());
        vo.setStartedAt(run.getStartedAt());
        vo.setCompletedAt(run.getCompletedAt());
        vo.setCacheExpiresAt(run.getCacheExpiresAt());
        vo.setCandidates(loadRunCandidates(run.getRunId(), adminView));
        return vo;
    }

    private List<DiscoveryCandidateVO> loadRunCandidates(Long runId, boolean adminView) {
        List<ResourceDiscoveryRunItem> items = runItemMapper.selectList(
                new LambdaQueryWrapper<ResourceDiscoveryRunItem>()
                        .eq(ResourceDiscoveryRunItem::getRunId, runId)
                        .orderByAsc(ResourceDiscoveryRunItem::getResultRank));
        if (items.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = items.stream().map(ResourceDiscoveryRunItem::getCandidateId).toList();
        Map<Long, ResourceDiscoveryCandidate> byId = new HashMap<>();
        candidateMapper.selectBatchIds(ids).forEach(candidate -> byId.put(candidate.getCandidateId(), candidate));
        return ids.stream()
                .map(byId::get)
                .filter(candidate -> candidate != null && (adminView || teacherVisible(candidate)))
                .map(this::toCandidateVO)
                .toList();
    }

    private boolean teacherVisible(ResourceDiscoveryCandidate candidate) {
        if (candidate.getDecisionStatus() != DiscoveryDecisionStatus.PENDING) {
            return false;
        }
        if (candidate.getAnalysisStatus() != DiscoveryAnalysisStatus.COMPLETED) {
            return true;
        }
        return Boolean.TRUE.equals(candidate.getIdeologicalRelevant())
                && candidate.getAiConfidence() != null
                && candidate.getAiConfidence().doubleValue() >= properties.getDiscoveryMinConfidence();
    }

    private void enrichProviderDetail(ResourceDiscoveryCandidate candidate) {
        if (StringUtils.hasText(candidate.getContactPhone()) && StringUtils.hasText(candidate.getOpeningHours())) {
            return;
        }
        try {
            AmapPoiClient.PoiRecord detail = amapPoiClient.getDetail(candidate.getProviderPlaceId());
            if (detail == null) {
                return;
            }
            candidate.setAddress(firstNonBlank(detail.address(), candidate.getAddress()));
            candidate.setProviderTypeCode(firstNonBlank(detail.providerTypeCode(), candidate.getProviderTypeCode()));
            candidate.setProviderTypeName(firstNonBlank(detail.providerTypeName(), candidate.getProviderTypeName()));
            candidate.setContactPhone(firstNonBlank(detail.contactPhone(), candidate.getContactPhone()));
            candidate.setOpeningHours(firstNonBlank(detail.openingHours(), candidate.getOpeningHours()));
            candidate.setRawJson(firstNonBlank(detail.rawJson(), candidate.getRawJson()));
            candidate.setLastSeenAt(LocalDateTime.now());
            candidateMapper.updateById(candidate);
        } catch (Exception ignored) {
            // The cached provider facts remain usable when detail lookup fails.
        }
    }

    private DiscoveryCandidateVO toCandidateVO(ResourceDiscoveryCandidate candidate) {
        DiscoveryCandidateVO vo = new DiscoveryCandidateVO();
        vo.setCandidateId(candidate.getCandidateId());
        vo.setSchoolId(candidate.getSchoolId());
        vo.setProvider(candidate.getProvider());
        vo.setProviderPlaceId(candidate.getProviderPlaceId());
        vo.setPlaceName(candidate.getPlaceName());
        vo.setAddress(candidate.getAddress());
        vo.setLongitude(candidate.getLongitude());
        vo.setLatitude(candidate.getLatitude());
        vo.setProviderTypeCode(candidate.getProviderTypeCode());
        vo.setProviderTypeName(candidate.getProviderTypeName());
        vo.setContactPhone(candidate.getContactPhone());
        vo.setOpeningHours(candidate.getOpeningHours());
        vo.setDistanceMeters(candidate.getDistanceMeters());
        vo.setAnalysisStatus(enumValue(candidate.getAnalysisStatus()));
        vo.setIdeologicalRelevant(candidate.getIdeologicalRelevant());
        vo.setAiCategory(enumValue(candidate.getAiCategory()));
        vo.setAiSubcategory(candidate.getAiSubcategory());
        vo.setAiConfidence(candidate.getAiConfidence());
        vo.setAiRationale(candidate.getAiRationale());
        vo.setEducationThemes(readThemes(candidate.getEducationThemesJson()));
        vo.setTargetGrades(candidate.getTargetGrades());
        vo.setActivitySuggestion(candidate.getActivitySuggestion());
        vo.setVerificationNotes(candidate.getVerificationNotes());
        vo.setDecisionStatus(enumValue(candidate.getDecisionStatus()));
        vo.setMatchedResourceId(candidate.getMatchedResourceId());
        vo.setLastError(candidate.getLastError());
        vo.setLastSeenAt(candidate.getLastSeenAt());
        vo.setLastAnalyzedAt(candidate.getLastAnalyzedAt());
        vo.setReviewedBy(candidate.getReviewedBy());
        vo.setReviewedAt(candidate.getReviewedAt());
        vo.setReviewRemark(candidate.getReviewRemark());
        return vo;
    }

    private List<String> readThemes(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            return new ArrayList<>();
        }
    }

    private School requireSchool(Long schoolId) {
        School school = schoolService.getById(schoolId);
        if (school == null || school.getReviewStatus() != ReviewStatus.APPROVED
                || !Boolean.TRUE.equals(school.getActive())) {
            throw new IllegalArgumentException("approved school not found");
        }
        return school;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
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
