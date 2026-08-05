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
import com.redculture.platform.vo.ai.AgentMemoryApplied;
import com.redculture.platform.vo.ai.AgentMemoryItem;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import com.redculture.platform.vo.request.AgentQaRequest;
import com.redculture.platform.vo.request.AgentAttachmentRequest;
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
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
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

        return askWithAgentPipeline(request, currentUser);
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

        if (agentRuntimeClient == null) {
            startLocalFallbackStream(emitter, request, currentUser);
            return emitter;
        }

        String question = request.getQuestion().trim();
        AgentIntent intent = hasImageAttachments(request)
                ? AgentIntent.RESOURCE_EXPLANATION : intentRecognizer.recognize(question);
        Scope scope = new Scope(scopeResolution.type(), scopeResolution.id());
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
            boolean[] upstreamFinal = {false};
            try {
                sendEvent(emitter, "phase.started", Map.of(
                        "phase", "retrieval",
                        "label", "正在检索可信知识与业务数据"
                ));
                AgentAnswerContext context = buildAgentContext(request, currentUser, question, intent, scope);
                sendEvent(emitter, "phase.completed", retrievalCompletedEvent(request, context, null));
                agentRuntimeClient.streamStateful(request, currentUser, context, event -> {
                    if ("done".equals(event.event())) {
                        upstreamDone[0] = true;
                    }
                    Map<String, Object> data = event.safeData();
                    if ("final".equals(event.event())) {
                        upstreamFinal[0] = true;
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
                if (upstreamFinal[0]) {
                    sendEvent(emitter, "error", Map.of(
                            "errorType", "agent_stream_incomplete",
                            "message", "Agent 已返回回答，但流式连接未正常结束"
                    ));
                    sendEvent(emitter, "done", Collections.emptyMap());
                    emitter.complete();
                    return;
                }
                // Stateful FastAPI 不可用时退回本地 Java 生成链路，仍然保持流式协议。
                sendEvent(emitter, "model.failed", Map.of(
                        "errorType", "stateful_agent_unavailable"
                ));
                sendEvent(emitter, "model.fallback", Map.of(
                        "reset", true,
                        "reason", "stateful_agent_unavailable"
                ));
                startLocalFallbackStream(emitter, request, currentUser);
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

    private void startLocalFallbackStream(SseEmitter emitter,
                                   AgentQaRequest request,
                                   AuthCurrentUserVO currentUser) {
        Thread thread = new Thread(() -> {
            String runId = java.util.UUID.randomUUID().toString();
            try {
                sendEvent(emitter, "run.started", Map.of("runId", runId));
                sendEvent(emitter, "phase.started", Map.of(
                        "runId", runId,
                        "phase", "retrieval",
                        "label", "正在检索可信知识与业务数据"
                ));
                AtomicReference<AgentAnswerContext> contextRef = new AtomicReference<>();
                AgentQaResponse response = askWithPipeline(request, currentUser, false, contextRef::set);
                sendEvent(emitter, "phase.completed", retrievalCompletedEvent(request, contextRef.get(), runId));
                sendEvent(emitter, "phase.started", Map.of(
                        "runId", runId,
                        "phase", "response",
                        "label", "正在生成回答"
                ));
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
                    LockSupport.parkNanos(1_000_000L);
                }
                sendEvent(emitter, "phase.completed", Map.of(
                        "runId", runId,
                        "phase", "response",
                        "label", "回答生成完成"
                ));
                sendEvent(emitter, "final", Map.of("runId", runId, "response", response));
                sendEvent(emitter, "done", Map.of("runId", runId));
                emitter.complete();
            } catch (RuntimeException exception) {
                sendEvent(emitter, "error", Map.of(
                        "runId", runId,
                        "errorType", "local_fallback_stream_error",
                        "message", exception.getMessage() == null ? "agent stream failed" : exception.getMessage()
                ));
                sendEvent(emitter, "done", Map.of("runId", runId));
                emitter.completeWithError(exception);
            }
        }, "local-agent-sse");
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
        response.setDegradedReason(textValue(responseMap.get("degradedReason")));
        response.setRunId(textValue(eventData.get("runId")));
        response.setConversationId(request.getConversationId());
        response.setIntent(context.getIntent());
        response.setScopeType(context.getScopeType());
        response.setScopeId(context.getScopeId());

        KnowledgeRetrieveResult retrieval = context.getRetrieval() == null
                ? KnowledgeRetrieveResult.empty() : context.getRetrieval();
        String remoteRetrievalStatus = textValue(responseMap.get("retrievalStatus"));
        response.setRetrievalStatus("degraded".equalsIgnoreCase(remoteRetrievalStatus)
                || StringUtils.hasText(response.getDegradedReason())
                ? KnowledgeRetrievalStatus.DEGRADED : retrieval.getRetrievalStatus());
        List<String> remoteRetrievalMethods = textList(responseMap.get("retrievalMethods"));
        response.setRetrievalMethods(remoteRetrievalMethods.isEmpty()
                ? retrieval.getRetrievalMethods() : remoteRetrievalMethods);
        response.setProvider(textValue(responseMap.get("provider")));
        response.setModel(textValue(responseMap.get("model")));
        response.setFallbackLevel(textValue(responseMap.get("fallbackLevel")));
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
        response.setMemoryCandidates(memoryItems(responseMap.get("memoryCandidates")));
        response.setMemoryApplied(memoryApplied(responseMap.get("memoryApplied")));
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

    private List<AgentMemoryItem> memoryItems(Object value) {
        if (!(value instanceof List<?> values)) {
            return new ArrayList<>();
        }
        List<AgentMemoryItem> items = new ArrayList<>();
        for (Object valueItem : values) {
            if (!(valueItem instanceof Map<?, ?> map)) {
                continue;
            }
            AgentMemoryItem item = new AgentMemoryItem();
            item.setId(textValue(map.get("id")));
            item.setMemoryType(textValue(map.get("memoryType")));
            item.setFieldKey(textValue(map.get("fieldKey")));
            item.setContent(textValue(map.get("content")));
            item.setStatus(textValue(map.get("status")));
            item.setSource(textValue(map.get("source")));
            item.setSourceThreadId(textValue(map.get("sourceThreadId")));
            item.setConfidence(doubleValue(map.get("confidence")));
            item.setExpiresAt(textValue(map.get("expiresAt")));
            item.setDeletedAt(textValue(map.get("deletedAt")));
            item.setPurgeAfter(textValue(map.get("purgeAfter")));
            item.setCreatedAt(textValue(map.get("createdAt")));
            item.setUpdatedAt(textValue(map.get("updatedAt")));
            items.add(item);
        }
        return items;
    }

    private AgentMemoryApplied memoryApplied(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        AgentMemoryApplied applied = new AgentMemoryApplied();
        Object count = map.get("count");
        if (count instanceof Number number) {
            applied.setCount(number.intValue());
        }
        applied.setMemoryIds(textList(map.get("memoryIds")));
        return applied;
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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

    private Map<String, Object> retrievalCompletedEvent(AgentQaRequest request,
                                                        AgentAnswerContext context,
                                                        String runId) {
        Map<String, Object> event = new LinkedHashMap<>();
        if (StringUtils.hasText(runId)) {
            event.put("runId", runId);
        }
        event.put("phase", "retrieval");
        event.put("label", "知识与业务上下文已准备");
        if (Boolean.TRUE.equals(request.getDebug()) && context != null && context.getRetrieval() != null
                && context.getRetrieval().getRetrievalTrace() != null) {
            event.put("retrievalTrace", context.getRetrieval().getRetrievalTrace());
        }
        return event;
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

    private AgentQaResponse askWithAgentPipeline(AgentQaRequest request,
                                                  AuthCurrentUserVO currentUser) {
        return askWithPipeline(request, currentUser, true);
    }

    private AgentQaResponse askWithLocalFallbackPipeline(AgentQaRequest request,
                                                          AuthCurrentUserVO currentUser) {
        return askWithPipeline(request, currentUser, false);
    }

    private AgentQaResponse askWithPipeline(AgentQaRequest request,
                                             AuthCurrentUserVO currentUser,
                                             boolean allowRemoteAgent) {
        return askWithPipeline(request, currentUser, allowRemoteAgent, ignored -> {
        });
    }

    private AgentQaResponse askWithPipeline(AgentQaRequest request,
                                             AuthCurrentUserVO currentUser,
                                             boolean allowRemoteAgent,
                                             Consumer<AgentAnswerContext> contextConsumer) {

        String question = request.getQuestion().trim();
        AgentIntent intent = hasImageAttachments(request)
                ? AgentIntent.RESOURCE_EXPLANATION : intentRecognizer.recognize(question);

        if (intent == AgentIntent.UNKNOWN && (agentRuntimeClient == null || !allowRemoteAgent)) {
            return skippedResponse(intent,
                    "我目前支持查询周边资源、解释教育资源、设计教学活动，以及查询人物、学校和资源之间的关系。请补充学校、资源或区域名称。", null);
        }

        ScopeResolution scopeResolution = resolveScope(request, currentUser, question);
        if (scopeResolution.requiresClarification()) {
            return clarificationResponse(intent, scopeResolution.message(), scopeResolution.options());
        }
        Scope scope = scopeResolution.scope();

        AgentAnswerContext context = buildAgentContext(request, currentUser, question, intent, scope);
        contextConsumer.accept(context);
        KnowledgeRetrieveResult retrieval = context.getRetrieval();

        AgentRuntimeResult remote = !allowRemoteAgent || agentRuntimeClient == null
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
        response.setRetrievalMethods(remote == null || remote.getRetrievalMethods() == null
                || remote.getRetrievalMethods().isEmpty()
                ? retrieval.getRetrievalMethods() : remote.getRetrievalMethods());
        response.setProvider(remote == null ? null : remote.getProvider());
        response.setModel(remote == null ? null : remote.getModel());
        response.setFallbackLevel(remote == null ? null : remote.getFallbackLevel());
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
        response.setDegradedReason(remote == null ? null : remote.getDegradedReason());
        if (StringUtils.hasText(response.getDegradedReason())) {
            response.setRetrievalStatus(KnowledgeRetrievalStatus.DEGRADED);
        }
        response.setToolExecutions(remote == null ? new ArrayList<>() : nonNullList(remote.getToolExecutions()));
        response.setMemoryCandidates(remote == null || remote.getMemoryCandidates() == null
                ? new ArrayList<>() : remote.getMemoryCandidates());
        response.setMemoryApplied(remote == null ? null : remote.getMemoryApplied());
        return response;
    }

    private void validateRequest(AgentQaRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuestion())) {
            throw new IllegalArgumentException("question is required");
        }
        if (request.getScopeId() != null && request.getScopeId() <= 0) {
            throw new IllegalArgumentException("scopeId must be positive");
        }
        List<AgentAttachmentRequest> attachments = request.getAttachments() == null
                ? Collections.emptyList() : request.getAttachments();
        if (attachments.size() > 3) {
            throw new IllegalArgumentException("at most 3 image attachments are allowed");
        }
        for (AgentAttachmentRequest attachment : attachments) {
            if (attachment == null || !"image".equals(attachment.getType())
                    || !StringUtils.hasText(attachment.getName())
                    || !StringUtils.hasText(attachment.getMediaType())
                    || !StringUtils.hasText(attachment.getDataUrl())) {
                throw new IllegalArgumentException("image attachment is invalid");
            }
            String prefix = "data:" + attachment.getMediaType() + ";base64,";
            if (!(List.of("image/jpeg", "image/png", "image/webp", "image/gif")
                    .contains(attachment.getMediaType()))
                    || !attachment.getDataUrl().startsWith(prefix)
                    || attachment.getDataUrl().length() > 7_100_000) {
                throw new IllegalArgumentException("image attachment format or size is invalid");
            }
        }
    }

    private boolean hasImageAttachments(AgentQaRequest request) {
        return request != null && request.getAttachments() != null && !request.getAttachments().isEmpty();
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
        request.setIntent(context.getIntent() == null ? null : context.getIntent().name());
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
        result.refreshRetrievalMethods();
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
