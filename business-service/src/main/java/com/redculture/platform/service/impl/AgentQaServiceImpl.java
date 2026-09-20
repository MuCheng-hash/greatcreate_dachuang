package com.redculture.platform.service.impl;

import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.ClassLearningTask;
import com.redculture.platform.entity.ClassMember;
import com.redculture.platform.entity.StudentProfile;
import com.redculture.platform.entity.StudentTaskProgress;
import com.redculture.platform.entity.TaskResourceRel;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.ClassLearningTaskMapper;
import com.redculture.platform.mapper.ClassMemberMapper;
import com.redculture.platform.mapper.StudentProfileMapper;
import com.redculture.platform.mapper.StudentTaskProgressMapper;
import com.redculture.platform.mapper.TaskResourceRelMapper;
import com.redculture.platform.mapper.SchoolResourceRelMapper;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.service.AgentQaService;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TownMapService;
import com.redculture.platform.service.agent.AgentAnswerContext;
import com.redculture.platform.service.agent.AgentAccessGuard;
import com.redculture.platform.service.agent.AgentBusyException;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.service.agent.AgentRuntimeResult;
import com.redculture.platform.service.agent.AgentUpstreamException;
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
import com.redculture.platform.enums.ResourceCategory;
import com.redculture.platform.vo.ai.AgentMemoryApplied;
import com.redculture.platform.vo.ai.AgentMemoryItem;
import com.redculture.platform.vo.ai.AssistantConversationTurnCancellation;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import com.redculture.platform.vo.request.AgentQaRequest;
import com.redculture.platform.vo.request.AgentAttachmentRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Scheduler agentBlockingScheduler;

    @Autowired private StudentProfileMapper studentProfileMapper;
    @Autowired private StudentTaskProgressMapper studentTaskProgressMapper;
    @Autowired private ClassLearningTaskMapper classLearningTaskMapper;
    @Autowired private ClassMemberMapper classMemberMapper;
    @Autowired private TaskResourceRelMapper taskResourceRelMapper;
    @Autowired private SchoolResourceRelMapper schoolResourceRelMapper;

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
                              AgentProperties agentProperties,
                              @Qualifier("agentBlockingScheduler") Scheduler agentBlockingScheduler) {
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
        this.agentBlockingScheduler = agentBlockingScheduler;
    }

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
        this(
                schoolMapService,
                townMapService,
                localEduResourceService,
                knowledgeRetriever,
                intentRecognizer,
                answerGenerator,
                citationValidator,
                accessGuard,
                agentRuntimeClient,
                agentProperties,
                Schedulers.immediate()
        );
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
                agentRuntimeClient, new AgentProperties(), Schedulers.immediate());
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
                new AgentAccessGuard(schoolMapService), null, new AgentProperties(),
                Schedulers.immediate());
    }

    /** 面向有状态运行时路径的兼容构造方法。 */
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
                new AgentAccessGuard(schoolMapService), agentRuntimeClient, new AgentProperties(),
                Schedulers.immediate());
    }

    @Override
    /**
     * 非流式问答入口：先验证请求和认证账号，再在阻塞调度器中构建受限上下文。
     * 是否调用远程 Agent 由运行时配置决定，回退结果仍沿用同一份权限范围与引用规则。
     */
    public Mono<AgentQaResponse> ask(AgentQaRequest request, AuthCurrentUserVO currentUser) {
        validateRequest(request);
        if (currentUser == null) {
            throw new IllegalArgumentException("需要学校账号");
        }

        return askWithAgentPipeline(request, currentUser);
    }

    @Override
    /**
     * 取消指定会话轮次，并以账号、学校和角色限制取消权限。
     * 取消请求交给状态存储处理，从而使重复请求不会重新触发已经停止的上游执行。
     */
    public Mono<AssistantConversationTurnCancellation> cancelTurn(
            String clientTurnId, AuthCurrentUserVO currentUser) {
        if (!StringUtils.hasText(clientTurnId)) {
            throw new IllegalArgumentException("clientTurnId 不能为空");
        }
        if (currentUser == null || currentUser.getSchoolId() == null) {
            throw new IllegalArgumentException("需要学校账号");
        }
        if (agentRuntimeClient == null) {
            throw new IllegalStateException("有状态 Agent 运行时不可用");
        }
        return agentRuntimeClient.cancelConversationTurn(
                clientTurnId,
                agentRuntimeClient.ownerIdFor(currentUser),
                "SCHOOL",
                currentUser.getSchoolId()
        );
    }

    @Override
    /**
     * 读取待确认动作的当前状态。
     * actionId 必须同时属于认证账号和其学校范围；状态存储未命中与越权均由上游按统一业务错误处理。
     */
    public Mono<com.redculture.platform.vo.ai.AgentActionVO> getAction(
            String actionId, AuthCurrentUserVO currentUser) {
        // 先验证身份、学校和运行时可用性，再把同一所有者范围传给状态服务，避免仅凭 actionId 越权查询。
        validateActionRequest(actionId, currentUser);
        return agentRuntimeClient.getAction(
                actionId,
                agentRuntimeClient.ownerIdFor(currentUser),
                "SCHOOL",
                currentUser.getSchoolId()
        );
    }

    @Override
    /**
     * 确认或拒绝待执行动作，并将身份范围随决策一并传给有状态运行时。
     * 同一动作的重复决策由运行时状态机幂等处理，本层仅拦截非法决策枚举。
     */
    public Mono<com.redculture.platform.vo.ai.AgentActionVO> decideAction(
            String actionId, String decision, AuthCurrentUserVO currentUser) {
        validateActionRequest(actionId, currentUser);
        // 决策值只允许终态确认或拒绝，防止将任意文本透传给可能执行副作用的 Agent 动作。
        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            throw new IllegalArgumentException("decision 必须为 approve 或 reject");
        }
        return agentRuntimeClient.decideAction(
                actionId,
                decision,
                agentRuntimeClient.ownerIdFor(currentUser),
                "SCHOOL",
                currentUser.getSchoolId()
        );
    }

    private void validateActionRequest(String actionId, AuthCurrentUserVO currentUser) {
        // actionId 为空时不访问运行时，避免把参数错误伪装成上游故障。
        if (!StringUtils.hasText(actionId)) {
            throw new IllegalArgumentException("actionId 不能为空");
        }
        if (currentUser == null || currentUser.getSchoolId() == null) {
            throw new IllegalArgumentException("需要学校账号");
        }
        if (agentRuntimeClient == null) {
            throw new IllegalStateException("有状态 Agent 运行时不可用");
        }
    }

    @Override
    /**
     * 建立带阶段事件的 SSE 问答流。远程运行时不可用时采用本地管线，
     * 两条路径都先完成认证范围解析和可信知识检索，避免流式接口绕过鉴权。
     */
    public Flux<ServerSentEvent<Map<String, Object>>> stream(
            AgentQaRequest request, AuthCurrentUserVO currentUser) {
        return Flux.defer(() -> {
            // 延迟到订阅时校验，确保每次 SSE 重连都重新使用当前认证上下文，而不是复用过期范围。
            validateRequest(request);
            if (currentUser == null) {
                return Flux.error(new IllegalArgumentException("需要学校账号"));
            }
            return onBlockingScheduler(() -> accessGuard.resolveScope(
                    request.getScopeType(),
                    request.getScopeId(),
                    currentUser,
                    request.getQuestion().trim()
            )).flatMapMany(scopeResolution -> streamResolvedScope(
                    request, currentUser, scopeResolution
            ));
        }).onErrorResume(error -> streamFailure(request, error));
    }

    private Flux<ServerSentEvent<Map<String, Object>>> streamResolvedScope(
            AgentQaRequest request,
            AuthCurrentUserVO currentUser,
            AgentAccessGuard.ScopeResolution scopeResolution) {
        if (scopeResolution.clarificationRequired()) {
            // 范围不唯一时只返回澄清选项，不发起检索或模型调用，防止在错误对象上生成回答。
            AgentQaResponse clarification = clarificationResponse(
                    AgentIntent.UNKNOWN,
                    scopeResolution.message(),
                    scopeResolution.options()
            );
            clarification.setClientTurnId(request.getClientTurnId());
            return Flux.just(
                    sse("final", Map.of("response", clarification)),
                    sse("done", Collections.emptyMap())
            );
        }
        if (agentRuntimeClient == null) {
            // 有状态运行时缺失时保留 SSE 协议形状，由本地管线产生可消费的降级回答。
            return localFallbackStream(request, currentUser);
        }

        String question = request.getQuestion().trim();
        AgentIntent intent = hasImageAttachments(request)
                ? AgentIntent.RESOURCE_EXPLANATION : intentRecognizer.recognize(question);
        Scope scope = new Scope(scopeResolution.type(), scopeResolution.id());
        return Flux.concat(
                Flux.just(sse("phase.started", Map.of(
                        "phase", "retrieval",
                        "label", "正在检索可信知识与业务数据"
                ))),
                onBlockingScheduler(() -> buildAgentContext(
                        request, currentUser, question, intent, scope
                )).flatMapMany(context -> Flux.concat(
                        Flux.just(sse(
                                "phase.completed",
                                retrievalCompletedEvent(request, context, null)
                        )),
                        upstreamStream(request, currentUser, context)
                ))
        );
    }

    private Flux<ServerSentEvent<Map<String, Object>>> upstreamStream(
            AgentQaRequest request,
            AuthCurrentUserVO currentUser,
            AgentAnswerContext context) {
        AtomicBoolean upstreamDone = new AtomicBoolean(false);
        AtomicBoolean upstreamFinal = new AtomicBoolean(false);
        return agentRuntimeClient.streamStateful(request, currentUser, context)
                .map(event -> {
                    // 上游 final 事件包含松散 Map，需要在业务侧补回可信范围、引用校验和统一响应字段。
                    if ("done".equals(event.event())) {
                        upstreamDone.set(true);
                    }
                    Map<String, Object> data = event.safeData();
                    if ("final".equals(event.event())) {
                        upstreamFinal.set(true);
                        data = normalizeStatefulFinalEvent(data, request, context);
                    }
                    return sse(event.event(), data);
                })
                .concatWith(Flux.defer(() -> upstreamDone.get()
                        ? Flux.empty()
                        : Flux.just(sse("done", Collections.emptyMap()))))
                .onErrorResume(error -> {
                    // final 已发出时不覆盖用户已看到的回答，只补发可识别的异常终止事件。
                    if (upstreamFinal.get()) {
                        return errorEvents(
                                request,
                                "agent_stream_incomplete",
                                "Agent 已返回回答，但流式连接未正常结束",
                                true
                        );
                    }
                    return streamFailure(request, error);
                });
    }

    private Flux<ServerSentEvent<Map<String, Object>>> localFallbackStream(
            AgentQaRequest request,
            AuthCurrentUserVO currentUser) {
        String runId = UUID.randomUUID().toString();
        return onBlockingScheduler(() -> {
            // 复用非流式本地管线以保证引用、权限和降级语义一致，再将完整答案切成协议兼容的 token 事件。
            AgentAnswerContext[] context = new AgentAnswerContext[1];
            AgentQaResponse response = askWithPipeline(
                    request, currentUser, false, value -> context[0] = value
            );
            response.setRunId(runId);
            if (!StringUtils.hasText(response.getConversationId())) {
                response.setConversationId(StringUtils.hasText(request.getConversationId())
                        ? request.getConversationId() : UUID.randomUUID().toString());
            }
            return new LocalFallbackResult(response, context[0]);
        }).flatMapMany(result -> {
            String answer = result.response().getAnswer() == null
                    ? "" : result.response().getAnswer();
            int chunkCount = (answer.length() + 7) / 8;
            Flux<ServerSentEvent<Map<String, Object>>> tokens = Flux.range(0, chunkCount)
                    // 降级路径没有真实模型增量，仍以小片段模拟推送，避免前端因协议差异无法展示回答。
                    .delayElements(Duration.ofMillis(1))
                    .map(index -> sse("token", Map.of(
                            "runId", runId,
                            "delta", answer.substring(
                                    index * 8,
                                    Math.min(answer.length(), (index + 1) * 8)
                            )
                    )));
            return Flux.concat(
                    Flux.just(
                            sse("run.started", Map.of(
                                    "runId", runId,
                                    "clientTurnId", request.getClientTurnId(),
                                    "resumed", false,
                                    "attempt", 1
                            )),
                            sse("phase.started", Map.of(
                                    "runId", runId,
                                    "phase", "retrieval",
                                    "label", "正在检索可信知识与业务数据"
                            )),
                            sse("phase.completed", retrievalCompletedEvent(
                                    request, result.context(), runId
                            )),
                            sse("phase.started", Map.of(
                                    "runId", runId,
                                    "phase", "response",
                                    "label", "正在生成回答"
                            ))
                    ),
                    tokens,
                    Flux.just(
                            sse("phase.completed", Map.of(
                                    "runId", runId,
                                    "phase", "response",
                                    "label", "回答生成完成"
                            )),
                            sse("final", Map.of(
                                    "runId", runId,
                                    "response", result.response()
                            )),
                            sse("done", Map.of("runId", runId))
                    )
            );
        });
    }

    private Map<String, Object> normalizeStatefulFinalEvent(Map<String, Object> eventData,
                                                             AgentQaRequest request,
                                                             AgentAnswerContext context) {
        Map<String, Object> normalized = new LinkedHashMap<>(eventData);
        // 远程 payload 属于不可信边界：只抽取允许字段，业务端重新注入已解析的意图、范围和验证后的引用。
        Object rawResponse = eventData.get("response");
        Map<?, ?> responseMap = rawResponse instanceof Map<?, ?> map ? map : Collections.emptyMap();
        AgentQaResponse response = new AgentQaResponse();
        String answer = textValue(responseMap.get("answer"));
        response.setAnswer(StringUtils.hasText(answer) ? answer : "暂时无法生成有效回答。");
        response.setThreadId(firstText(responseMap.get("threadId"), eventData.get("threadId")));
        response.setClientTurnId(firstText(
                responseMap.get("clientTurnId"),
                firstText(eventData.get("clientTurnId"), request.getClientTurnId())
        ));
        response.setStatus(firstText(responseMap.get("status"), "degraded"));
        response.setDegradedReason(textValue(responseMap.get("degradedReason")));
        response.setRunId(textValue(eventData.get("runId")));
        response.setConversationId(request.getConversationId());
        response.setIntent(context.getIntent());
        response.setScopeType(context.getScopeType());
        response.setScopeId(context.getScopeId());
        response.setAppliedContext(appliedContext(context));

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
        response.setExploreSuggestions(exploreSuggestions(context));
        if (context.isStudentMode()) {
            response.setRelatedResources(relatedResources(context));
            response.setExploreSuggestions(exploreSuggestions(context));
        }
        response.setFollowUpQuestions(followUps);
        response.setToolExecutions(toolNames(responseMap.get("toolExecutions")));
        response.setMemoryCandidates(memoryItems(responseMap.get("memoryCandidates")));
        response.setMemoryApplied(memoryApplied(responseMap.get("memoryApplied")));
        normalized.put("threadId", response.getThreadId());
        normalized.put("clientTurnId", response.getClientTurnId());
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

    /**
     * 将用户、问题、识别意图和已解析范围合成为 Agent 上下文。
     * 这里是从 HTTP 认证信息收紧到学校、班级、任务和资源集合的关键边界，后续检索不得扩大它。
     */
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
        context.setResourceCategory(clean(request.getResourceCategory()));
        context.setMaxDistanceMeters(request.getMaxDistanceMeters());
        context.setStudentMode(currentUser != null && "student".equalsIgnoreCase(currentUser.getRoleCode()));
        context.setAccountId(currentUser == null ? null : currentUser.getAccountId());
        context.setResourceId(request.getResourceId());
        context.setTaskId(request.getTaskId());
        // 业务上下文先补齐学生任务、班级或资源等授权数据，再执行知识检索，避免检索结果反向决定权限。
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
        // 检索轨迹只在显式调试请求中回传；普通回答不暴露内部召回分数、通道与实体范围。
        if (Boolean.TRUE.equals(request.getDebug()) && context != null && context.getRetrieval() != null
                && context.getRetrieval().getRetrievalTrace() != null) {
            event.put("retrievalTrace", context.getRetrieval().getRetrievalTrace());
        }
        return event;
    }

    private ServerSentEvent<Map<String, Object>> sse(
            String eventName, Map<String, Object> data) {
        return ServerSentEvent.<Map<String, Object>>builder()
                .event(eventName)
                .data(data)
                .build();
    }

    private Flux<ServerSentEvent<Map<String, Object>>> streamFailure(
            AgentQaRequest request, Throwable error) {
        Throwable unwrapped = Exceptions.unwrap(error);
        // 先按可恢复性分类，前端据此决定是否复用 clientTurnId 重连，而不是盲目发起新轮次。
        if (unwrapped instanceof AgentBusyException) {
            return errorEvents(
                    request,
                    "agent_busy",
                    "当前请求较多，请稍后使用同一 clientTurnId 重试",
                    true
            );
        }
        if (unwrapped instanceof AgentUpstreamException upstream) {
            return errorEvents(
                    request,
                    upstream.getCode(),
                    "Agent 服务暂时不可用，可使用同一 clientTurnId 恢复本轮执行",
                    upstream.isRetryable()
            );
        }
        if (unwrapped instanceof IllegalArgumentException) {
            return errorEvents(
                    request,
                    "request_invalid",
                    unwrapped.getMessage() == null ? "request failed" : unwrapped.getMessage(),
                    false
            );
        }
        return errorEvents(
                request,
                "agent_stream_interrupted",
                "连接中断，可使用同一 clientTurnId 恢复本轮执行",
                true
        );
    }

    private Flux<ServerSentEvent<Map<String, Object>>> errorEvents(
            AgentQaRequest request,
            String code,
            String message,
            boolean retryable) {
        // error 后始终追加 done，确保客户端释放加载状态；该约定也适用于参数错误等不可重试情形。
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("errorType", code);
        error.put("message", message);
        error.put("clientTurnId", request == null ? null : request.getClientTurnId());
        error.put("retryable", retryable);
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("clientTurnId", request == null ? null : request.getClientTurnId());
        return Flux.just(sse("error", error), sse("done", done));
    }

    private <T> Mono<T> onBlockingScheduler(Callable<T> callable) {
        // 映射专用调度器拒绝为可识别的繁忙错误，避免 Reactor 线程上的阻塞操作拖慢其他请求。
        return Mono.fromCallable(callable)
                .subscribeOn(agentBlockingScheduler)
                .onErrorMap(error -> Exceptions.unwrap(error) instanceof RejectedExecutionException,
                        AgentBusyException::new);
    }

    private Mono<AgentQaResponse> askWithAgentPipeline(
            AgentQaRequest request, AuthCurrentUserVO currentUser) {
        return onBlockingScheduler(() -> prepareAnswer(
                request, currentUser, true, ignored -> { }
        )).flatMap(prepared -> {
            // 澄清问题等早期结果不调用模型；其余结果先准备本地回退，再尝试远程有状态 Agent。
            if (prepared.earlyResponse() != null) {
                return Mono.just(prepared.earlyResponse());
            }
            Mono<AgentQaResponse> local = onBlockingScheduler(() -> completeAnswer(
                    request, prepared, null
            ));
            if (agentRuntimeClient == null) {
                return local;
            }
            // 远程空响应回退到本地生成，远程异常则交由调用链保留其可重试错误语义。
            return agentRuntimeClient.generate(request, currentUser, prepared.context())
                    .flatMap(remote -> onBlockingScheduler(() -> completeAnswer(
                            request, prepared, remote
                    )))
                    .switchIfEmpty(local);
        });
    }

    private AgentQaResponse askWithLocalFallbackPipeline(AgentQaRequest request,
                                                          AuthCurrentUserVO currentUser) {
        return askWithPipeline(request, currentUser, false);
    }

    /** 按统一管线完成上下文、检索、生成和引用校验，供同步问答与本地流式降级共用。 */
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
        AnswerPreparation prepared = prepareAnswer(
                request, currentUser, allowRemoteAgent, contextConsumer
        );
        return prepared.earlyResponse() == null
                ? completeAnswer(request, prepared, null)
                : prepared.earlyResponse();
    }

    private AnswerPreparation prepareAnswer(
            AgentQaRequest request,
            AuthCurrentUserVO currentUser,
            boolean allowRemoteAgent,
            Consumer<AgentAnswerContext> contextConsumer) {
        String question = request.getQuestion().trim();
        AgentIntent intent = hasImageAttachments(request)
                ? AgentIntent.RESOURCE_EXPLANATION : intentRecognizer.recognize(question);

        // 本地回退无法处理未知意图时尽早返回澄清，避免在没有可靠检索目标的情况下生成内容。
        if (intent == AgentIntent.UNKNOWN && (agentRuntimeClient == null || !allowRemoteAgent)) {
            return new AnswerPreparation(
                    skippedResponse(
                            intent,
                            "我目前支持查询周边资源、解释教育资源、设计教学活动，以及查询人物、学校和资源之间的关系。请补充学校、资源或区域名称。",
                            null
                    ),
                    intent,
                    null,
                    null,
                    null
            );
        }

        ScopeResolution scopeResolution = resolveScope(request, currentUser, question);
        // 范围解析失败或不唯一时不构造上下文；模型永远不能参与选择用户实际想问的学校或资源。
        if (scopeResolution.requiresClarification()) {
            return new AnswerPreparation(
                    clarificationResponse(
                            intent,
                            scopeResolution.message(),
                            scopeResolution.options()
                    ),
                    intent,
                    null,
                    null,
                    null
            );
        }
        Scope scope = scopeResolution.scope();

        AgentAnswerContext context = buildAgentContext(request, currentUser, question, intent, scope);
        contextConsumer.accept(context);
        KnowledgeRetrieveResult retrieval = context.getRetrieval();
        return new AnswerPreparation(null, intent, scope, context, retrieval);
    }

    private AgentQaResponse completeAnswer(
            AgentQaRequest request,
            AnswerPreparation prepared,
            AgentRuntimeResult remote) {
        AgentIntent intent = prepared.intent();
        Scope scope = prepared.scope();
        AgentAnswerContext context = prepared.context();
        KnowledgeRetrieveResult retrieval = prepared.retrieval();
        GeneratedAnswer generated = remote == null ? null : remote.getAnswer();
        if (generated == null) {
            try {
                // 远程未提供完整答案时才调用本地生成器，保留远程模型已声明的结果优先级。
                generated = answerGenerator.generate(context);
            } catch (RuntimeException exception) {
                // 本地生成失败转换为可展示的降级结果，不让单次模型故障破坏已完成的检索链路。
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
        response.setExploreSuggestions(exploreSuggestions(context));
        response.setCitations(validatedCitations(generated, retrieval));
        response.setFollowUpQuestions(nonNullList(generated.getFollowUpQuestions()));
        response.setThreadId(remote == null ? request.getThreadId() : remote.getThreadId());
        response.setClientTurnId(request.getClientTurnId());
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
        response.setAppliedContext(appliedContext(context));
        return response;
    }

    private Map<String, Object> appliedContext(AgentAnswerContext context) {
        Map<String, Object> applied = new LinkedHashMap<>();
        applied.put("schoolId", context.getScopeType() == KnowledgeScopeType.SCHOOL ? context.getScopeId() : null);
        applied.put("schoolName", context.getSchoolDetail() == null || context.getSchoolDetail().getSchool() == null
                ? null : context.getSchoolDetail().getSchool().getSchoolName());
        applied.put("grade", context.getGrade());
        applied.put("theme", context.getTheme());
        applied.put("resourceCategory", context.getResourceCategory());
        applied.put("maxDistanceMeters", context.getMaxDistanceMeters());
        applied.put("studentMode", context.isStudentMode());
        applied.put("resourceId", context.getResourceId());
        applied.put("taskId", context.getTaskId());
        applied.put("taskTitle", context.getTaskTitle());
        return applied;
    }

    private List<Map<String, Object>> exploreSuggestions(AgentAnswerContext context) {
        if (!context.isStudentMode()) return new ArrayList<>();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        relatedResources(context).stream().limit(3).forEach(name -> {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("type", "RESOURCE"); item.put("title", "继续了解：" + name); suggestions.add(item);
        });
        Map<String, Object> map = new LinkedHashMap<>(); map.put("type", "MAP"); map.put("title", "查看学校周边智慧地图"); map.put("path", "/map"); suggestions.add(map);
        return suggestions;
    }

    private void validateRequest(AgentQaRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuestion())) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (request.getScopeId() != null && request.getScopeId() <= 0) {
            throw new IllegalArgumentException("scopeId 必须为正数");
        }
        if (request.getResourceId() != null && request.getResourceId() <= 0) throw new IllegalArgumentException("resourceId 必须为正数");
        if (request.getTaskId() != null && request.getTaskId() <= 0) throw new IllegalArgumentException("taskId 必须为正数");
        if (StringUtils.hasText(request.getTheme()) && request.getTheme().trim().length() > 50) {
            throw new IllegalArgumentException("主题长度不能超过 50 个字符");
        }
        if (StringUtils.hasText(request.getResourceCategory())) {
            ResourceCategory.fromValue(request.getResourceCategory());
        }
        if (request.getMaxDistanceMeters() != null
                && !List.of(1000, 3000, 5000, 10000).contains(request.getMaxDistanceMeters())) {
            throw new IllegalArgumentException("maxDistanceMeters 必须为 1000、3000、5000 或 10000 之一");
        }
        if (!StringUtils.hasText(request.getClientTurnId())) {
            request.setClientTurnId(java.util.UUID.randomUUID().toString());
        }
        List<AgentAttachmentRequest> attachments = request.getAttachments() == null
                ? Collections.emptyList() : request.getAttachments();
        if (attachments.size() > 3) {
            throw new IllegalArgumentException("最多允许添加 3 个图片附件");
        }
        for (AgentAttachmentRequest attachment : attachments) {
            if (attachment == null || !"image".equals(attachment.getType())
                    || !StringUtils.hasText(attachment.getName())
                    || !StringUtils.hasText(attachment.getMediaType())
                    || !StringUtils.hasText(attachment.getDataUrl())) {
                throw new IllegalArgumentException("图片附件无效");
            }
            String prefix = "data:" + attachment.getMediaType() + ";base64,";
            if (!(List.of("image/jpeg", "image/png", "image/webp", "image/gif")
                    .contains(attachment.getMediaType()))
                    || !attachment.getDataUrl().startsWith(prefix)
                    || attachment.getDataUrl().length() > 7_100_000) {
                throw new IllegalArgumentException("图片附件格式或大小无效");
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
                throw new IllegalArgumentException("需要学校账号");
            }
            if (requestedType != null && requestedType != KnowledgeScopeType.SCHOOL) {
                throw new IllegalArgumentException("学校账号只能查询本校数据");
            }
            if (requestedId != null && !requestedId.equals(currentUser.getSchoolId())) {
                throw new IllegalArgumentException("无权访问其他学校");
            }
            List<SchoolSummaryVO> mentionedSchools = findMentionedSchools(question);
            if (mentionedSchools.stream().anyMatch(school -> !currentUser.getSchoolId().equals(school.getSchoolId()))) {
                throw new IllegalArgumentException("无权访问其他学校");
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
        if (context.isStudentMode()) {
            if (context.getScopeType() != KnowledgeScopeType.SCHOOL) {
                throw new IllegalArgumentException("学生账号只能查询本校数据");
            }
            if (context.getResourceId() != null) {
                LocalEduResource resource = localEduResourceService.getById(context.getResourceId());
                if (resource == null || !Boolean.TRUE.equals(resource.getActive()) || resource.getReviewStatus() != ReviewStatus.APPROVED
                        || !schoolResourceRelMapper.exists(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SchoolResourceRel>()
                        .eq(SchoolResourceRel::getSchoolId, context.getScopeId()).eq(SchoolResourceRel::getResourceId, context.getResourceId()))) {
                    throw new IllegalArgumentException("该资源不对当前学生开放");
                }
                context.setResource(resource);
            }
            if (context.getTaskId() != null) {
                StudentProfile student = studentProfileMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudentProfile>()
                        .eq(StudentProfile::getAccountId, currentAccountId(context)).eq(StudentProfile::getStatus, "active").last("LIMIT 1"));
                ClassLearningTask task = classLearningTaskMapper.selectById(context.getTaskId());
                boolean assigned = student != null && task != null && "published".equalsIgnoreCase(task.getStatus())
                        && classMemberMapper.exists(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ClassMember>()
                        .eq(ClassMember::getStudentId, student.getStudentId()).eq(ClassMember::getClassId, task.getClassId()).eq(ClassMember::getStatus, "active"))
                        && studentTaskProgressMapper.exists(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudentTaskProgress>()
                        .eq(StudentTaskProgress::getStudentId, student.getStudentId()).eq(StudentTaskProgress::getTaskId, task.getTaskId()));
                if (!assigned) throw new IllegalArgumentException("该任务不对当前学生开放");
                context.setTaskTitle(task.getTitle()); context.setTaskDescription(task.getDescription());
                context.setTaskResourceIds(taskResourceRelMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskResourceRel>()
                        .eq(TaskResourceRel::getTaskId, task.getTaskId()).orderByAsc(TaskResourceRel::getSortOrder)).stream().map(TaskResourceRel::getResourceId).toList());
            }
        }
        switch (context.getScopeType()) {
            case SCHOOL -> {
                SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(context.getScopeId());
                if (detail == null) {
                    throw new IllegalArgumentException("学校不存在或不可用");
                }
                context.setSchoolDetail(detail);
                context.setMatchedSchoolResource(findMentionedResource(detail, context.getQuestion()));
            }
            case REGION -> {
                if (townMapService != null) {
                    context.setRegionDetail(townMapService.getTownMapDetail(context.getScopeId()));
                }
                if (context.getRegionDetail() == null) {
                    throw new IllegalArgumentException("区域不存在或不可用");
                }
            }
            case RESOURCE -> {
                LocalEduResource resource = localEduResourceService.getById(context.getScopeId());
                if (resource == null || !Boolean.TRUE.equals(resource.getActive())
                        || resource.getReviewStatus() != ReviewStatus.APPROVED) {
                    throw new IllegalArgumentException("资源不存在或不可用");
                }
                context.setResource(resource);
            }
        }
    }

    private Long currentAccountId(AgentAnswerContext context) {
        // 学生归属已经由认证范围确定；prepareAnswer 中还会使用账号进一步约束任务查询。
        return context.getAccountId();
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
        request.setResourceCategory(context.getResourceCategory());
        request.setMaxDistanceMeters(context.getMaxDistanceMeters());
        if (context.getResourceId() != null) {
            request.setResourceIds(List.of(context.getResourceId()));
        } else if (context.getTaskResourceIds() != null && !context.getTaskResourceIds().isEmpty()) {
            request.setResourceIds(context.getTaskResourceIds());
        }
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

    private record AnswerPreparation(
            AgentQaResponse earlyResponse,
            AgentIntent intent,
            Scope scope,
            AgentAnswerContext context,
            KnowledgeRetrieveResult retrieval) {
    }

    private record LocalFallbackResult(
            AgentQaResponse response, AgentAnswerContext context) {
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
