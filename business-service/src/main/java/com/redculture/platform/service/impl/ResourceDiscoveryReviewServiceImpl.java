package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.DataSource;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.ResourceDiscoveryCandidate;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.enums.DiscoveryAnalysisStatus;
import com.redculture.platform.enums.DiscoveryDecisionStatus;
import com.redculture.platform.enums.ReachabilityLevel;
import com.redculture.platform.enums.ResourceCategory;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.enums.SchoolResourceRelationType;
import com.redculture.platform.enums.SourceType;
import com.redculture.platform.enums.TravelMode;
import com.redculture.platform.mapper.DataSourceMapper;
import com.redculture.platform.mapper.ResourceDiscoveryCandidateMapper;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.ResourceDiscoveryReviewService;
import com.redculture.platform.service.SchoolResourceRelService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.vo.discovery.DiscoveryCandidateVO;
import com.redculture.platform.vo.request.DiscoveryCandidateReviewRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ResourceDiscoveryReviewServiceImpl implements ResourceDiscoveryReviewService {

    private static final long DEFAULT_PAGE_SIZE = 20L;

    private final ResourceDiscoveryCandidateMapper candidateMapper;
    private final LocalEduResourceService resourceService;
    private final SchoolResourceRelService relationService;
    private final SchoolService schoolService;
    private final DataSourceMapper dataSourceMapper;
    private final ObjectMapper objectMapper;

    public ResourceDiscoveryReviewServiceImpl(ResourceDiscoveryCandidateMapper candidateMapper,
                                              LocalEduResourceService resourceService,
                                              SchoolResourceRelService relationService,
                                              SchoolService schoolService,
                                              DataSourceMapper dataSourceMapper,
                                              ObjectMapper objectMapper) {
        this.candidateMapper = candidateMapper;
        this.resourceService = resourceService;
        this.relationService = relationService;
        this.schoolService = schoolService;
        this.dataSourceMapper = dataSourceMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<DiscoveryCandidateVO> pageCandidates(Long schoolId, String analysisStatus,
                                                            String decisionStatus, Long pageNum, Long pageSize) {
        long safePage = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long safeSize = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, 100);
        LambdaQueryWrapper<ResourceDiscoveryCandidate> wrapper = new LambdaQueryWrapper<ResourceDiscoveryCandidate>()
                .eq(schoolId != null, ResourceDiscoveryCandidate::getSchoolId, schoolId)
                .eq(parseAnalysisStatus(analysisStatus) != null,
                        ResourceDiscoveryCandidate::getAnalysisStatus, parseAnalysisStatus(analysisStatus))
                .eq(parseDecisionStatus(decisionStatus) != null,
                        ResourceDiscoveryCandidate::getDecisionStatus, parseDecisionStatus(decisionStatus))
                .orderByDesc(ResourceDiscoveryCandidate::getLastSeenAt);
        Page<ResourceDiscoveryCandidate> page = candidateMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(page.getRecords().stream().map(this::toVO).toList(),
                page.getTotal(), safePage, safeSize);
    }

    @Override
    public DiscoveryCandidateVO getCandidate(Long candidateId) {
        return toVO(requireCandidate(candidateId));
    }

    @Override
    @Transactional(transactionManager = "mysqlTransactionManager")
    public DiscoveryCandidateVO approve(Long candidateId, DiscoveryCandidateReviewRequest request) {
        ResourceDiscoveryCandidate candidate = requireCandidate(candidateId);
        School school = schoolService.getById(candidate.getSchoolId());
        if (school == null) {
            throw new IllegalArgumentException("school not found");
        }
        LocalEduResource resource = resourceService.getOne(new LambdaQueryWrapper<LocalEduResource>()
                .eq(LocalEduResource::getExternalProvider, candidate.getProvider())
                .eq(LocalEduResource::getExternalPlaceId, candidate.getProviderPlaceId())
                .last("LIMIT 1"));
        Long sourceId = ensureAmapSource();
        if (resource == null) {
            resource = new LocalEduResource();
            resource.setResourceCode(buildResourceCode(candidate));
            resource.setExternalProvider(candidate.getProvider());
            resource.setExternalPlaceId(candidate.getProviderPlaceId());
        }
        resource.setResourceName(firstNonBlank(request == null ? null : request.getResourceName(), candidate.getPlaceName()));
        resource.setResourceCategory(firstNonNull(request == null ? null : request.getResourceCategory(),
                candidate.getAiCategory(), ResourceCategory.OTHER));
        resource.setResourceSubcategory(firstNonBlank(request == null ? null : request.getResourceSubcategory(),
                candidate.getAiSubcategory(), candidate.getProviderTypeName()));
        resource.setRegionId(school.getRegionId());
        resource.setCountyRegionId(school.getCountyRegionId());
        resource.setTownshipRegionId(school.getTownshipRegionId());
        resource.setAddress(candidate.getAddress());
        resource.setLongitude(candidate.getLongitude());
        resource.setLatitude(candidate.getLatitude());
        resource.setOrganizationName(candidate.getPlaceName());
        resource.setContactPhone(candidate.getContactPhone());
        resource.setOpeningTimeDesc(candidate.getOpeningHours());
        resource.setReservationRequired(false);
        resource.setIntro(candidate.getAiRationale());
        resource.setEducationValue(firstNonBlank(request == null ? null : request.getEducationValue(), candidate.getAiRationale()));
        resource.setActivitySuggestion(firstNonBlank(request == null ? null : request.getActivitySuggestion(), candidate.getActivitySuggestion()));
        resource.setTargetGrade(firstNonBlank(request == null ? null : request.getTargetGrade(), candidate.getTargetGrades()));
        resource.setSafetyNote(candidate.getVerificationNotes());
        resource.setSourceId(sourceId);
        resource.setSourceCheckedAt(LocalDateTime.now());
        resource.setReviewStatus(ReviewStatus.APPROVED);
        resource.setActive(true);
        if (resource.getResourceId() == null) {
            resourceService.save(resource);
        } else {
            resourceService.updateById(resource);
        }

        SchoolResourceRel relation = relationService.getOne(new LambdaQueryWrapper<SchoolResourceRel>()
                .eq(SchoolResourceRel::getSchoolId, candidate.getSchoolId())
                .eq(SchoolResourceRel::getResourceId, resource.getResourceId())
                .eq(SchoolResourceRel::getRelationType, SchoolResourceRelationType.NEARBY)
                .last("LIMIT 1"));
        if (relation == null) {
            relation = new SchoolResourceRel();
            relation.setSchoolId(candidate.getSchoolId());
            relation.setResourceId(resource.getResourceId());
            relation.setRelationType(SchoolResourceRelationType.NEARBY);
        }
        relation.setDistanceMeters(candidate.getDistanceMeters());
        relation.setRecommendedTravelMode(travelMode(candidate.getDistanceMeters()));
        relation.setReachabilityLevel(reachability(candidate.getDistanceMeters()));
        relation.setPriorityLevel(priority(candidate.getAiConfidence()));
        relation.setEducationThemeSummary(truncate(firstNonBlank(candidate.getAiRationale(),
                String.join("、", readThemes(candidate.getEducationThemesJson()))), 255));
        relation.setSourceId(sourceId);
        relation.setReviewStatus(ReviewStatus.APPROVED);
        if (relation.getRelId() == null) {
            relationService.save(relation);
        } else {
            relationService.updateById(relation);
        }

        candidate.setDecisionStatus(DiscoveryDecisionStatus.APPROVED);
        candidate.setMatchedResourceId(resource.getResourceId());
        applyReviewMetadata(candidate, request);
        candidateMapper.updateById(candidate);
        return toVO(candidate);
    }

    @Override
    @Transactional(transactionManager = "mysqlTransactionManager")
    public DiscoveryCandidateVO reject(Long candidateId, DiscoveryCandidateReviewRequest request) {
        ResourceDiscoveryCandidate candidate = requireCandidate(candidateId);
        candidate.setDecisionStatus(DiscoveryDecisionStatus.REJECTED);
        applyReviewMetadata(candidate, request);
        candidateMapper.updateById(candidate);
        return toVO(candidate);
    }

    @Override
    @Transactional(transactionManager = "mysqlTransactionManager")
    public DiscoveryCandidateVO reopen(Long candidateId, DiscoveryCandidateReviewRequest request) {
        ResourceDiscoveryCandidate candidate = requireCandidate(candidateId);
        if (candidate.getDecisionStatus() != DiscoveryDecisionStatus.REJECTED) {
            throw new IllegalArgumentException("only rejected candidates can be reopened");
        }
        candidate.setDecisionStatus(DiscoveryDecisionStatus.PENDING);
        candidate.setMatchedResourceId(null);
        applyReviewMetadata(candidate, request);
        candidateMapper.updateById(candidate);
        return toVO(candidate);
    }

    private ResourceDiscoveryCandidate requireCandidate(Long candidateId) {
        ResourceDiscoveryCandidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("discovery candidate not found");
        }
        return candidate;
    }

    private Long ensureAmapSource() {
        DataSource source = dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getSourceName, "高德地图 POI")
                .last("LIMIT 1"));
        if (source == null) {
            source = new DataSource();
            source.setSourceName("高德地图 POI");
            source.setSourceType(SourceType.OTHER);
            source.setOrganizationName("高德地图");
            source.setBaseUrl("https://www.amap.com");
            source.setReliabilityLevel(3);
            source.setLicenseNote("使用时遵守高德开放平台服务条款");
            source.setCrawlAllowed(false);
            source.setRemark("服务端周边 POI 发现来源");
            dataSourceMapper.insert(source);
        }
        return source.getSourceId();
    }

    private void applyReviewMetadata(ResourceDiscoveryCandidate candidate, DiscoveryCandidateReviewRequest request) {
        candidate.setReviewedBy(clean(request == null ? null : request.getReviewerName()));
        candidate.setReviewRemark(clean(request == null ? null : request.getReviewRemark()));
        candidate.setReviewedAt(LocalDateTime.now());
    }

    private String buildResourceCode(ResourceDiscoveryCandidate candidate) {
        String normalized = candidate.getProviderPlaceId().replaceAll("[^A-Za-z0-9]", "");
        String code = "RES_AMAP_" + normalized;
        if (code.length() > 50) {
            code = code.substring(0, 50);
        }
        if (resourceService.count(new LambdaQueryWrapper<LocalEduResource>()
                .eq(LocalEduResource::getResourceCode, code)) > 0) {
            code = "RES_AMAP_C" + candidate.getCandidateId();
        }
        return code;
    }

    private DiscoveryCandidateVO toVO(ResourceDiscoveryCandidate candidate) {
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

    private DiscoveryAnalysisStatus parseAnalysisStatus(String value) {
        if (!StringUtils.hasText(value)) return null;
        for (DiscoveryAnalysisStatus status : DiscoveryAnalysisStatus.values()) {
            if (status.getValue().equalsIgnoreCase(value.trim())) return status;
        }
        return null;
    }

    private DiscoveryDecisionStatus parseDecisionStatus(String value) {
        if (!StringUtils.hasText(value)) return null;
        for (DiscoveryDecisionStatus status : DiscoveryDecisionStatus.values()) {
            if (status.getValue().equalsIgnoreCase(value.trim())) return status;
        }
        return null;
    }

    private TravelMode travelMode(Integer distance) {
        if (distance == null) return TravelMode.UNKNOWN;
        if (distance <= 1500) return TravelMode.WALK;
        if (distance <= 5000) return TravelMode.BIKE;
        return TravelMode.BUS;
    }

    private ReachabilityLevel reachability(Integer distance) {
        if (distance == null) return ReachabilityLevel.UNKNOWN;
        if (distance <= 3000) return ReachabilityLevel.NEAR;
        if (distance <= 10000) return ReachabilityLevel.MEDIUM;
        if (distance <= 30000) return ReachabilityLevel.FAR;
        return ReachabilityLevel.VERY_FAR;
    }

    private int priority(BigDecimal confidence) {
        if (confidence == null) return 3;
        if (confidence.doubleValue() >= .85D) return 1;
        if (confidence.doubleValue() >= .70D) return 2;
        return 3;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) if (value != null) return value;
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String enumValue(Object value) {
        if (value == null) return null;
        try {
            Object enumValue = value.getClass().getMethod("getValue").invoke(value);
            return enumValue == null ? null : String.valueOf(enumValue);
        } catch (ReflectiveOperationException exception) {
            return String.valueOf(value);
        }
    }
}
