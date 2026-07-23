package com.redculture.platform.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.service.AiTeachingPlanService;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.vo.GeneratedTeachingPlanCitationVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolResourceItemVO;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.ai.AgentActorVO;
import com.redculture.platform.vo.ai.KnowledgeChunkVO;
import com.redculture.platform.vo.ai.KnowledgeCitationCandidateVO;
import com.redculture.platform.vo.ai.KnowledgeGraphFactVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import com.redculture.platform.vo.ai.TeachingPlanContextVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingActivityPlanCreateRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AiTeachingPlanServiceImpl implements AiTeachingPlanService {

    private static final Logger log = LoggerFactory.getLogger(AiTeachingPlanServiceImpl.class);
    private static final int MAX_CONTENT_CHUNKS = 12;
    private static final int MAX_CITATIONS = 8;

    private final SchoolMapService schoolMapService;
    private final TeachingActivityPlanService teachingActivityPlanService;
    private final KnowledgeRetriever knowledgeRetriever;
    private final AppMapProperties appMapProperties;
    private final ObjectMapper objectMapper;
    private final ObjectWriter sseObjectWriter;

    public AiTeachingPlanServiceImpl(SchoolMapService schoolMapService,
                                     TeachingActivityPlanService teachingActivityPlanService,
                                     KnowledgeRetriever knowledgeRetriever,
                                     AppMapProperties appMapProperties,
                                     ObjectMapper objectMapper) {
        this.schoolMapService = schoolMapService;
        this.teachingActivityPlanService = teachingActivityPlanService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.appMapProperties = appMapProperties;
        this.objectMapper = objectMapper;
        this.sseObjectWriter = objectMapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII);
    }

    @Override
    public GeneratedTeachingPlanResponse generatePlan(TeachingPlanGenerateRequest request) {
        return generatePlan(request, null, null);
    }

    @Override
    public GeneratedTeachingPlanResponse generatePlan(TeachingPlanGenerateRequest request,
                                                        Long accountId,
                                                        String sessionId) {
        validateGenerateRequest(request);
        TeachingPlanContextVO context = buildContext(request, accountId, sessionId);
        GeneratedTeachingPlanResponse llmResponse = callLlmService(context);
        return finalizeResponse(llmResponse, context);
    }

    @Override
    public SseEmitter generatePlanStream(TeachingPlanGenerateRequest request) {
        return generatePlanStream(request, null, null);
    }

    @Override
    public SseEmitter generatePlanStream(TeachingPlanGenerateRequest request,
                                         Long accountId,
                                         String sessionId) {
        validateGenerateRequest(request);
        SseEmitter emitter = new SseEmitter(120_000L);
        Thread.ofVirtual().name("teaching-plan-stream")
                .start(() -> streamPlan(request, accountId, sessionId, emitter));
        return emitter;
    }

    private GeneratedTeachingPlanResponse finalizeResponse(GeneratedTeachingPlanResponse llmResponse,
                                                            TeachingPlanContextVO context) {
        GeneratedTeachingPlanResponse response = llmResponse == null
                ? buildFallbackResponse(context)
                : normalizeResponse(llmResponse, context, false);
        response.setRetrievalStatus(context.getRetrievalStatus());
        response.setRetrievalMethods(context.getContentChunks().stream()
                .map(TeachingPlanContextVO.ContentChunkContextVO::getRetrievalMethod)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
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

    private TeachingPlanContextVO buildContext(TeachingPlanGenerateRequest request,
                                                Long accountId,
                                                String sessionId) {
        SchoolMapDetailVO detail = requireApprovedSchool(request.getSchoolId());

        TeachingPlanContextVO context = new TeachingPlanContextVO();
        context.setRequest(request);
        AgentActorVO actor = new AgentActorVO();
        actor.setAccountId(accountId);
        actor.setSchoolId(request.getSchoolId());
        context.setActor(actor);
        context.setSessionId(sessionId);
        context.setSchool(detail.getSchool());
        context.setResources(buildResourceContexts(detail.getResources()));
        context.setExistingPlans(detail.getActivityPlans() == null ? Collections.emptyList() : detail.getActivityPlans());
        applyRetrievedKnowledge(context, retrieveKnowledge(request));
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

    private KnowledgeRetrieveResult retrieveKnowledge(TeachingPlanGenerateRequest request) {
        KnowledgeRetrieveRequest retrieveRequest = new KnowledgeRetrieveRequest();
        retrieveRequest.setQuery(Stream.of(request.getTheme(), request.getGrade(), enumValue(request.getActivityType()))
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" ")));
        retrieveRequest.setTheme(request.getTheme());
        retrieveRequest.setGrade(request.getGrade());
        retrieveRequest.setScopeType(KnowledgeScopeType.SCHOOL);
        retrieveRequest.setScopeId(request.getSchoolId());
        retrieveRequest.setTopK(MAX_CONTENT_CHUNKS);
        KnowledgeRetrieveResult result = knowledgeRetriever.retrieve(retrieveRequest);
        return result == null ? KnowledgeRetrieveResult.degraded() : result;
    }

    private void applyRetrievedKnowledge(TeachingPlanContextVO context, KnowledgeRetrieveResult retrieval) {
        context.setRetrievalStatus(retrieval.getRetrievalStatus() == null
                ? "empty" : retrieval.getRetrievalStatus().getValue());
        List<KnowledgeChunkVO> chunks = retrieval.getChunks() == null ? List.of() : retrieval.getChunks();
        context.setContentChunks(chunks.stream().limit(MAX_CONTENT_CHUNKS).map(chunk -> {
            TeachingPlanContextVO.ContentChunkContextVO item = new TeachingPlanContextVO.ContentChunkContextVO();
            item.setChunkId(chunk.getChunkId());
            item.setEntityType(chunk.getEntityType());
            item.setEntityId(chunk.getEntityId());
            item.setTitle(chunk.getTitle());
            item.setText(chunk.getText());
            item.setSourceId(chunk.getSourceId());
            item.setScore(chunk.getScore());
            item.setRetrievalMethod(chunk.getRetrievalMethod());
            return item;
        }).collect(Collectors.toList()));

        List<KnowledgeCitationCandidateVO> candidates = retrieval.getCitationCandidates() == null
                ? List.of() : retrieval.getCitationCandidates();
        context.setCitationCandidates(candidates.stream().limit(MAX_CITATIONS).map(candidate -> {
            GeneratedTeachingPlanCitationVO citation = new GeneratedTeachingPlanCitationVO();
            citation.setCitationId(candidate.getCitationId());
            citation.setTitle(candidate.getTitle());
            citation.setSourceType(candidate.getSourceType());
            citation.setRelatedEntityType(candidate.getRelatedEntityType());
            citation.setRelatedEntityId(candidate.getRelatedEntityId());
            citation.setExcerpt(candidate.getExcerpt());
            citation.setUrl(candidate.getUrl());
            return citation;
        }).collect(Collectors.toList()));

        List<KnowledgeGraphFactVO> facts = retrieval.getGraphFacts() == null ? List.of() : retrieval.getGraphFacts();
        context.setGraphFacts(facts.stream()
                .map(KnowledgeGraphFactVO::getText)
                .filter(StringUtils::hasText)
                .toList());
    }

    private void streamPlan(TeachingPlanGenerateRequest request,
                            Long accountId,
                            String sessionId,
                            SseEmitter emitter) {
        TeachingPlanContextVO context = null;
        boolean resultSent = false;
        try {
            sendEvent(emitter, "stage", Map.of("stage", "retrieval", "message", "正在检索教学依据"));
            context = buildContext(request, accountId, sessionId);
            sendEvent(emitter, "stage", Map.of("stage", "generation", "message", "正在生成教学方案"));

            if (!StringUtils.hasText(appMapProperties.getLlmServiceBaseUrl())) {
                sendFinalEvent(emitter, finalizeResponse(null, context));
                resultSent = true;
            } else {
                HttpResponse<java.io.InputStream> response = openTeachingPlanStream(context);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("LLM stream returned HTTP " + response.statusCode());
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String eventName = "message";
                    StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!data.isEmpty()) {
                                resultSent = forwardLlmEvent(emitter, eventName, data.toString(), context) || resultSent;
                            }
                            eventName = "message";
                            data.setLength(0);
                        } else if (line.startsWith("event:")) {
                            eventName = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (!data.isEmpty()) {
                                data.append('\n');
                            }
                            data.append(line.substring(5).trim());
                        }
                    }
                    if (!data.isEmpty()) {
                        resultSent = forwardLlmEvent(emitter, eventName, data.toString(), context) || resultSent;
                    }
                }
            }
            if (!resultSent) {
                sendFinalEvent(emitter, finalizeResponse(null, context));
            }
            sendEvent(emitter, "done", Map.of("status", "completed"));
            emitter.complete();
        } catch (Exception exception) {
            log.warn("Teaching-plan stream failed: {}", exception.getMessage(), exception);
            try {
                if (!resultSent && context != null) {
                    sendFinalEvent(emitter, finalizeResponse(null, context));
                    sendEvent(emitter, "done", Map.of("status", "degraded"));
                    emitter.complete();
                } else {
                    sendEvent(emitter, "error", Map.of("message", "教学方案流式生成失败"));
                    emitter.completeWithError(exception);
                }
            } catch (Exception sendException) {
                emitter.completeWithError(sendException);
            }
        }
    }

    private HttpResponse<java.io.InputStream> openTeachingPlanStream(TeachingPlanContextVO context) throws Exception {
        String baseUrl = appMapProperties.getLlmServiceBaseUrl().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/llm/teaching-plan/generate/stream"))
                .timeout(Duration.ofSeconds(110))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(context), StandardCharsets.UTF_8))
                .build();
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private boolean forwardLlmEvent(SseEmitter emitter,
                                    String eventName,
                                    String data,
                                    TeachingPlanContextVO context) throws Exception {
        JsonNode payload = objectMapper.readTree(data);
        if ("result".equals(eventName)) {
            GeneratedTeachingPlanResponse response = objectMapper.treeToValue(payload, GeneratedTeachingPlanResponse.class);
            sendFinalEvent(emitter, finalizeResponse(response, context));
            return true;
        }
        if ("token".equals(eventName) || "meta".equals(eventName)
                || "stage".equals(eventName) || "fallback".equals(eventName)) {
            sendEvent(emitter, eventName, payload);
        }
        return false;
    }

    private void sendFinalEvent(SseEmitter emitter, GeneratedTeachingPlanResponse response) throws Exception {
        sendEvent(emitter, "result", response);
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws Exception {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(sseObjectWriter.writeValueAsString(data)));
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

    private String cleanOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
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
