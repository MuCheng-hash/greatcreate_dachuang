package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.entity.DataSource;
import com.redculture.platform.entity.EntitySourceRel;
import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.mapper.DataSourceMapper;
import com.redculture.platform.mapper.EntitySourceRelMapper;
import com.redculture.platform.service.AiTeachingPlanService;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.vo.GeneratedTeachingPlanCitationVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolResourceItemVO;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.TeachingActivityPlanVO;
import com.redculture.platform.vo.ai.TeachingPlanContextVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingActivityPlanCreateRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiTeachingPlanServiceImpl implements AiTeachingPlanService {

    private static final Logger log = LoggerFactory.getLogger(AiTeachingPlanServiceImpl.class);
    private static final int MAX_CONTENT_CHUNKS = 12;
    private static final int MAX_CITATIONS = 8;

    private final SchoolMapService schoolMapService;
    private final TeachingActivityPlanService teachingActivityPlanService;
    private final ContentChunkMapper contentChunkMapper;
    private final EntitySourceRelMapper entitySourceRelMapper;
    private final DataSourceMapper dataSourceMapper;
    private final Neo4jClient neo4jClient;
    private final AppMapProperties appMapProperties;

    public AiTeachingPlanServiceImpl(SchoolMapService schoolMapService,
                                     TeachingActivityPlanService teachingActivityPlanService,
                                     ContentChunkMapper contentChunkMapper,
                                     EntitySourceRelMapper entitySourceRelMapper,
                                     DataSourceMapper dataSourceMapper,
                                     Neo4jClient neo4jClient,
                                     AppMapProperties appMapProperties) {
        this.schoolMapService = schoolMapService;
        this.teachingActivityPlanService = teachingActivityPlanService;
        this.contentChunkMapper = contentChunkMapper;
        this.entitySourceRelMapper = entitySourceRelMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.neo4jClient = neo4jClient;
        this.appMapProperties = appMapProperties;
    }

    @Override
    public GeneratedTeachingPlanResponse generatePlan(TeachingPlanGenerateRequest request) {
        validateGenerateRequest(request);
        TeachingPlanContextVO context = buildContext(request);
        GeneratedTeachingPlanResponse llmResponse = callLlmService(context);
        GeneratedTeachingPlanResponse response = llmResponse == null ? buildFallbackResponse(context) : normalizeResponse(llmResponse, context, false);
        response.setCitations(validateCitations(response.getCitations(), context.getCitationCandidates()));
        if (response.getCitations().isEmpty()) {
            response.setCitations(context.getCitationCandidates().stream().limit(5).collect(Collectors.toList()));
        }
        return response;
    }

    @Override
    public TeachingActivityPlanAdminVO saveDraft(GeneratedTeachingPlanSaveRequest request) {
        validateSaveRequest(request);
        SchoolMapDetailVO detail = requireApprovedSchool(request.getSchoolId());

        TeachingActivityPlanCreateRequest createRequest = new TeachingActivityPlanCreateRequest();
        createRequest.setPlanCode(generatePlanCode());
        createRequest.setSchoolId(request.getSchoolId());
        createRequest.setResourceId(request.getResourceId());
        createRequest.setTheme(cleanOrDefault(request.getTheme(), "AI 生成教学活动方案"));
        createRequest.setActivityType(request.getActivityType() == null ? ActivityType.CLASSROOM : request.getActivityType());
        createRequest.setSuitableGrade(cleanOrDefault(request.getGrade(), "未指定年级"));
        createRequest.setObjectiveText(joinLines(request.getObjectives(), "教学目标待完善。"));
        createRequest.setActivityContent(joinLines(request.getActivityFlow(), "活动流程待完善。"));
        createRequest.setPreparationText(joinLines(request.getPreparation(), "课前准备待完善。"));
        createRequest.setSafetyText(joinLines(request.getSafetyNotes(), "安全提示待完善。"));
        createRequest.setExpectedOutcome(joinLines(mergeLists(request.getReflection(), request.getEvaluation()), "形成学习记录、交流展示和反思评价。"));
        createRequest.setDurationMinutes(request.getDurationMinutes());

        if (request.getResourceId() == null && detail.getResources() != null && !detail.getResources().isEmpty()) {
            createRequest.setResourceId(detail.getResources().get(0).getResourceId());
        }

        return teachingActivityPlanService.createPlan(createRequest);
    }

    private TeachingPlanContextVO buildContext(TeachingPlanGenerateRequest request) {
        SchoolMapDetailVO detail = requireApprovedSchool(request.getSchoolId());

        TeachingPlanContextVO context = new TeachingPlanContextVO();
        context.setRequest(request);
        context.setSchool(detail.getSchool());
        context.setResources(buildResourceContexts(detail.getResources()));
        context.setExistingPlans(detail.getActivityPlans() == null ? Collections.emptyList() : detail.getActivityPlans());
        context.setContentChunks(loadContentChunks(request, context));
        context.setCitationCandidates(loadCitationCandidates(context));
        context.setGraphFacts(loadGraphFacts(request.getSchoolId()));
        return context;
    }

    private List<TeachingPlanContextVO.ResourceContextVO> buildResourceContexts(List<SchoolResourceItemVO> items) {
        if (items == null) {
            return Collections.emptyList();
        }
        return items.stream()
                .filter(item -> item.getResource() != null)
                .map(item -> {
                    TeachingPlanContextVO.ResourceContextVO context = new TeachingPlanContextVO.ResourceContextVO();
                    context.setResourceId(item.getResourceId());
                    context.setRelationType(item.getRelationType());
                    context.setDistanceMeters(item.getDistanceMeters());
                    context.setTravelMode(item.getRecommendedTravelMode());
                    context.setReachabilityLevel(item.getReachabilityLevel());
                    context.setEducationThemeSummary(item.getEducationThemeSummary());
                    context.setResource(item.getResource());
                    return context;
                })
                .collect(Collectors.toList());
    }

    private List<TeachingPlanContextVO.ContentChunkContextVO> loadContentChunks(TeachingPlanGenerateRequest request,
                                                                                TeachingPlanContextVO context) {
        Map<EntityType, Set<Long>> entityIds = collectContextEntityIds(context);
        if (entityIds.isEmpty()) {
            return Collections.emptyList();
        }

        Set<EntityType> types = entityIds.keySet();
        Set<Long> ids = entityIds.values().stream().flatMap(Collection::stream).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContentChunk> chunks = contentChunkMapper.selectList(new LambdaQueryWrapper<ContentChunk>()
                .in(ContentChunk::getEntityType, types)
                .in(ContentChunk::getEntityId, ids)
                .orderByAsc(ContentChunk::getEntityType)
                .orderByAsc(ContentChunk::getEntityId)
                .orderByAsc(ContentChunk::getChunkIndex));

        String theme = clean(request.getTheme());
        return chunks.stream()
                .filter(chunk -> entityIds.getOrDefault(chunk.getEntityType(), Collections.emptySet()).contains(chunk.getEntityId()))
                .sorted(Comparator.comparing((ContentChunk chunk) -> relevanceScore(chunk, theme)).reversed())
                .limit(MAX_CONTENT_CHUNKS)
                .map(chunk -> {
                    TeachingPlanContextVO.ContentChunkContextVO item = new TeachingPlanContextVO.ContentChunkContextVO();
                    item.setChunkId(chunk.getChunkId());
                    item.setEntityType(enumValue(chunk.getEntityType()));
                    item.setEntityId(chunk.getEntityId());
                    item.setTitle(chunk.getChunkTitle());
                    item.setText(truncate(chunk.getChunkText(), 700));
                    item.setSourceId(chunk.getSourceId());
                    return item;
                })
                .collect(Collectors.toList());
    }

    private List<GeneratedTeachingPlanCitationVO> loadCitationCandidates(TeachingPlanContextVO context) {
        Map<String, GeneratedTeachingPlanCitationVO> citations = new LinkedHashMap<>();

        Map<EntityType, Set<Long>> entityIds = collectContextEntityIds(context);
        Set<Long> sourceIds = new LinkedHashSet<>();
        for (TeachingPlanContextVO.ContentChunkContextVO chunk : context.getContentChunks()) {
            GeneratedTeachingPlanCitationVO citation = new GeneratedTeachingPlanCitationVO();
            citation.setCitationId("chunk:" + chunk.getChunkId());
            citation.setTitle(cleanOrDefault(chunk.getTitle(), "内容分块 " + chunk.getChunkId()));
            citation.setSourceType("content_chunk");
            citation.setRelatedEntityType(chunk.getEntityType());
            citation.setRelatedEntityId(chunk.getEntityId());
            citation.setExcerpt(truncate(chunk.getText(), 180));
            citations.put(citation.getCitationId(), citation);
            if (chunk.getSourceId() != null) {
                sourceIds.add(chunk.getSourceId());
            }
        }

        if (!entityIds.isEmpty()) {
            Set<EntityType> types = entityIds.keySet();
            Set<Long> ids = entityIds.values().stream().flatMap(Collection::stream).collect(Collectors.toSet());
            List<EntitySourceRel> rels = entitySourceRelMapper.selectList(new LambdaQueryWrapper<EntitySourceRel>()
                    .in(EntitySourceRel::getEntityType, types)
                    .in(EntitySourceRel::getEntityId, ids)
                    .orderByDesc(EntitySourceRel::getCredibilityScore));
            for (EntitySourceRel rel : rels) {
                if (!entityIds.getOrDefault(rel.getEntityType(), Collections.emptySet()).contains(rel.getEntityId())) {
                    continue;
                }
                GeneratedTeachingPlanCitationVO citation = new GeneratedTeachingPlanCitationVO();
                citation.setCitationId("source-rel:" + rel.getRelId());
                citation.setTitle("来源记录 " + rel.getRelId());
                citation.setSourceType("entity_source");
                citation.setRelatedEntityType(enumValue(rel.getEntityType()));
                citation.setRelatedEntityId(rel.getEntityId());
                citation.setExcerpt(truncate(rel.getSourceExcerpt(), 180));
                citation.setUrl(rel.getSourceUrl());
                citations.putIfAbsent(citation.getCitationId(), citation);
                if (rel.getSourceId() != null) {
                    sourceIds.add(rel.getSourceId());
                }
            }
        }

        enrichCitationTitles(citations.values(), sourceIds);
        if (citations.isEmpty()) {
            addResourceFallbackCitations(context, citations);
        }
        return citations.values().stream().limit(MAX_CITATIONS).collect(Collectors.toList());
    }

    private void enrichCitationTitles(Collection<GeneratedTeachingPlanCitationVO> citations, Set<Long> sourceIds) {
        if (sourceIds.isEmpty()) {
            return;
        }
        Map<Long, DataSource> sourceById = dataSourceMapper.selectBatchIds(sourceIds).stream()
                .collect(Collectors.toMap(DataSource::getSourceId, source -> source, (first, second) -> first));
        for (GeneratedTeachingPlanCitationVO citation : citations) {
            if (!StringUtils.hasText(citation.getTitle()) || !citation.getTitle().startsWith("来源记录")) {
                continue;
            }
            DataSource source = sourceById.values().stream().findFirst().orElse(null);
            if (source != null) {
                citation.setTitle(source.getSourceName());
                citation.setSourceType(enumValue(source.getSourceType()));
                citation.setUrl(firstNonBlank(citation.getUrl(), source.getBaseUrl()));
            }
        }
    }

    private void addResourceFallbackCitations(TeachingPlanContextVO context,
                                              Map<String, GeneratedTeachingPlanCitationVO> citations) {
        for (TeachingPlanContextVO.ResourceContextVO item : context.getResources()) {
            if (item.getResource() == null) {
                continue;
            }
            GeneratedTeachingPlanCitationVO citation = new GeneratedTeachingPlanCitationVO();
            citation.setCitationId("resource:" + item.getResourceId());
            citation.setTitle(item.getResource().getResourceName());
            citation.setSourceType("approved_resource");
            citation.setRelatedEntityType(EntityType.RESOURCE.getValue());
            citation.setRelatedEntityId(item.getResourceId());
            citation.setExcerpt(firstNonBlank(item.getResource().getEducationValue(), item.getResource().getIntro(), item.getEducationThemeSummary()));
            citations.put(citation.getCitationId(), citation);
            if (citations.size() >= MAX_CITATIONS) {
                break;
            }
        }
    }

    private List<String> loadGraphFacts(Long schoolId) {
        try {
            String cypher = ""
                    + "MATCH (s:School {id: $schoolId})-[rel:SCHOOL_NEAR_RESOURCE]->(r:LocalEduResource) "
                    + "OPTIONAL MATCH (r)-[:HAS_TAG]->(t:Tag) "
                    + "RETURN r.name AS resourceName, rel.educationThemeSummary AS theme, "
                    + "rel.distanceMeters AS distanceMeters, collect(DISTINCT t.name) AS tags "
                    + "ORDER BY rel.priorityLevel DESC, rel.distanceMeters ASC LIMIT 8";
            return neo4jClient.query(cypher)
                    .bind(schoolId).to("schoolId")
                    .fetch()
                    .all()
                    .stream()
                    .map(this::toGraphFact)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
        } catch (Exception exception) {
            return Collections.emptyList();
        }
    }

    private GeneratedTeachingPlanResponse callLlmService(TeachingPlanContextVO context) {
        if (!StringUtils.hasText(appMapProperties.getLlmServiceBaseUrl())) {
            return null;
        }
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(3_000);
            requestFactory.setReadTimeout(60_000);
            return RestClient.builder()
                    .baseUrl(appMapProperties.getLlmServiceBaseUrl())
                    .requestFactory(requestFactory)
                    .build()
                    .post()
                    .uri("/llm/teaching-plan/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(context)
                    .retrieve()
                    .body(GeneratedTeachingPlanResponse.class);
        } catch (Exception exception) {
            log.warn("LLM teaching-plan request failed for endpoint {}: {}",
                    appMapProperties.getLlmServiceBaseUrl(), exception.getMessage(), exception);
            return null;
        }
    }

    private GeneratedTeachingPlanResponse normalizeResponse(GeneratedTeachingPlanResponse response,
                                                            TeachingPlanContextVO context,
                                                            boolean fallback) {
        if (response == null) {
            response = new GeneratedTeachingPlanResponse();
        }
        TeachingPlanGenerateRequest request = context.getRequest();
        response.setGenerationStatus(StringUtils.hasText(response.getGenerationStatus())
                ? response.getGenerationStatus()
                : (fallback ? "degraded" : "completed"));
        response.setMessage(StringUtils.hasText(response.getMessage())
                ? response.getMessage()
                : (fallback ? "已使用本地结构化兜底生成。" : "已完成结构化生成。"));
        response.setTheme(cleanOrDefault(response.getTheme(), request.getTheme()));
        response.setGrade(cleanOrDefault(response.getGrade(), request.getGrade()));
        response.setActivityType(cleanOrDefault(response.getActivityType(), enumValue(request.getActivityType())));
        response.setDurationMinutes(response.getDurationMinutes() == null ? request.getDurationMinutes() : response.getDurationMinutes());
        response.setPracticeRequired(response.getPracticeRequired() == null ? request.getPracticeRequired() : response.getPracticeRequired());
        response.setObjectives(safeList(response.getObjectives()));
        response.setResourceBasis(safeList(response.getResourceBasis()));
        response.setActivityFlow(safeList(response.getActivityFlow()));
        response.setPreparation(safeList(response.getPreparation()));
        response.setFieldTasks(safeList(response.getFieldTasks()));
        response.setSafetyNotes(safeList(response.getSafetyNotes()));
        response.setReflection(safeList(response.getReflection()));
        response.setEvaluation(safeList(response.getEvaluation()));
        response.setRelatedResources(response.getRelatedResources() == null || response.getRelatedResources().isEmpty()
                ? context.getResources().stream()
                .map(item -> item.getResource() == null ? null : item.getResource().getResourceName())
                .filter(StringUtils::hasText)
                .limit(5)
                .collect(Collectors.toList())
                : response.getRelatedResources());
        response.setFollowUpSuggestions(response.getFollowUpSuggestions() == null ? Collections.emptyList() : response.getFollowUpSuggestions());
        response.setCitations(response.getCitations() == null ? Collections.emptyList() : response.getCitations());
        return response;
    }

    private GeneratedTeachingPlanResponse buildFallbackResponse(TeachingPlanContextVO context) {
        TeachingPlanGenerateRequest request = context.getRequest();
        String schoolName = context.getSchool() == null ? "当前学校" : context.getSchool().getSchoolName();
        List<String> resourceNames = context.getResources().stream()
                .map(item -> item.getResource() == null ? null : item.getResource().getResourceName())
                .filter(StringUtils::hasText)
                .limit(5)
                .collect(Collectors.toList());

        GeneratedTeachingPlanResponse response = new GeneratedTeachingPlanResponse();
        response.setGenerationStatus("degraded");
        response.setMessage("LLM 服务不可用，已基于学校周边资源生成本地结构化方案。");
        response.setTheme(cleanOrDefault(request.getTheme(), "本土思政实践活动"));
        response.setGrade(cleanOrDefault(request.getGrade(), "小学高年级"));
        response.setActivityType(enumValue(request.getActivityType()));
        response.setDurationMinutes(request.getDurationMinutes());
        response.setPracticeRequired(Boolean.TRUE.equals(request.getPracticeRequired()));
        response.setObjectives(List.of(
                "引导学生理解身边真实资源的思政教育价值。",
                "通过观察、讨论和实践任务形成家乡认同与社会责任意识。"
        ));
        response.setResourceBasis(resourceNames.isEmpty()
                ? List.of(schoolName + "当前暂无足够资源，建议先补充周边资源数据。")
                : resourceNames.stream().map(name -> name + "：可作为本次教学活动的资源依据。").collect(Collectors.toList()));
        response.setActivityFlow(List.of(
                "课堂导入：介绍" + schoolName + "周边资源与本次主题的关系。",
                "资源研读：分组阅读资源简介、教育价值和活动建议。",
                "实践任务：围绕主题完成观察记录、访谈或志愿服务任务。",
                "交流展示：用照片、记录单或小组汇报呈现学习成果。"
        ));
        response.setPreparation(List.of("教师提前确认资源开放和安全条件。", "准备任务单、分组名单和引用材料。"));
        response.setFieldTasks(List.of("记录资源中的人物、故事、场景或服务对象。", "完成一条与主题相关的学习发现。"));
        response.setSafetyNotes(List.of("校外活动需统一行动并明确带队责任。", "涉及服务对象时注意礼仪、隐私和秩序。"));
        response.setReflection(List.of("学生完成一段活动感想。", "班级讨论本地资源与个人成长的关系。"));
        response.setEvaluation(List.of("依据参与度、任务单质量、合作表现和反思表达进行综合评价。"));
        response.setRelatedResources(resourceNames);
        response.setFollowUpSuggestions(List.of(
                schoolName + "还可以围绕哪些资源设计第二课时？",
                "如何把本次活动沉淀为校本课程案例？"
        ));
        return normalizeResponse(response, context, true);
    }

    private List<GeneratedTeachingPlanCitationVO> validateCitations(List<GeneratedTeachingPlanCitationVO> returned,
                                                                    List<GeneratedTeachingPlanCitationVO> candidates) {
        if (returned == null || returned.isEmpty() || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, GeneratedTeachingPlanCitationVO> candidateById = candidates.stream()
                .filter(candidate -> StringUtils.hasText(candidate.getCitationId()))
                .collect(Collectors.toMap(GeneratedTeachingPlanCitationVO::getCitationId, candidate -> candidate, (first, second) -> first, LinkedHashMap::new));
        List<GeneratedTeachingPlanCitationVO> valid = new ArrayList<>();
        for (GeneratedTeachingPlanCitationVO citation : returned) {
            GeneratedTeachingPlanCitationVO candidate = candidateById.get(citation.getCitationId());
            if (candidate != null) {
                valid.add(candidate);
            }
        }
        return valid.stream().limit(MAX_CITATIONS).collect(Collectors.toList());
    }

    private SchoolMapDetailVO requireApprovedSchool(Long schoolId) {
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(schoolId);
        if (detail == null || detail.getSchool() == null) {
            throw new IllegalArgumentException("school not found or not approved");
        }
        return detail;
    }

    private void validateGenerateRequest(TeachingPlanGenerateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (request.getSchoolId() == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        if (!StringUtils.hasText(request.getGrade())) {
            throw new IllegalArgumentException("grade is required");
        }
        if (!StringUtils.hasText(request.getTheme())) {
            throw new IllegalArgumentException("theme is required");
        }
        if (request.getDurationMinutes() != null && request.getDurationMinutes() <= 0) {
            throw new IllegalArgumentException("durationMinutes must be positive");
        }
    }

    private void validateSaveRequest(GeneratedTeachingPlanSaveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (request.getSchoolId() == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        if (!StringUtils.hasText(request.getTheme())) {
            throw new IllegalArgumentException("theme is required");
        }
    }

    private Map<EntityType, Set<Long>> collectContextEntityIds(TeachingPlanContextVO context) {
        Map<EntityType, Set<Long>> result = new HashMap<>();
        if (context.getSchool() != null && context.getSchool().getSchoolId() != null) {
            addEntityId(result, EntityType.SCHOOL, context.getSchool().getSchoolId());
        }
        for (TeachingPlanContextVO.ResourceContextVO resource : context.getResources()) {
            addEntityId(result, EntityType.RESOURCE, resource.getResourceId());
        }
        for (TeachingActivityPlanVO plan : context.getExistingPlans()) {
            addEntityId(result, EntityType.ACTIVITY_PLAN, plan.getPlanId());
        }
        return result;
    }

    private void addEntityId(Map<EntityType, Set<Long>> result, EntityType entityType, Long entityId) {
        if (entityId == null) {
            return;
        }
        result.computeIfAbsent(entityType, ignored -> new LinkedHashSet<>()).add(entityId);
    }

    private int relevanceScore(ContentChunk chunk, String theme) {
        if (!StringUtils.hasText(theme)) {
            return 0;
        }
        String text = (String.valueOf(chunk.getChunkTitle()) + " " + String.valueOf(chunk.getChunkText())).toLowerCase();
        return text.contains(theme.toLowerCase()) ? 1 : 0;
    }

    private String toGraphFact(Map<String, Object> row) {
        String name = stringValue(row.get("resourceName"));
        String theme = stringValue(row.get("theme"));
        String distance = stringValue(row.get("distanceMeters"));
        Object tagsValue = row.get("tags");
        List<String> tags = new ArrayList<>();
        if (tagsValue instanceof List<?>) {
            for (Object item : (List<?>) tagsValue) {
                String text = stringValue(item);
                if (StringUtils.hasText(text)) {
                    tags.add(text);
                }
            }
        }
        return String.join("；", List.of(
                StringUtils.hasText(name) ? "资源：" + name : "",
                StringUtils.hasText(theme) ? "关系：" + theme : "",
                StringUtils.hasText(distance) ? "距离：" + distance + "米" : "",
                tags.isEmpty() ? "" : "标签：" + String.join("、", tags)
        )).replaceAll("(^；+|；+$)", "");
    }

    private String generatePlanCode() {
        return "AI_PLAN_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    }

    private String joinLines(List<String> values, String fallback) {
        List<String> cleanValues = safeList(values);
        if (cleanValues.isEmpty()) {
            return fallback;
        }
        return String.join("\n", cleanValues);
    }

    private List<String> mergeLists(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        merged.addAll(safeList(first));
        merged.addAll(safeList(second));
        return merged;
    }

    private List<String> safeList(List<String> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        return values.stream().filter(StringUtils::hasText).map(String::trim).collect(Collectors.toList());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private String cleanOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
