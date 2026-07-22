package com.redculture.platform.service.discovery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.entity.ResourceDiscoveryCandidate;
import com.redculture.platform.entity.ResourceDiscoveryRun;
import com.redculture.platform.entity.ResourceDiscoveryRunItem;
import com.redculture.platform.entity.School;
import com.redculture.platform.enums.DiscoveryAnalysisStatus;
import com.redculture.platform.enums.DiscoveryDecisionStatus;
import com.redculture.platform.enums.DiscoveryRunStatus;
import com.redculture.platform.enums.ResourceCategory;
import com.redculture.platform.mapper.ResourceDiscoveryCandidateMapper;
import com.redculture.platform.mapper.ResourceDiscoveryRunItemMapper;
import com.redculture.platform.mapper.ResourceDiscoveryRunMapper;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.vo.discovery.DiscoveryClassificationResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ResourceDiscoveryWorker {

    private final ResourceDiscoveryRunMapper runMapper;
    private final ResourceDiscoveryCandidateMapper candidateMapper;
    private final ResourceDiscoveryRunItemMapper runItemMapper;
    private final SchoolService schoolService;
    private final AmapPoiClient amapPoiClient;
    private final DiscoveryLlmClient llmClient;
    private final AppMapProperties properties;
    private final ObjectMapper objectMapper;

    public ResourceDiscoveryWorker(ResourceDiscoveryRunMapper runMapper,
                                   ResourceDiscoveryCandidateMapper candidateMapper,
                                   ResourceDiscoveryRunItemMapper runItemMapper,
                                   SchoolService schoolService,
                                   AmapPoiClient amapPoiClient,
                                   DiscoveryLlmClient llmClient,
                                   AppMapProperties properties,
                                   ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.candidateMapper = candidateMapper;
        this.runItemMapper = runItemMapper;
        this.schoolService = schoolService;
        this.amapPoiClient = amapPoiClient;
        this.llmClient = llmClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Async("resourceDiscoveryExecutor")
    public void executeAsync(Long runId) {
        execute(runId);
    }

    public void execute(Long runId) {
        ResourceDiscoveryRun run = runMapper.selectById(runId);
        if (run == null || (run.getStatus() != DiscoveryRunStatus.PENDING
                && run.getStatus() != DiscoveryRunStatus.RUNNING)) {
            return;
        }
        School school = schoolService.getById(run.getSchoolId());
        if (school == null) {
            fail(run, "school not found");
            return;
        }
        run.setStatus(DiscoveryRunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        run.setErrorMessage(null);
        runMapper.updateById(run);

        try {
            List<AmapPoiClient.PoiRecord> records = amapPoiClient.searchNearby(school, run.getRadiusMeters());
            List<ResourceDiscoveryCandidate> candidates = upsertCandidates(run, records);
            int analyzed = classifyCandidates(school, candidates);
            run.setStatus(DiscoveryRunStatus.COMPLETED);
            run.setProviderCount(records.size());
            run.setCandidateCount(candidates.size());
            run.setAnalysisCount(analyzed);
            run.setCompletedAt(LocalDateTime.now());
            run.setCacheExpiresAt(run.getCompletedAt().plusHours(Math.max(1, properties.getDiscoveryCacheHours())));
            runMapper.updateById(run);
        } catch (Exception exception) {
            fail(run, safeMessage(exception));
        }
    }

    private List<ResourceDiscoveryCandidate> upsertCandidates(ResourceDiscoveryRun run,
                                                               List<AmapPoiClient.PoiRecord> records) {
        runItemMapper.delete(new LambdaQueryWrapper<ResourceDiscoveryRunItem>()
                .eq(ResourceDiscoveryRunItem::getRunId, run.getRunId()));
        LocalDateTime now = LocalDateTime.now();
        List<ResourceDiscoveryCandidate> candidates = new ArrayList<>();
        int rank = 1;
        for (AmapPoiClient.PoiRecord record : records) {
            ResourceDiscoveryCandidate candidate = candidateMapper.selectOne(
                    new LambdaQueryWrapper<ResourceDiscoveryCandidate>()
                            .eq(ResourceDiscoveryCandidate::getSchoolId, run.getSchoolId())
                            .eq(ResourceDiscoveryCandidate::getProvider, "amap")
                            .eq(ResourceDiscoveryCandidate::getProviderPlaceId, record.providerPlaceId())
                            .last("LIMIT 1")
            );
            if (candidate == null) {
                candidate = new ResourceDiscoveryCandidate();
                candidate.setSchoolId(run.getSchoolId());
                candidate.setProvider("amap");
                candidate.setProviderPlaceId(record.providerPlaceId());
                candidate.setAnalysisStatus(DiscoveryAnalysisStatus.UNANALYZED);
                candidate.setDecisionStatus(DiscoveryDecisionStatus.PENDING);
                candidate.setFirstSeenAt(now);
            }
            applyProviderFacts(candidate, record, now);
            if (candidate.getCandidateId() == null) {
                candidateMapper.insert(candidate);
            } else {
                candidateMapper.updateById(candidate);
            }
            ResourceDiscoveryRunItem item = new ResourceDiscoveryRunItem();
            item.setRunId(run.getRunId());
            item.setCandidateId(candidate.getCandidateId());
            item.setResultRank(rank++);
            item.setDistanceMeters(record.distanceMeters());
            runItemMapper.insertItem(item);
            candidates.add(candidate);
        }
        return candidates;
    }

    private void applyProviderFacts(ResourceDiscoveryCandidate candidate,
                                    AmapPoiClient.PoiRecord record,
                                    LocalDateTime now) {
        candidate.setPlaceName(record.placeName());
        candidate.setAddress(record.address());
        candidate.setLongitude(record.longitude());
        candidate.setLatitude(record.latitude());
        candidate.setProviderTypeCode(record.providerTypeCode());
        candidate.setProviderTypeName(record.providerTypeName());
        candidate.setContactPhone(record.contactPhone());
        candidate.setOpeningHours(record.openingHours());
        candidate.setDistanceMeters(record.distanceMeters());
        candidate.setRawJson(record.rawJson());
        candidate.setLastSeenAt(now);
    }

    private int classifyCandidates(School school, List<ResourceDiscoveryCandidate> candidates) {
        List<ResourceDiscoveryCandidate> pending = candidates.stream()
                .filter(candidate -> candidate.getDecisionStatus() == DiscoveryDecisionStatus.PENDING)
                .toList();
        int batchSize = Math.max(1, Math.min(20, properties.getDiscoveryLlmBatchSize()));
        int analyzed = 0;
        for (int start = 0; start < pending.size(); start += batchSize) {
            List<ResourceDiscoveryCandidate> batch = pending.subList(start, Math.min(start + batchSize, pending.size()));
            DiscoveryClassificationResponse response = llmClient.classify(school, batch);
            if (response == null || response.getResults() == null) {
                batch.forEach(candidate -> markUnanalyzed(candidate, "LLM classification unavailable"));
                continue;
            }
            Map<String, ResourceDiscoveryCandidate> byProviderId = batch.stream().collect(Collectors.toMap(
                    ResourceDiscoveryCandidate::getProviderPlaceId,
                    item -> item,
                    (first, second) -> first,
                    HashMap::new
            ));
            Set<String> appliedIds = response.getResults().stream()
                    .map(result -> applyClassification(byProviderId, result))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            analyzed += appliedIds.size();
            batch.stream()
                    .filter(candidate -> !appliedIds.contains(candidate.getProviderPlaceId()))
                    .forEach(candidate -> markUnanalyzed(candidate, "LLM returned no valid classification"));
        }
        return analyzed;
    }

    private String applyClassification(Map<String, ResourceDiscoveryCandidate> byProviderId,
                                       DiscoveryClassificationResponse.Result result) {
        if (result == null || !StringUtils.hasText(result.getProviderPlaceId())
                || result.getIdeologicalRelevant() == null
                || result.getConfidence() == null
                || result.getConfidence().compareTo(BigDecimal.ZERO) < 0
                || result.getConfidence().compareTo(BigDecimal.ONE) > 0) {
            return null;
        }
        ResourceDiscoveryCandidate candidate = byProviderId.get(result.getProviderPlaceId());
        if (candidate == null) {
            return null;
        }
        ResourceCategory category = parseCategory(result.getResourceCategory());
        if (Boolean.TRUE.equals(result.getIdeologicalRelevant()) && category == null) {
            return null;
        }
        candidate.setAnalysisStatus(DiscoveryAnalysisStatus.COMPLETED);
        candidate.setIdeologicalRelevant(result.getIdeologicalRelevant());
        candidate.setAiCategory(category);
        candidate.setAiSubcategory(clean(result.getResourceSubcategory(), 100));
        candidate.setAiConfidence(result.getConfidence());
        candidate.setAiRationale(clean(result.getRationale(), 4000));
        candidate.setEducationThemesJson(toJson(result.getEducationThemes()));
        candidate.setTargetGrades(clean(result.getTargetGrades(), 255));
        candidate.setActivitySuggestion(clean(result.getActivitySuggestion(), 4000));
        candidate.setVerificationNotes(clean(result.getVerificationNotes(), 4000));
        candidate.setLastAnalyzedAt(LocalDateTime.now());
        candidate.setLastError(null);
        candidateMapper.updateById(candidate);
        return candidate.getProviderPlaceId();
    }

    private void markUnanalyzed(ResourceDiscoveryCandidate candidate, String message) {
        candidate.setAnalysisStatus(DiscoveryAnalysisStatus.UNANALYZED);
        candidate.setIdeologicalRelevant(null);
        candidate.setLastError(clean(message, 500));
        candidateMapper.updateById(candidate);
    }

    private ResourceCategory parseCategory(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return Arrays.stream(ResourceCategory.values())
                .filter(category -> category.getValue().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElse(null);
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .limit(8)
                    .toList());
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private void fail(ResourceDiscoveryRun run, String message) {
        run.setStatus(DiscoveryRunStatus.FAILED);
        run.setErrorMessage(clean(message, 500));
        run.setCompletedAt(LocalDateTime.now());
        run.setCacheExpiresAt(null);
        runMapper.updateById(run);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (StringUtils.hasText(message) ? ": " + message : "");
    }

    private String clean(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }
}
