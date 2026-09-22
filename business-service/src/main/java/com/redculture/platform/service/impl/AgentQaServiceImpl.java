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

/**
 * 智能问答服务的业务编排实现。
 *
 * <p>该类不直接承担模型推理或向量检索实现，而是将一次问答请求收敛为受控的执行链路：
 * 校验输入和附件，按当前账号解析允许访问的知识范围，装配学校、区域、资源及学生任务上下文，
 * 调用 {@link KnowledgeRetriever} 获取可引用证据，最后交由有状态 Agent 运行时或本地生成器产出回答。</p>
 *
 * <p>非流式 {@link #ask(AgentQaRequest, AuthCurrentUserVO)} 优先使用远程 Agent；
 * 流式 {@link #stream(AgentQaRequest, AuthCurrentUserVO)} 以 SSE 输出运行阶段、增量文本、最终响应和结束事件。
 * 当远程运行时未配置时，两条路径都会保留相同的权限、检索和引用校验语义，并降级到本地生成管线。</p>
 *
 * <p>安全边界由本类保持：调用方提供的范围不能突破认证账号范围，远程运行时返回的松散数据不能直接
 * 作为业务响应，引用、范围、意图及业务上下文均需在本服务内重新校验或注入。</p>
 */
@Service
public class AgentQaServiceImpl implements AgentQaService {

    /** 未传入有效召回数量时使用的默认知识片段数。 */
    private static final int DEFAULT_TOP_K = 5;
    /** 单次请求允许的最大知识片段数，防止过大的上下文扩大模型输入和检索负载。 */
    private static final int MAX_TOP_K = 8;
    /** 从自然语言问题中识别年级信息的模式，显式请求参数优先于该推断结果。 */
    private static final Pattern GRADE_PATTERN = Pattern.compile("(低年级|中年级|高年级|[一二三四五六七八九十0-9]+年级)");

    /** 查询学校详情、学校资源及学校名称的地图业务服务。 */
    private final SchoolMapService schoolMapService;
    /** 查询区域地图与区域标记信息的业务服务。 */
    private final TownMapService townMapService;
    /** 读取教育资源实体，并校验资源启用、审核状态的服务。 */
    private final LocalEduResourceService localEduResourceService;
    /** 混合知识检索入口，负责返回文本片段、图谱事实及候选引用。 */
    private final KnowledgeRetriever knowledgeRetriever;
    /** 将用户问题归类为资源解释、活动设计、关系查询等可处理意图的识别器。 */
    private final IntentRecognizer intentRecognizer;
    /** 远程 Agent 不可用或未返回有效答案时使用的本地回答生成器。 */
    private final AnswerGenerator answerGenerator;
    /** 根据检索结果过滤模型声明的引用，防止未检索到的标识透传给前端。 */
    private final CitationValidator citationValidator;
    /** 统一解析请求范围并依据认证账号执行访问控制的守卫。 */
    private final AgentAccessGuard accessGuard;
    /** 对接 Python 有状态 Agent 运行时；为 null 时进入本地降级路径。 */
    private final AgentRuntimeClient agentRuntimeClient;
    /** Agent 运行时开关及相关配置，保留给依赖注入和兼容构造路径。 */
    private final AgentProperties agentProperties;
    /** 隔离数据库、检索等阻塞操作的专用 Reactor 调度器。 */
    private final Scheduler agentBlockingScheduler;

    /** 学生档案查询，用于确认当前账号对应的有效学生身份。 */
    @Autowired private StudentProfileMapper studentProfileMapper;
    /** 学生任务进度查询，用于确认任务已分配给当前学生。 */
    @Autowired private StudentTaskProgressMapper studentTaskProgressMapper;
    /** 班级学习任务查询。 */
    @Autowired private ClassLearningTaskMapper classLearningTaskMapper;
    /** 班级成员关系查询，用于核验学生是否属于任务所在班级。 */
    @Autowired private ClassMemberMapper classMemberMapper;
    /** 任务与资源关联查询，用于限定任务问答可检索的资源集合。 */
    @Autowired private TaskResourceRelMapper taskResourceRelMapper;
    /** 学校与资源关联查询，用于限制学生访问本校已关联的资源。 */
    @Autowired private SchoolResourceRelMapper schoolResourceRelMapper;

    /**
     * Spring 生产环境使用的完整构造方法。
     *
     * <p>显式注入专用调度器，避免同步数据库和检索调用占用 WebFlux 事件循环线程。</p>
     */
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

    /**
     * 供单元测试或未提供专用调度器的装配路径使用。
     * 使用 {@link Schedulers#immediate()}，调用方需要自行保证不会在事件循环中执行阻塞操作。
     */
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

    /**
     * 供不需要显式 {@link AgentProperties} 的兼容装配路径使用。
     * 默认配置不会替代实际远程运行时客户端的可用性判断。
     */
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

    /**
     * 仅使用本地生成器的兼容构造方法。
     * Agent 运行时客户端为空时，问答与流式接口会自动走本地回退路径。
     */
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

    /**
     * 面向有状态运行时路径的兼容构造方法。
     * 自动创建范围守卫和默认配置，主要用于不由 Spring 完整托管的测试或历史调用点。
     */
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

    /**
     * 执行一次非流式问答。
     *
     * <p>先同步校验请求，随后在阻塞调度器中解析访问范围、准备检索上下文；有远程 Agent 时优先调用它，
     * 否则或远程返回空结果时以本地生成器完成回答。两种结果都会附带校验后的引用和实际生效的业务范围。</p>
     *
     * @param request 包含问题、可选范围、资源/任务限定、附件和会话标识的请求
     * @param currentUser 经认证拦截器解析出的当前账号，不能为空
     * @return 异步的统一问答响应；参数错误在订阅前抛出，运行时错误通过 {@code Mono} 传播
     */
    @Override
    public Mono<AgentQaResponse> ask(AgentQaRequest request, AuthCurrentUserVO currentUser) {
        validateRequest(request);
        if (currentUser == null) {
            throw new IllegalArgumentException("需要学校账号");
        }

        return askWithAgentPipeline(request, currentUser);
    }

    /**
     * 请求取消指定的会话轮次。
     *
     * <p>将账号所有者、固定学校范围一并传至有状态运行时，避免仅凭 {@code clientTurnId} 取消其他账号的任务。
     * 最终取消状态及重复取消的幂等语义由运行时状态存储维护。</p>
     *
     * @param clientTurnId 前端为本轮生成的稳定标识
     * @param currentUser 当前学校账号
     * @return 运行时返回的取消结果
     */
    @Override
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

    /**
     * 查询待确认 Agent 动作的当前状态。
     *
     * <p>动作标识必须属于当前账号且位于其学校范围内；未命中和越权由有状态运行时按统一业务错误处理。</p>
     *
     * @param actionId 待查询动作标识
     * @param currentUser 当前学校账号
     * @return 当前动作及其状态
     */
    @Override
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

    /**
     * 对待确认动作提交批准或拒绝决策。
     *
     * <p>本层仅接受 {@code approve} 与 {@code reject}，并携带当前账号的所有者和学校范围；
     * 重复决策及状态迁移由有状态运行时的动作状态机保证幂等。</p>
     *
     * @param actionId 待决策动作标识
     * @param decision {@code approve} 或 {@code reject}
     * @param currentUser 当前学校账号
     * @return 决策后的动作状态
     */
    @Override
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

    /**
     * 校验动作查询和决策共用的前置条件。
     *
     * <p>该校验同时保证标识非空、调用者具备学校归属且远程运行时已装配，避免将本地参数或配置错误
     * 错误地包装成上游服务故障。</p>
     */
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

    /**
     * 建立包含执行阶段和最终结果的 SSE 问答流。
     *
     * <p>每次订阅都会重新校验请求并解析认证范围。远程运行时可用时转发其流事件，同时修正最终响应；
     * 不可用时以本地完整答案模拟 token 事件。无论路径如何，流以 {@code done} 事件结束，错误则先发送
     * {@code error} 再发送 {@code done}，便于前端稳定释放加载状态。</p>
     *
     * @param request 问答请求
     * @param currentUser 当前认证账号
     * @return SSE 事件流，事件名包括阶段事件、token、final、error 和 done
     */
    @Override
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

    /**
     * 在访问范围已由 {@link AgentAccessGuard} 解析后选择远程或本地流式管线。
     * 范围不唯一时仅发送澄清响应，避免在未确定知识对象的情况下检索或调用模型。
     */
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

    /**
     * 转发远程有状态 Agent 的 SSE 流，并规范化其 {@code final} 事件。
     *
     * <p>上游遗漏 {@code done} 时由本层补发；若最终回答已输出后连接断开，则保留该回答并追加
     * 可识别的错误与结束事件，而不会再以降级答案覆盖用户已看到的内容。</p>
     */
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

    /**
     * 在远程运行时未配置时构造与上游兼容的本地 SSE 流。
     *
     * <p>本地生成器只能一次性返回完整文本，因此该方法按八个字符切分为短 token 并依次推送。
     * 这只是协议兼容的展示方式，并不代表本地模型具备真实的增量生成能力。</p>
     */
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

    /**
     * 将远程运行时的松散最终事件转换为本服务的可信 {@link AgentQaResponse}。
     *
     * <p>远程返回仅提取允许展示的文本、模型信息、工具和记忆字段；意图、范围、已应用上下文及引用
     * 始终以本地预先计算的 {@code context} 为准。由此防止上游响应篡改学校范围或伪造引用。</p>
     *
     * @return 保留原始事件其他字段、但以规范化 {@code response} 替换最终响应的事件数据
     */
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

    /**
     * 从运行时的引用字段中提取引用标识。
     * 兼容字符串数组及包含 {@code citationId} 的对象数组，忽略空值和未知结构。
     */
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

    /**
     * 从运行时工具执行列表中提取可展示的工具名称。
     * 同时兼容 {@code name}、{@code toolName} 和纯字符串三种上游表示。
     */
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

    /**
     * 将上游列表安全转换为非空文本列表，非列表输入按空列表处理。 */
    private List<String> textList(Object value) {
        if (!(value instanceof List<?> values)) {
            return new ArrayList<>();
        }
        return values.stream()
                .map(this::textValue)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 将远程记忆候选对象映射为前端响应对象。
     * 仅复制已定义字段，结构不合法的元素直接跳过，以隔离动态 Map 边界。
     */
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

    /**
     * 解析本轮实际应用的记忆摘要；上游未返回对象时不虚构记忆应用结果。 */
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

    /**
     * 尽力将上游数值或数值字符串转换为 {@link Double}，非法文本返回 {@code null}。 */
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

    /** 返回首个非空文本值，用于处理不同版本运行时返回的同义字段。 */
    private String firstText(Object first, Object fallback) {
        String value = textValue(first);
        return StringUtils.hasText(value) ? value : textValue(fallback);
    }

    /** 将非空动态值转为文本，不对空白文本作额外处理。 */
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

    /**
     * 创建检索阶段完成事件。
     * 调试开关开启时才返回检索轨迹，避免常规 SSE 响应泄露召回分数、通道和内部实体范围。
     */
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

    /** 统一构造携带 Map 数据的 SSE 事件，确保所有分支使用相同的事件封装方式。 */
    private ServerSentEvent<Map<String, Object>> sse(
            String eventName, Map<String, Object> data) {
        return ServerSentEvent.<Map<String, Object>>builder()
                .event(eventName)
                .data(data)
                .build();
    }

    /**
     * 将流式执行异常分类为客户端可理解的错误事件。
     * 可重试性决定前端是否可沿用同一 {@code clientTurnId} 恢复当前轮次。
     */
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

    /**
     * 输出标准错误和结束事件。
     * 无论错误是否可重试，均追加 {@code done}，使前端不会因异常分支永久等待。
     */
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

    /**
     * 在 Agent 专用阻塞调度器中执行同步工作。
     * 调度器拒绝任务时转换为 {@link AgentBusyException}，使调用方得到可重试的业务错误而非 Reactor 原始异常。
     */
    private <T> Mono<T> onBlockingScheduler(Callable<T> callable) {
        // 映射专用调度器拒绝为可识别的繁忙错误，避免 Reactor 线程上的阻塞操作拖慢其他请求。
        return Mono.fromCallable(callable)
                .subscribeOn(agentBlockingScheduler)
                .onErrorMap(error -> Exceptions.unwrap(error) instanceof RejectedExecutionException,
                        AgentBusyException::new);
    }

    /**
     * 执行远程优先的非流式问答编排。
     * 早期澄清结果不会触发模型调用；远程返回空响应时回退本地生成，远程异常则保留其错误语义给上层处理。
     */
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

    /**
     * 强制执行不依赖远程 Agent 的同步回退管线。
     * 保留该方法作为本地调用入口，实际公共流式降级由 {@link #localFallbackStream} 使用带上下文回调的重载。
     */
    private AgentQaResponse askWithLocalFallbackPipeline(AgentQaRequest request,
                                                          AuthCurrentUserVO currentUser) {
        return askWithPipeline(request, currentUser, false);
    }

    /**
     * 按统一管线完成范围解析、业务上下文装配、检索、生成和引用校验。
     * 供同步问答与本地流式降级共用，{@code allowRemoteAgent} 用于决定未知意图是否允许继续交给远程 Agent。
     */
    private AgentQaResponse askWithPipeline(AgentQaRequest request,
                                             AuthCurrentUserVO currentUser,
                                             boolean allowRemoteAgent) {
        return askWithPipeline(request, currentUser, allowRemoteAgent, ignored -> {
        });
    }

    /**
     * 统一同步问答管线的底层重载。
     * 上下文准备完成后通过 {@code contextConsumer} 暴露给流式事件构造；出现澄清结果时不会调用生成器。
     */
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

    /**
     * 在调用生成器之前完成意图识别、范围解析、业务授权和知识检索。
     *
     * <p>未知意图的本地回退请求会直接返回引导性回答；范围缺失或歧义则返回澄清响应。其余情形才创建
     * {@link AgentAnswerContext}，从而保证模型不会参与决定用户可访问的学校、资源或区域。</p>
     */
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

    /**
     * 将已准备的上下文与远程或本地生成结果组装为最终响应。
     *
     * <p>远程结果优先；远程未提供答案时调用本地生成器，生成异常则转换为可展示的降级回答。
     * 不论生成来源如何，引用均由 {@link CitationValidator} 根据本轮检索结果再次过滤。</p>
     */
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

    /**
     * 导出本轮实际生效的上下文摘要，供前端展示和排障。
     * 该摘要只包含已解析的范围、筛选条件和任务信息，不包含检索正文或账号敏感数据。
     */
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

    /**
     * 为学生模式生成后续探索入口。
     * 教师和管理员不返回该引导项，避免在其工作流中加入面向学生的资源浏览入口。
     */
    private List<Map<String, Object>> exploreSuggestions(AgentAnswerContext context) {
        if (!context.isStudentMode()) return new ArrayList<>();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        relatedResources(context).stream().limit(3).forEach(name -> {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("type", "RESOURCE"); item.put("title", "继续了解：" + name); suggestions.add(item);
        });
        Map<String, Object> map = new LinkedHashMap<>(); map.put("type", "MAP"); map.put("title", "查看学校周边智慧地图"); map.put("path", "/map"); suggestions.add(map);
        return suggestions;
    }

    /**
     * 校验问答请求并补全本轮 {@code clientTurnId}。
     *
     * <p>除问题、范围和筛选条件外，还限制图片附件数量、MIME 类型、Data URL 前缀和最大长度。
     * 该方法不做权限判断，权限必须在获得 {@link AuthCurrentUserVO} 后由范围解析阶段处理。</p>
     */
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

    /** 判断请求是否携带已通过格式校验的图片附件，用于直接选择资源解释意图。 */
    private boolean hasImageAttachments(AgentQaRequest request) {
        return request != null && request.getAttachments() != null && !request.getAttachments().isEmpty();
    }

    /**
     * 根据认证角色、显式范围及问题中提及的学校解析最终知识范围。
     *
     * <p>学校账号只能查询本校，且问题文本中不得包含其他学校；平台管理员可显式指定学校、区域或资源范围，
     * 未指定时仅在问题唯一命中学校时继续。范围缺失或学校名称歧义以澄清结果返回，绝不由模型猜测。</p>
     */
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

    /**
     * 在问题文本中匹配已登记学校名称。
     * 匹配前会去除空白、常见中英文标点并转小写，以降低自然语言输入格式差异造成的漏匹配。
     */
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

    /** 将学校摘要集合提取为非空名称列表，用作范围澄清时的候选项。 */
    private List<String> schoolNames(List<SchoolSummaryVO> schools) {
        return schools.stream()
                .map(SchoolSummaryVO::getSchoolName)
                .filter(StringUtils::hasText)
                .toList();
    }

    /** 规范化学校名称匹配文本，不改变原始业务字段或最终回答文本。 */
    private String normalizeForMatch(String value) {
        return value == null ? "" : value
                .replaceAll("\\s+", "")
                .replaceAll("[，。！？、；】【：‘’“”（）《》【】,.!?;:\"'()<>\\[\\]{}]", "")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 解析年级筛选条件。
     * 显式请求值优先；未传入时才从问题文本中按 {@link #GRADE_PATTERN} 推断。
     */
    private String resolveGrade(String requestedGrade, String question) {
        String explicitGrade = clean(requestedGrade);
        if (explicitGrade != null) {
            return explicitGrade;
        }
        Matcher matcher = GRADE_PATTERN.matcher(question == null ? "" : question);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 构造无需生成和检索的范围澄清响应。
     * 响应明确标记 {@code clarificationRequired}，使前端可以展示候选范围而不是将文本视为普通答案。
     */
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

    /**
     * 构造因未知意图等原因跳过检索和生成的响应。
     * 若范围已经确定，仍将其写入响应，避免调用方误以为请求未完成范围解析。
     */
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

    /**
     * 装配并校验与当前范围相关的业务实体。
     *
     * <p>学生模式额外验证资源已审核且归属本校，以及任务已发布、学生属于对应班级并具有任务进度记录；
     * 之后按学校、区域或资源范围填充地图详情和资源信息。这些校验先于检索执行，防止检索成为越权入口。</p>
     */
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

    /** 返回上下文已绑定的认证账号标识，供学生任务授权查询使用。 */
    private Long currentAccountId(AgentAnswerContext context) {
        // 学生归属已经由认证范围确定；prepareAnswer 中还会使用账号进一步约束任务查询。
        return context.getAccountId();
    }

    /**
     * 基于受限业务上下文发起知识检索。
     * 未识别意图不检索；检索实现异常被隔离为 {@link KnowledgeRetrievalStatus#DEGRADED}，以便回答链路仍可返回降级结果。
     */
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

    /**
     * 规范化检索器返回值，消除可选集合和状态字段的空值。
     * 当检索器未给出状态时，依据是否存在任何证据推导 {@code OK} 或 {@code EMPTY}，并刷新召回方法摘要。
     */
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

    /**
     * 汇集与当前回答相关的资源名称。
     * 依次保留当前资源、学校资源和区域标记名称，去重且每个来源最多补充八项，避免响应无限膨胀。
     */
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

    /**
     * 校验回答声明的引用，并在模型没有声明任何有效引用时尝试选取最多五条检索候选引用。
     * 所有返回引用均通过 {@link CitationValidator}，不会直接信任模型或远程运行时提供的标识。
     */
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

    /** 在学校详情的资源列表中找到问题文本明确提及的第一个资源，用于丰富学校范围上下文。 */
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

    /** 将客户端请求的召回数量限制到 {@link #DEFAULT_TOP_K} 至 {@link #MAX_TOP_K} 的有效区间。 */
    private Integer normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    /** 将可能为空的文本列表转换为可安全序列化的空列表。 */
    private List<String> nonNullList(List<String> values) {
        return values == null ? new ArrayList<>() : values;
    }

    /** 去除可选文本字段首尾空白；空白或空值统一视为未提供。 */
    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 已解析且可访问的知识范围，由范围类型和对应业务主键组成。 */
    private record Scope(KnowledgeScopeType type, Long id) {
    }

    /**
     * 生成前准备阶段的不可变结果。
     * {@code earlyResponse} 非空表示需要澄清或跳过，后续字段可为空且不得继续调用生成器。
     */
    private record AnswerPreparation(
            AgentQaResponse earlyResponse,
            AgentIntent intent,
            Scope scope,
            AgentAnswerContext context,
            KnowledgeRetrieveResult retrieval) {
    }

    /** 本地流式降级所需的完整响应及其原始上下文，以便补充检索阶段事件。 */
    private record LocalFallbackResult(
            AgentQaResponse response, AgentAnswerContext context) {
    }

    /**
     * 旧同步范围解析器的结果载体。
     * {@code scope} 为空表示调用方需要依据 {@code message} 和 {@code options} 补充或消除范围歧义。
     */
    private record ScopeResolution(Scope scope, String message, List<String> options) {

        /** 创建已成功解析的范围结果。 */
        private static ScopeResolution resolved(Scope scope) {
            return new ScopeResolution(scope, null, Collections.emptyList());
        }

        /** 创建需要用户澄清范围的结果，并携带可展示的候选项。 */
        private static ScopeResolution clarification(String message, List<String> options) {
            return new ScopeResolution(null, message, options);
        }

        /** 通过范围是否为空判断后续流程是否必须停止并返回澄清响应。 */
        private boolean requiresClarification() {
            return scope == null;
        }
    }
}
