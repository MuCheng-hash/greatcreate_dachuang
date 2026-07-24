package com.redculture.platform.service.impl;

import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.AgentQaService;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TownMapService;
import com.redculture.platform.service.agent.AgentAnswerContext;
import com.redculture.platform.service.agent.AgentAccessGuard;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.service.agent.AgentRuntimeResult;
import com.redculture.platform.service.agent.AnswerGenerator;
import com.redculture.platform.service.agent.CitationValidator;
import com.redculture.platform.service.agent.GeneratedAnswer;
import com.redculture.platform.service.agent.IntentRecognizer;
import com.redculture.platform.vo.AgentGenerationStatus;
import com.redculture.platform.vo.AgentCitationVO;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolResourceItemVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import com.redculture.platform.vo.ai.AgentActorVO;
import com.redculture.platform.vo.ai.AgentRuntimeRequest;
import com.redculture.platform.vo.ai.AgentRuntimeResponse;
import com.redculture.platform.vo.ai.AgentScopeVO;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentQaServiceImpl implements AgentQaService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 8;
    private static final Pattern GRADE_PATTERN = Pattern.compile("(低年级|中年级|高年级|[一二三四五六七八九十0-9]+年级)");

    private final SchoolMapService schoolMapService;
    private final TownMapService townMapService;
    private final LocalEduResourceService localEduResourceService;
    private final KnowledgeRetriever knowledgeRetriever;
    private final IntentRecognizer intentRecognizer;
    private final AnswerGenerator answerGenerator;
    private final CitationValidator citationValidator;
    private final AgentAccessGuard accessGuard;
    private final AgentRuntimeClient agentRuntimeClient;
    private final AgentProperties agentProperties;

    @Autowired
    public AgentQaServiceImpl(SchoolMapService schoolMapService,
                              TownMapService townMapService,
                              LocalEduResourceService localEduResourceService,
                              KnowledgeRetriever knowledgeRetriever,
                              IntentRecognizer intentRecognizer,
                              AnswerGenerator answerGenerator,
                              CitationValidator citationValidator,
                              AgentAccessGuard accessGuard,
                              AgentRuntimeClient agentRuntimeClient,
                              AgentProperties agentProperties) {
        this.schoolMapService = schoolMapService;
        this.townMapService = townMapService;
        this.localEduResourceService = localEduResourceService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.intentRecognizer = intentRecognizer;
        this.answerGenerator = answerGenerator;
        this.citationValidator = citationValidator;
        this.accessGuard = accessGuard;
        this.agentRuntimeClient = agentRuntimeClient;
        this.agentProperties = agentProperties;
    }

    public AgentQaServiceImpl(SchoolMapService schoolMapService,
                              TownMapService townMapService,
                              LocalEduResourceService localEduResourceService,
                              KnowledgeRetriever knowledgeRetriever,
                              IntentRecognizer intentRecognizer,
                              AnswerGenerator answerGenerator,
                              CitationValidator citationValidator,
                              AgentAccessGuard accessGuard,
                              AgentRuntimeClient agentRuntimeClient) {
        this(schoolMapService, townMapService, localEduResourceService, knowledgeRetriever,
                intentRecognizer, answerGenerator, citationValidator, accessGuard,
                agentRuntimeClient, new AgentProperties());
    }

    public AgentQaServiceImpl(SchoolMapService schoolMapService,
                              TownMapService townMapService,
                              LocalEduResourceService localEduResourceService,
                              KnowledgeRetriever knowledgeRetriever,
                              IntentRecognizer intentRecognizer,
                              AnswerGenerator answerGenerator,
                              CitationValidator citationValidator) {
        this(schoolMapService, townMapService, localEduResourceService, knowledgeRetriever,
                intentRecognizer, answerGenerator, citationValidator,
                new AgentAccessGuard(schoolMapService), null, new AgentProperties());
    }

    /** Compatibility constructor for the stateful runtime path. */
    public AgentQaServiceImpl(SchoolMapService schoolMapService,
                              TownMapService townMapService,
                              LocalEduResourceService localEduResourceService,
                              KnowledgeRetriever knowledgeRetriever,
                              IntentRecognizer intentRecognizer,
                               AnswerGenerator answerGenerator,
                               CitationValidator citationValidator,
                               AgentRuntimeClient agentRuntimeClient) {
        this(schoolMapService, townMapService, localEduResourceService, knowledgeRetriever,
                intentRecognizer, answerGenerator, citationValidator,
                new AgentAccessGuard(schoolMapService), agentRuntimeClient, new AgentProperties());
    }

    @Override
    public AgentQaResponse ask(AgentQaRequest request, AuthCurrentUserVO currentUser) {
        validateRequest(request);
        if (currentUser == null) {
            throw new IllegalArgumentException("school account is required");
        }

        if (agentRuntimeClient != null && agentProperties.isLegacyRuntime()) {
            try {
                return askWithAgentRuntime(request, currentUser);
            } catch (IllegalArgumentException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                // FastAPI 不可用时保留已有 Java 业务链路，保证兼容接口仍能回答。
            }
        }

        return askWithLegacyPipeline(request, currentUser, !agentProperties.isLegacyRuntime());
    }

    @Override
    public SseEmitter stream(AgentQaRequest request, AuthCurrentUserVO currentUser) {
        validateRequest(request);
        if (currentUser == null) {
            throw new IllegalArgumentException("school account is required");
        }
        SseEmitter emitter = new SseEmitter(Math.max(1000L, agentProperties.getStreamTimeoutMs()));
        AgentAccessGuard.ScopeResolution scopeResolution = accessGuard.resolveScope(
                request.getScopeType(), request.getScopeId(), currentUser, request.getQuestion().trim()
        );
        if (scopeResolution.clarificationRequired()) {
            sendEvent(emitter, "final", Map.of("response", clarificationResponse(
                    AgentIntent.UNKNOWN,
                    scopeResolution.message(),
                    scopeResolution.options()
            )));
            sendEvent(emitter, "done", Collections.emptyMap());
            emitter.complete();
            return emitter;
        }

        if (agentRuntimeClient == null || agentProperties.isLegacyRuntime()) {
            startLegacyStream(emitter, request, currentUser, false);
            return emitter;
        }

        String question = request.getQuestion().trim();
        AgentIntent intent = intentRecognizer.recognize(question);
        if (intent == AgentIntent.UNKNOWN) {
            startLegacyStream(emitter, request, currentUser, false);
            return emitter;
        }
        Scope scope = new Scope(scopeResolution.type(), scopeResolution.id());
        AgentAnswerContext context = buildAgentContext(request, currentUser, question, intent, scope);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        Runnable cancelWorker = () -> {
            if (finished.get()) {
                return;
            }
            Thread worker = workerRef.get();
            if (worker != null && worker != Thread.currentThread()) {
                worker.interrupt();
            }
        };
        emitter.onCompletion(cancelWorker);
        emitter.onTimeout(cancelWorker);
        emitter.onError(ignored -> cancelWorker.run());

        Thread thread = new Thread(() -> {
            boolean[] upstreamDone = {false};
            try {
                agentRuntimeClient.streamStateful(request, currentUser, context, event -> {
                    if ("done".equals(event.event())) {
                        upstreamDone[0] = true;
                    }
                    Map<String, Object> data = event.safeData();
                    if ("final".equals(event.event())) {
                        data = normalizeStatefulFinalEvent(data, request, context);
                    }
                    sendEvent(emitter, event.event(), data);
                });
                if (!upstreamDone[0]) {
                    sendEvent(emitter, "done", Collections.emptyMap());
                }
                finished.set(true);
                emitter.complete();
            } catch (RuntimeException exception) {
                if (isClientDisconnected(exception)) {
                    return;
                }
                // Stateful FastAPI 不可用时退回本地 Java 生成链路，仍然保持流式协议。
                startLegacyStream(emitter, request, currentUser, false);
                return;
            } finally {
                finished.set(true);
            }
        }, "agent-runtime-sse");
        workerRef.set(thread);
        thread.setDaemon(true);
        thread.start();
        return emitter;
    }

    private AgentQaResponse askWithAgentRuntime(AgentQaRequest request, AuthCurrentUserVO currentUser) {
        AgentAccessGuard.ScopeResolution scopeResolution = accessGuard.resolveScope(
                request.getScopeType(), request.getScopeId(), currentUser, request.getQuestion().trim()
        );
        if (scopeResolution.clarificationRequired()) {
            return clarificationResponse(AgentIntent.UNKNOWN, scopeResolution.message(), scopeResolution.options());
        }
        Scope scope = new Scope(scopeResolution.type(), scopeResolution.id());
        AgentRuntimeResponse runtimeResponse = agentRuntimeClient.run(
                toRuntimeRequest(request, currentUser, scope)
        );
        return mapRuntimeResponse(runtimeResponse, scope);
    }

    private AgentRuntimeRequest toRuntimeRequest(AgentQaRequest request,
                                                 AuthCurrentUserVO currentUser,
                                                 Scope scope) {
        AgentActorVO actor = new AgentActorVO();
        actor.setAccountId(currentUser.getAccountId());
        actor.setRoleCode(currentUser.getRoleCode());
        actor.setSchoolId(currentUser.getSchoolId());

        AgentScopeVO agentScope = new AgentScopeVO();
        agentScope.setScopeType(scope.type().name());
        agentScope.setScopeId(scope.id());
        if (scope.type() == KnowledgeScopeType.SCHOOL) {
            agentScope.setName(currentUser.getSchoolName());
        }

        AgentRuntimeRequest runtimeRequest = new AgentRuntimeRequest();
        runtimeRequest.setQuestion(request.getQuestion().trim());
        runtimeRequest.setConversationId(request.getConversationId());
        runtimeRequest.setActor(actor);
        runtimeRequest.setScope(agentScope);
        runtimeRequest.setGrade(resolveGrade(request.getGrade(), request.getQuestion()));
        runtimeRequest.setTheme(clean(request.getTheme()));
        runtimeRequest.setTopK(normalizeTopK(request.getTopK()));
        return runtimeRequest;
    }

    private AgentQaResponse mapRuntimeResponse(AgentRuntimeResponse runtimeResponse, Scope fallbackScope) {
        if (runtimeResponse == null || !StringUtils.hasText(runtimeResponse.getAnswer())) {
            throw new IllegalStateException("agent runtime returned an empty response");
        }
        AgentQaResponse response = new AgentQaResponse();
        response.setAnswer(runtimeResponse.getAnswer());
        response.setConversationId(runtimeResponse.getConversationId());
        response.setRunId(runtimeResponse.getRunId());
        response.setFallbackLevel(runtimeResponse.getFallbackLevel());
        response.setIntent(parseIntent(runtimeResponse.getIntent()));
        response.setGenerationStatus(parseGenerationStatus(runtimeResponse.getGenerationStatus()));
        response.setRetrievalStatus(parseRetrievalStatus(runtimeResponse.getRetrievalStatus()));
        response.setScopeType(parseScopeType(runtimeResponse.getScopeType(), fallbackScope.type()));
        response.setScopeId(runtimeResponse.getScopeId() == null
                ? fallbackScope.id() : runtimeResponse.getScopeId());
        response.setRelatedResources(nonNullList(runtimeResponse.getRelatedResources()));
        response.setCitations(runtimeResponse.getCitations() == null
                ? new ArrayList<>() : runtimeResponse.getCitations());
        response.setFollowUpQuestions(nonNullList(runtimeResponse.getFollowUpQuestions()));
        response.setClarificationRequired(runtimeResponse.isClarificationRequired());
        response.setClarificationMessage(runtimeResponse.getClarificationMessage());
        response.setClarificationOptions(nonNullList(runtimeResponse.getClarificationOptions()));
        return response;
    }

    private AgentIntent parseIntent(String value) {
        if (!StringUtils.hasText(value)) {
            return AgentIntent.UNKNOWN;
        }
        try {
            return AgentIntent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return AgentIntent.UNKNOWN;
        }
    }

    private AgentGenerationStatus parseGenerationStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return AgentGenerationStatus.DEGRADED;
        }
        try {
            return AgentGenerationStatus.from(value);
        } catch (IllegalArgumentException exception) {
            return AgentGenerationStatus.DEGRADED;
        }
    }

    private KnowledgeRetrievalStatus parseRetrievalStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return KnowledgeRetrievalStatus.EMPTY;
        }
        for (KnowledgeRetrievalStatus status : KnowledgeRetrievalStatus.values()) {
            if (status.getValue().equalsIgnoreCase(value.trim())
                    || status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return KnowledgeRetrievalStatus.DEGRADED;
    }

    private KnowledgeScopeType parseScopeType(String value, KnowledgeScopeType fallback) {
        try {
            KnowledgeScopeType parsed = KnowledgeScopeType.from(value);
            return parsed == null ? fallback : parsed;
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private void startLegacyStream(SseEmitter emitter,
                                   AgentQaRequest request,
                                   AuthCurrentUserVO currentUser,
                                   boolean useStatefulRuntime) {
        Thread thread = new Thread(() -> {
            String runId = java.util.UUID.randomUUID().toString();
            try {
                sendEvent(emitter, "run.started", Map.of("runId", runId));
                AgentQaResponse response = askWithLegacyPipeline(request, currentUser, useStatefulRuntime);
                response.setRunId(runId);
                if (!StringUtils.hasText(response.getConversationId())) {
                    response.setConversationId(StringUtils.hasText(request.getConversationId())
                            ? request.getConversationId() : java.util.UUID.randomUUID().toString());
                }
                String answer = response.getAnswer() == null ? "" : response.getAnswer();
                for (int index = 0; index < answer.length(); index += 8) {
                    sendEvent(emitter, "token", Map.of(
                            "runId", runId,
                            "delta", answer.substring(index, Math.min(answer.length(), index + 8))
                    ));
                }
                sendEvent(emitter, "final", Map.of("runId", runId, "response", response));
                sendEvent(emitter, "done", Map.of("runId", runId));
                emitter.complete();
            } catch (RuntimeException exception) {
                sendEvent(emitter, "error", Map.of(
                        "runId", runId,
                        "errorType", "legacy_stream_error",
                        "message", exception.getMessage() == null ? "agent stream failed" : exception.getMessage()
                ));
                sendEvent(emitter, "done", Map.of("runId", runId));
                emitter.completeWithError(exception);
            }
        }, "legacy-agent-sse");
        thread.setDaemon(true);
        thread.start();
    }

    private Map<String, Object> normalizeStatefulFinalEvent(Map<String, Object> eventData,
                                                             AgentQaRequest request,
                                                             AgentAnswerContext context) {
        Map<String, Object> normalized = new LinkedHashMap<>(eventData);
        Object rawResponse = eventData.get("response");
        Map<?, ?> responseMap = rawResponse instanceof Map<?, ?> map ? map : Collections.emptyMap();
        AgentQaResponse response = new AgentQaResponse();
        String answer = textValue(responseMap.get("answer"));
        response.setAnswer(StringUtils.hasText(answer) ? answer : "暂时无法生成有效回答。");
        response.setThreadId(firstText(responseMap.get("threadId"), eventData.get("threadId")));
        response.setStatus(firstText(responseMap.get("status"), "degraded"));
        response.setRunId(textValue(eventData.get("runId")));
        response.setConversationId(request.getConversationId());
        response.setIntent(context.getIntent());
        response.setScopeType(context.getScopeType());
        response.setScopeId(context.getScopeId());

        KnowledgeRetrieveResult retrieval = context.getRetrieval() == null
                ? KnowledgeRetrieveResult.empty() : context.getRetrieval();
        response.setRetrievalStatus(retrieval.getRetrievalStatus());
        AgentGenerationStatus generationStatus = "completed".equalsIgnoreCase(response.getStatus())
                ? AgentGenerationStatus.COMPLETED : AgentGenerationStatus.DEGRADED;
        response.setGenerationStatus(generationStatus);

        List<String> citationIds = citationIds(responseMap.get("citations"));
        List<String> followUps = textList(responseMap.get("followUpQuestions"));
        GeneratedAnswer generated = new GeneratedAnswer(
                response.getAnswer(), citationIds, followUps, generationStatus
        );
        response.setCitations(validatedCitations(generated, retrieval));
        response.setRelatedResources(textList(responseMap.get("relatedResources")));
        response.setFollowUpQuestions(followUps);
        response.setToolExecutions(toolNames(responseMap.get("toolExecutions")));
        normalized.put("threadId", response.getThreadId());
        normalized.put("response", response);
        return normalized;
    }

    private List<String> citationIds(Object value) {
        if (!(value instanceof List<?> values)) {
            return new ArrayList<>();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) {
                String id = textValue(map.get("citationId"));
                if (StringUtils.hasText(id)) {
                    ids.add(id);
                }
            } else if (item instanceof String id && StringUtils.hasText(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<String> toolNames(Object value) {
        if (!(value instanceof List<?> values)) {
            return new ArrayList<>();
        }
        List<String> names = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) {
                String name = firstText(map.get("name"), map.get("toolName"));
                if (StringUtils.hasText(name)) {
                    names.add(name);
                }
            } else if (item instanceof String name && StringUtils.hasText(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private List<String> textList(Object value) {
        if (!(value instanceof List<?> values)) {
            return new ArrayList<>();
        }
        return values.stream()
                .map(this::textValue)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String firstText(Object first, Object fallback) {
        String value = textValue(first);
        return StringUtils.hasText(value) ? value : textValue(fallback);
    }

    private String textValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private AgentAnswerContext buildAgentContext(AgentQaRequest request,
                                                 AuthCurrentUserVO currentUser,
                                                 String question,
                                                 AgentIntent intent,
                                                 Scope scope) {
        AgentAnswerContext context = new AgentAnswerContext();
        context.setQuestion(question);
        context.setIntent(intent);
        context.setScopeType(scope.type());
        context.setScopeId(scope.id());
        context.setGrade(resolveGrade(request.getGrade(), question));
        context.setTheme(clean(request.getTheme()));
        loadBusinessContext(context);
        context.setRetrieval(retrieve(context, request.getTopK()));
        return context;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException exception) {
            throw new IllegalStateException("client disconnected", exception);
        }
    }

    private boolean isClientDisconnected(RuntimeException exception) {
        String message = exception.getMessage();
        return Thread.currentThread().isInterrupted()
                || (message != null && message.contains("client disconnected"));
    }

    private AgentQaResponse askWithLegacyPipeline(AgentQaRequest request,
                                                  AuthCurrentUserVO currentUser,
                                                  boolean useStatefulRuntime) {

        String question = request.getQuestion().trim();
        AgentIntent intent = intentRecognizer.recognize(question);

        if (intent == AgentIntent.UNKNOWN) {
            return skippedResponse(intent,
                    "我目前支持查询周边资源、解释教育资源、设计教学活动，以及查询人物、学校和资源之间的关系。请补充学校、资源或区域名称。", null);
        }

        ScopeResolution scopeResolution = resolveScope(request, currentUser, question);
        if (scopeResolution.requiresClarification()) {
            return clarificationResponse(intent, scopeResolution.message(), scopeResolution.options());
        }
        Scope scope = scopeResolution.scope();

        AgentAnswerContext context = buildAgentContext(request, currentUser, question, intent, scope);
        KnowledgeRetrieveResult retrieval = context.getRetrieval();

        AgentRuntimeResult remote = !useStatefulRuntime || agentRuntimeClient == null
                ? null : agentRuntimeClient.generate(request, currentUser, context);
        GeneratedAnswer generated = remote == null ? null : remote.getAnswer();
        if (generated == null) {
            try {
                generated = answerGenerator.generate(context);
            } catch (RuntimeException exception) {
                generated = new GeneratedAnswer(
                        "暂时无法生成完整回答，请稍后重试。",
                        List.of(),
                        List.of(),
                        AgentGenerationStatus.DEGRADED
                );
            }
        }
        if (generated == null) {
            generated = new GeneratedAnswer(
                    "暂时无法生成回答，请稍后重试。",
                    List.of(),
                    List.of(),
                    AgentGenerationStatus.DEGRADED
            );
        }

        AgentQaResponse response = new AgentQaResponse();
        response.setAnswer(StringUtils.hasText(generated.getAnswer()) ? generated.getAnswer() : "暂时无法生成回答。");
        response.setIntent(intent);
        response.setRetrievalStatus(retrieval.getRetrievalStatus());
        response.setGenerationStatus(generated.getGenerationStatus() == null
                ? AgentGenerationStatus.COMPLETED
                : generated.getGenerationStatus());
        response.setScopeType(scope.type());
        response.setScopeId(scope.id());
        response.setRelatedResources(relatedResources(context));
        response.setCitations(validatedCitations(generated, retrieval));
        response.setFollowUpQuestions(nonNullList(generated.getFollowUpQuestions()));
        response.setThreadId(remote == null ? request.getThreadId() : remote.getThreadId());
        response.setConversationId(request.getConversationId());
        response.setStatus(remote == null ? "degraded" : remote.getStatus());
        response.setToolExecutions(remote == null ? new ArrayList<>() : nonNullList(remote.getToolExecutions()));
        return response;
    }

    private void validateRequest(AgentQaRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuestion())) {
            throw new IllegalArgumentException("question is required");
        }
        if (request.getScopeId() != null && request.getScopeId() <= 0) {
            throw new IllegalArgumentException("scopeId must be positive");
        }
    }

    private ScopeResolution resolveScope(AgentQaRequest request,
                                         AuthCurrentUserVO currentUser,
                                         String question) {
        KnowledgeScopeType requestedType = KnowledgeScopeType.from(request.getScopeType());
        Long requestedId = request.getScopeId();
        boolean admin = "platform_admin".equals(currentUser.getRoleCode());

        if (!admin) {
            if (currentUser.getSchoolId() == null) {
                throw new IllegalArgumentException("school account is required");
            }
            if (requestedType != null && requestedType != KnowledgeScopeType.SCHOOL) {
                throw new IllegalArgumentException("school account can only query its own school");
            }
            if (requestedId != null && !requestedId.equals(currentUser.getSchoolId())) {
                throw new IllegalArgumentException("cannot access another school");
            }
            List<SchoolSummaryVO> mentionedSchools = findMentionedSchools(question);
            if (mentionedSchools.stream().anyMatch(school -> !currentUser.getSchoolId().equals(school.getSchoolId()))) {
                throw new IllegalArgumentException("cannot access another school");
            }
            if (mentionedSchools.size() > 1) {
                return ScopeResolution.clarification("问题中匹配到多个学校，请补充完整学校名称。", schoolNames(mentionedSchools));
            }
            return ScopeResolution.resolved(new Scope(KnowledgeScopeType.SCHOOL, currentUser.getSchoolId()));
        }

        if (requestedId != null && requestedType == null) {
            requestedType = KnowledgeScopeType.SCHOOL;
        }
        if (requestedId != null) {
            return ScopeResolution.resolved(new Scope(requestedType, requestedId));
        }
        if (requestedType != null) {
            return ScopeResolution.clarification("请补充当前范围的 scopeId。", Collections.emptyList());
        }

        List<SchoolSummaryVO> mentionedSchools = findMentionedSchools(question);
        if (mentionedSchools.size() == 1) {
            return ScopeResolution.resolved(new Scope(KnowledgeScopeType.SCHOOL, mentionedSchools.get(0).getSchoolId()));
        }
        if (mentionedSchools.size() > 1) {
            return ScopeResolution.clarification("问题中匹配到多个学校，请补充完整学校名称。", schoolNames(mentionedSchools));
        }
        return ScopeResolution.clarification("请补充具体学校名称，或传入学校 scopeId。", Collections.emptyList());
    }

    private List<SchoolSummaryVO> findMentionedSchools(String question) {
        if (!StringUtils.hasText(question)) {
            return Collections.emptyList();
        }
        List<SchoolSummaryVO> schools = schoolMapService.listSchools(null, null, null, 100);
        if (schools == null || schools.isEmpty()) {
            return Collections.emptyList();
        }
        String normalizedQuestion = normalizeForMatch(question);
        List<SchoolSummaryVO> matches = new ArrayList<>();
        for (SchoolSummaryVO school : schools) {
            if (school == null || school.getSchoolId() == null || !StringUtils.hasText(school.getSchoolName())) {
                continue;
            }
            if (normalizedQuestion.contains(normalizeForMatch(school.getSchoolName()))
                    && matches.stream().noneMatch(item -> school.getSchoolId().equals(item.getSchoolId()))) {
                matches.add(school);
            }
        }
        return matches;
    }

    private List<String> schoolNames(List<SchoolSummaryVO> schools) {
        return schools.stream()
                .map(SchoolSummaryVO::getSchoolName)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String normalizeForMatch(String value) {
        return value == null ? "" : value
                .replaceAll("\\s+", "")
                .replaceAll("[，。！？、；】【：‘’“”（）《》【】,.!?;:\"'()<>\\[\\]{}]", "")
                .toLowerCase(Locale.ROOT);
    }

    private String resolveGrade(String requestedGrade, String question) {
        String explicitGrade = clean(requestedGrade);
        if (explicitGrade != null) {
            return explicitGrade;
        }
        Matcher matcher = GRADE_PATTERN.matcher(question == null ? "" : question);
        return matcher.find() ? matcher.group(1) : null;
    }

    private AgentQaResponse clarificationResponse(AgentIntent intent,
                                                  String message,
                                                  List<String> options) {
        AgentQaResponse response = new AgentQaResponse();
        response.setAnswer(message);
        response.setIntent(intent);
        response.setRetrievalStatus(KnowledgeRetrievalStatus.EMPTY);
        response.setGenerationStatus(AgentGenerationStatus.SKIPPED);
        response.setClarificationRequired(true);
        response.setClarificationMessage(message);
        response.setClarificationOptions(options == null ? new ArrayList<>() : options);
        return response;
    }

    private AgentQaResponse skippedResponse(AgentIntent intent, String message, Scope scope) {
        AgentQaResponse response = new AgentQaResponse();
        response.setAnswer(message);
        response.setIntent(intent);
        response.setRetrievalStatus(KnowledgeRetrievalStatus.EMPTY);
        response.setGenerationStatus(AgentGenerationStatus.SKIPPED);
        if (scope != null) {
            response.setScopeType(scope.type());
            response.setScopeId(scope.id());
        }
        return response;
    }

    private void loadBusinessContext(AgentAnswerContext context) {
        switch (context.getScopeType()) {
            case SCHOOL -> {
                SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(context.getScopeId());
                if (detail == null) {
                    throw new IllegalArgumentException("school not found or unavailable");
                }
                context.setSchoolDetail(detail);
                context.setMatchedSchoolResource(findMentionedResource(detail, context.getQuestion()));
            }
            case REGION -> {
                if (townMapService != null) {
                    context.setRegionDetail(townMapService.getTownMapDetail(context.getScopeId()));
                }
                if (context.getRegionDetail() == null) {
                    throw new IllegalArgumentException("region not found or unavailable");
                }
            }
            case RESOURCE -> {
                LocalEduResource resource = localEduResourceService.getById(context.getScopeId());
                if (resource == null || !Boolean.TRUE.equals(resource.getActive())
                        || resource.getReviewStatus() != ReviewStatus.APPROVED) {
                    throw new IllegalArgumentException("resource not found or unavailable");
                }
                context.setResource(resource);
            }
        }
    }

    private KnowledgeRetrieveResult retrieve(AgentAnswerContext context, Integer requestedTopK) {
        if (context.getIntent() == AgentIntent.UNKNOWN) {
            return KnowledgeRetrieveResult.empty();
        }

        KnowledgeRetrieveRequest request = new KnowledgeRetrieveRequest();
        request.setQuery(context.getQuestion());
        request.setScopeType(context.getScopeType());
        request.setScopeId(context.getScopeId());
        request.setGrade(context.getGrade());
        request.setTheme(context.getTheme());
        request.setTopK(normalizeTopK(requestedTopK));

        try {
            return normalizeResult(knowledgeRetriever.retrieve(request));
        } catch (RuntimeException exception) {
            return KnowledgeRetrieveResult.degraded();
        }
    }

    private KnowledgeRetrieveResult normalizeResult(KnowledgeRetrieveResult result) {
        if (result == null) {
            return KnowledgeRetrieveResult.degraded();
        }
        if (result.getChunks() == null) {
            result.setChunks(new ArrayList<>());
        }
        if (result.getGraphFacts() == null) {
            result.setGraphFacts(new ArrayList<>());
        }
        if (result.getCitationCandidates() == null) {
            result.setCitationCandidates(new ArrayList<>());
        }
        if (result.getRetrievalStatus() == null) {
            boolean hasEvidence = !result.getChunks().isEmpty()
                    || !result.getGraphFacts().isEmpty()
                    || !result.getCitationCandidates().isEmpty();
            result.setRetrievalStatus(hasEvidence ? KnowledgeRetrievalStatus.OK : KnowledgeRetrievalStatus.EMPTY);
        }
        return result;
    }

    private List<String> relatedResources(AgentAnswerContext context) {
        List<String> names = new ArrayList<>();
        if (context.getResource() != null && StringUtils.hasText(context.getResource().getResourceName())) {
            names.add(context.getResource().getResourceName());
        }
        SchoolMapDetailVO detail = context.getSchoolDetail();
        if (detail != null && detail.getResources() != null) {
            detail.getResources().stream()
                    .map(SchoolResourceItemVO::getResource)
                    .filter(resource -> resource != null && StringUtils.hasText(resource.getResourceName()))
                    .map(LocalEduResourceSummaryVO::getResourceName)
                    .filter(name -> !names.contains(name))
                    .limit(8)
                    .forEach(names::add);
        }
        if (context.getRegionDetail() != null && context.getRegionDetail().getMarkers() != null) {
            context.getRegionDetail().getMarkers().stream()
                    .filter(marker -> marker != null && StringUtils.hasText(marker.getName()))
                    .map(marker -> marker.getName())
                    .filter(name -> !names.contains(name))
                    .limit(8)
                    .forEach(names::add);
        }
        return names;
    }

    private List<AgentCitationVO> validatedCitations(GeneratedAnswer generated,
                                                     KnowledgeRetrieveResult retrieval) {
        List<AgentCitationVO> citations = citationValidator.filter(
                generated == null ? Collections.emptyList() : generated.getCitationIds(),
                retrieval
        );
        if (!citations.isEmpty() || retrieval == null) {
            return citations;
        }

        List<String> fallbackIds = retrieval.allCitationIds().stream()
                .limit(5)
                .toList();
        return citationValidator.filter(fallbackIds, retrieval);
    }

    private LocalEduResourceSummaryVO findMentionedResource(SchoolMapDetailVO detail, String question) {
        if (detail == null || detail.getResources() == null || question == null) {
            return null;
        }
        return detail.getResources().stream()
                .map(SchoolResourceItemVO::getResource)
                .filter(resource -> resource != null && StringUtils.hasText(resource.getResourceName()))
                .filter(resource -> question.contains(resource.getResourceName()))
                .findFirst()
                .orElse(null);
    }

    private Integer normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private List<String> nonNullList(List<String> values) {
        return values == null ? new ArrayList<>() : values;
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record Scope(KnowledgeScopeType type, Long id) {
    }

    private record ScopeResolution(Scope scope, String message, List<String> options) {

        private static ScopeResolution resolved(Scope scope) {
            return new ScopeResolution(scope, null, Collections.emptyList());
        }

        private static ScopeResolution clarification(String message, List<String> options) {
            return new ScopeResolution(null, message, options);
        }

        private boolean requiresClarification() {
            return scope == null;
        }
    }
}
