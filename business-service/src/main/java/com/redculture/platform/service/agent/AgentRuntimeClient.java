package com.redculture.platform.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentAsyncConfiguration;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.vo.AgentGenerationStatus;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AgentActionVO;
import com.redculture.platform.vo.ai.AgentMemoryConflictPreview;
import com.redculture.platform.vo.ai.AgentMemoryItem;
import com.redculture.platform.vo.ai.AgentMemorySetting;
import com.redculture.platform.vo.ai.AssistantConversationDetail;
import com.redculture.platform.vo.ai.AssistantConversationSummary;
import com.redculture.platform.vo.ai.AssistantConversationTurnCancellation;
import com.redculture.platform.vo.ai.AssistantConversationTurnRecovery;
import com.redculture.platform.vo.ai.LlmModelOption;
import com.redculture.platform.vo.ai.StatefulAgentRequest;
import com.redculture.platform.vo.ai.StatefulAgentResponse;
import com.redculture.platform.vo.request.AgentMemoryCreateRequest;
import com.redculture.platform.vo.request.AgentMemoryUpdateRequest;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Component
public class AgentRuntimeClient {

    private static final ParameterizedTypeReference<Map<String, List<LlmModelOption>>> MODEL_MAP =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<AgentMemoryItem>> MEMORY_LIST =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<AssistantConversationSummary>> THREAD_LIST =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_EVENT =
            new ParameterizedTypeReference<>() { };

    private final WebClient webClient;
    private final Duration jsonTimeout;
    private final Duration streamIdleTimeout;
    private final ObjectMapper objectMapper;
    private final AppMapProperties appMapProperties;

    @Autowired
    public AgentRuntimeClient(AppMapProperties appMapProperties,
                              AgentProperties agentProperties,
                              ObjectMapper objectMapper,
                              @Qualifier("agentWebClient") WebClient webClient) {
        this.webClient = webClient;
        this.jsonTimeout = Duration.ofMillis(Math.max(1, agentProperties.getReadTimeoutMs()));
        this.streamIdleTimeout = Duration.ofMillis(Math.max(1L, agentProperties.getStreamTimeoutMs()));
        this.objectMapper = objectMapper;
        this.appMapProperties = appMapProperties;
    }

    /** 兼容不启动 Spring 容器的单元测试，生产运行始终注入共享 WebClient。 */
    public AgentRuntimeClient(AppMapProperties appMapProperties,
                              AgentProperties agentProperties,
                              ObjectMapper objectMapper) {
        this(
                appMapProperties,
                agentProperties,
                objectMapper,
                AgentAsyncConfiguration.createAgentWebClient(appMapProperties, agentProperties)
        );
    }

    /** Java 完成鉴权与可信上下文解析后，以非阻塞方式调用有状态 Agent。 */
    public Mono<AgentRuntimeResult> generate(AgentQaRequest request,
                                             AuthCurrentUserVO user,
                                             AgentAnswerContext context) {
        if (!appMapProperties.isAgentRuntimeEnabled()
                || !StringUtils.hasText(appMapProperties.getLlmServiceBaseUrl())) {
            return Mono.empty();
        }
        return send(chatRequest(request, user, context)).map(this::toRuntimeResult);
    }

    public Flux<StreamEvent> streamStateful(AgentQaRequest request,
                                             AuthCurrentUserVO user,
                                             AgentAnswerContext context) {
        return stream(chatRequest(request, user, context));
    }

    public Mono<StatefulAgentResponse> send(StatefulAgentRequest request) {
        ensureClientTurnId(request);
        return json(
                webClient.post()
                        .uri("/agent/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(request),
                StatefulAgentResponse.class
        ).flatMap(response -> StringUtils.hasText(response.getAnswer())
                ? Mono.just(response)
                : Mono.error(emptyResponse("agent_empty_response")));
    }

    public Mono<List<LlmModelOption>> listModels() {
        return json(
                webClient.get().uri("/models").accept(MediaType.APPLICATION_JSON),
                MODEL_MAP
        ).map(response -> response.get("models") == null
                        ? List.<LlmModelOption>of() : response.get("models"))
                .defaultIfEmpty(List.<LlmModelOption>of());
    }

    public Mono<AgentMemorySetting> getMemorySetting(
            String ownerId, String scopeType, Long scopeId) {
        return json(
                webClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/agent/memory-settings")
                                .queryParam("ownerId", ownerId)
                                .queryParam("scopeType", scopeType)
                                .queryParam("scopeId", scopeId)
                                .build())
                        .accept(MediaType.APPLICATION_JSON),
                AgentMemorySetting.class
        );
    }

    public Mono<AgentMemorySetting> updateMemorySetting(
            String ownerId, String scopeType, Long scopeId, boolean enabled) {
        Map<String, Object> body = scopeBody(ownerId, scopeType, scopeId);
        body.put("enabled", enabled);
        return json(
                webClient.put()
                        .uri("/agent/memory-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body),
                AgentMemorySetting.class
        );
    }

    public Mono<List<AgentMemoryItem>> listMemories(
            String ownerId,
            String scopeType,
            Long scopeId,
            String status,
            String memoryType) {
        return json(
                webClient.get()
                        .uri(uriBuilder -> {
                            UriBuilder builder = uriBuilder.path("/agent/memories")
                                    .queryParam("ownerId", ownerId)
                                    .queryParam("scopeType", scopeType)
                                    .queryParam("scopeId", scopeId);
                            if (StringUtils.hasText(status)) {
                                builder.queryParam("status", status);
                            }
                            if (StringUtils.hasText(memoryType)) {
                                builder.queryParam("memoryType", memoryType);
                            }
                            return builder.build();
                        })
                        .accept(MediaType.APPLICATION_JSON),
                MEMORY_LIST
        ).defaultIfEmpty(List.of());
    }

    public Mono<AgentMemoryItem> createMemory(
            String ownerId,
            String scopeType,
            Long scopeId,
            AgentMemoryCreateRequest request) {
        Map<String, Object> body = scopeBody(ownerId, scopeType, scopeId);
        body.put("memoryType", request.getMemoryType());
        body.put("fieldKey", request.getFieldKey());
        body.put("content", request.getContent());
        body.put("status", "active");
        body.put("source", "profile_ui");
        body.put("replaceConflicts", Boolean.TRUE.equals(request.getReplaceConflicts()));
        return memoryConflictAware(json(
                webClient.post()
                        .uri("/agent/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body),
                AgentMemoryItem.class
        ));
    }

    public Mono<AgentMemoryConflictPreview> getMemoryConfirmationPreview(
            String memoryId, String ownerId, String scopeType, Long scopeId) {
        return memoryConflictAware(json(
                webClient.get()
                        .uri(uriBuilder -> scopedMemoryUri(
                                uriBuilder,
                                "/agent/memories/{memoryId}/confirmation-preview",
                                memoryId,
                                ownerId,
                                scopeType,
                                scopeId))
                        .accept(MediaType.APPLICATION_JSON),
                AgentMemoryConflictPreview.class
        ));
    }

    public Mono<AgentMemoryItem> updateMemory(
            String memoryId,
            String ownerId,
            String scopeType,
            Long scopeId,
            AgentMemoryUpdateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request.getMemoryType() != null) {
            body.put("memoryType", request.getMemoryType());
        }
        if (request.getFieldKey() != null) {
            body.put("fieldKey", request.getFieldKey());
        }
        if (request.getContent() != null) {
            body.put("content", request.getContent());
        }
        body.put("replaceConflicts", Boolean.TRUE.equals(request.getReplaceConflicts()));
        return sendMemoryPatch(
                "/agent/memories/{memoryId}",
                memoryId,
                ownerId,
                scopeType,
                scopeId,
                body
        );
    }

    public Mono<AgentMemoryItem> confirmMemory(
            String memoryId, String ownerId, String scopeType, Long scopeId) {
        return confirmMemory(memoryId, ownerId, scopeType, scopeId, false);
    }

    public Mono<AgentMemoryItem> confirmMemory(
            String memoryId,
            String ownerId,
            String scopeType,
            Long scopeId,
            boolean replaceConflicts) {
        return postMemoryAction(
                "/agent/memories/{memoryId}/confirm",
                memoryId,
                ownerId,
                scopeType,
                scopeId,
                replaceConflicts
        );
    }

    public Mono<AgentMemoryItem> deleteMemory(
            String memoryId, String ownerId, String scopeType, Long scopeId) {
        return json(
                webClient.delete()
                        .uri(uriBuilder -> scopedMemoryUri(
                                uriBuilder,
                                "/agent/memories/{memoryId}",
                                memoryId,
                                ownerId,
                                scopeType,
                                scopeId))
                        .accept(MediaType.APPLICATION_JSON),
                AgentMemoryItem.class
        );
    }

    public Mono<AgentMemoryItem> restoreMemory(
            String memoryId, String ownerId, String scopeType, Long scopeId) {
        return restoreMemory(memoryId, ownerId, scopeType, scopeId, false);
    }

    public Mono<AgentMemoryItem> restoreMemory(
            String memoryId,
            String ownerId,
            String scopeType,
            Long scopeId,
            boolean replaceConflicts) {
        return postMemoryAction(
                "/agent/memories/{memoryId}/restore",
                memoryId,
                ownerId,
                scopeType,
                scopeId,
                replaceConflicts
        );
    }

    public Mono<Void> permanentlyDeleteMemory(
            String memoryId, String ownerId, String scopeType, Long scopeId) {
        return noContent(webClient.delete().uri(uriBuilder -> scopedMemoryUri(
                uriBuilder,
                "/agent/memories/{memoryId}/permanent",
                memoryId,
                ownerId,
                scopeType,
                scopeId
        )));
    }

    public Mono<List<AssistantConversationSummary>> listConversations(
            String ownerId, String scopeType, Long scopeId) {
        return listConversations(ownerId, scopeType, scopeId, "active");
    }

    public Mono<List<AssistantConversationSummary>> listConversations(
            String ownerId, String scopeType, Long scopeId, String status) {
        return json(
                webClient.get().uri(uriBuilder -> uriBuilder.path("/agent/threads")
                        .queryParam("ownerId", ownerId)
                        .queryParam("taskType", "CHAT")
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .queryParam("limit", 50)
                        .queryParam("status", status)
                        .build()),
                THREAD_LIST
        ).defaultIfEmpty(List.of());
    }

    public Mono<AssistantConversationDetail> getConversation(
            String threadId, String ownerId, String scopeType, Long scopeId) {
        return required(json(
                webClient.get().uri(uriBuilder -> uriBuilder.path("/agent/threads/{threadId}")
                        .queryParam("ownerId", ownerId)
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .build(threadId)),
                AssistantConversationDetail.class
        ), "agent_conversation_empty");
    }

    public Mono<AssistantConversationTurnRecovery> recoverConversationTurn(
            String clientTurnId, String ownerId, String scopeType, Long scopeId) {
        return required(json(
                webClient.get().uri(uriBuilder -> uriBuilder
                        .path("/agent/messages/recovery/{clientTurnId}")
                        .queryParam("ownerId", ownerId)
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .build(clientTurnId)),
                AssistantConversationTurnRecovery.class
        ), "agent_turn_recovery_empty");
    }

    public Mono<AssistantConversationTurnCancellation> cancelConversationTurn(
            String clientTurnId, String ownerId, String scopeType, Long scopeId) {
        return required(json(
                webClient.post().uri(uriBuilder -> uriBuilder
                        .path("/agent/turns/{clientTurnId}/cancel")
                        .queryParam("ownerId", ownerId)
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .build(clientTurnId))
                        .accept(MediaType.APPLICATION_JSON),
                AssistantConversationTurnCancellation.class
        ), "agent_turn_cancellation_empty");
    }

    public Mono<AgentActionVO> getAction(
            String actionId, String ownerId, String scopeType, Long scopeId) {
        return required(json(
                webClient.get().uri(uriBuilder -> uriBuilder.path("/agent/actions/{actionId}")
                        .queryParam("ownerId", ownerId)
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .build(actionId))
                        .accept(MediaType.APPLICATION_JSON),
                AgentActionVO.class
        ), "agent_action_empty");
    }

    public Mono<AgentActionVO> decideAction(
            String actionId,
            String decision,
            String ownerId,
            String scopeType,
            Long scopeId) {
        Map<String, Object> body = scopeBody(ownerId, scopeType, scopeId);
        body.put("decision", decision);
        return required(json(
                webClient.post()
                        .uri("/agent/actions/{actionId}/decision", actionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body),
                AgentActionVO.class
        ), "agent_action_decision_empty");
    }

    public Mono<Void> archiveConversation(
            String threadId, String ownerId, String scopeType, Long scopeId) {
        return noContent(webClient.post().uri(uriBuilder -> uriBuilder
                .path("/agent/threads/{threadId}/archive")
                .queryParam("ownerId", ownerId)
                .queryParam("scopeType", scopeType)
                .queryParam("scopeId", scopeId)
                .build(threadId)));
    }

    public Mono<Void> restoreConversation(
            String threadId, String ownerId, String scopeType, Long scopeId) {
        return noContent(webClient.post().uri(uriBuilder -> uriBuilder
                .path("/agent/threads/{threadId}/restore")
                .queryParam("ownerId", ownerId)
                .queryParam("scopeType", scopeType)
                .queryParam("scopeId", scopeId)
                .build(threadId)));
    }

    public Flux<StreamEvent> stream(StatefulAgentRequest request) {
        ensureClientTurnId(request);
        return webClient.post()
                .uri("/agent/messages/stream")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .exchangeToFlux(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMapMany(body -> Flux.error(upstreamError(
                                        response.statusCode().value(), body
                                )));
                    }
                    return response.bodyToFlux(SSE_EVENT);
                })
                .timeout(streamIdleTimeout)
                .map(this::decodeEvent)
                .onErrorMap(this::isTransportError, this::transportError);
    }

    public Mono<Void> archive(String threadId, String ownerId) {
        return noContent(webClient.post().uri(uriBuilder -> uriBuilder
                .path("/agent/threads/{threadId}/archive")
                .queryParam("ownerId", ownerId)
                .build(threadId)))
                .onErrorResume(ignored -> Mono.empty());
    }

    public String ownerIdFor(AuthCurrentUserVO user) {
        if (user.getAccountId() != null) {
            return "account:" + user.getAccountId();
        }
        return "username:" + (user.getUsername() == null ? "unknown" : user.getUsername());
    }

    public StatefulAgentRequest chatRequest(AgentQaRequest request,
                                            AuthCurrentUserVO user,
                                            AgentAnswerContext context) {
        StatefulAgentRequest body = new StatefulAgentRequest();
        body.setOwnerId(ownerIdFor(user));
        body.setScopeType(context.getScopeType().name());
        body.setScopeId(context.getScopeId());
        body.setThreadId(request.getThreadId());
        if (!StringUtils.hasText(request.getClientTurnId())) {
            request.setClientTurnId(UUID.randomUUID().toString());
        }
        body.setClientTurnId(request.getClientTurnId());
        body.setModelId(request.getModelId());
        body.setTaskType("CHAT");
        body.setMessage(context.getQuestion());
        body.setAttachments(request.getAttachments());
        body.setGrade(context.getGrade());
        body.setTheme(context.getTheme());
        body.setIntent(context.getIntent() == null ? null : context.getIntent().name());
        body.setContext(trustedContext(context));
        return body;
    }

    public StatefulAgentRequest taskRequest(String ownerId,
                                            String scopeType,
                                            Long scopeId,
                                            String threadId,
                                            String taskType,
                                            String message,
                                            Map<String, Object> taskPayload,
                                            Map<String, Object> context) {
        StatefulAgentRequest request = new StatefulAgentRequest();
        request.setOwnerId(ownerId);
        request.setScopeType(scopeType);
        request.setScopeId(scopeId);
        request.setThreadId(threadId);
        request.setClientTurnId(UUID.randomUUID().toString());
        request.setTaskType(taskType);
        request.setMessage(message);
        request.setTaskPayload(taskPayload == null ? new LinkedHashMap<>() : taskPayload);
        request.setContext(context == null ? new LinkedHashMap<>() : context);
        return request;
    }

    private AgentRuntimeResult toRuntimeResult(StatefulAgentResponse response) {
        List<String> citationIds = response.getCitations() == null ? new ArrayList<>()
                : response.getCitations().stream()
                .map(item -> item.getCitationId())
                .filter(StringUtils::hasText)
                .toList();
        List<String> followUps = response.getFollowUpQuestions() == null
                ? new ArrayList<>() : response.getFollowUpQuestions();
        List<String> toolNames = response.getToolExecutions() == null ? new ArrayList<>()
                : response.getToolExecutions().stream()
                .map(StatefulAgentResponse.ToolExecutionResponse::getName)
                .filter(StringUtils::hasText)
                .toList();
        return new AgentRuntimeResult(
                new GeneratedAnswer(
                        response.getAnswer(),
                        citationIds,
                        followUps,
                        generationStatus(response)
                ),
                response.getThreadId(),
                response.getStatus(),
                toolNames,
                response.getDegradedReason(),
                response.getMemoryCandidates() == null
                        ? new ArrayList<>() : response.getMemoryCandidates(),
                response.getMemoryApplied(),
                response.getRetrievalMethods() == null
                        ? new ArrayList<>() : response.getRetrievalMethods(),
                response.getProvider(),
                response.getModel(),
                response.getFallbackLevel()
        );
    }

    private void ensureClientTurnId(StatefulAgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("agent request is required");
        }
        if (!StringUtils.hasText(request.getClientTurnId())) {
            request.setClientTurnId(UUID.randomUUID().toString());
        }
    }

    private Map<String, Object> scopeBody(String ownerId, String scopeType, Long scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerId", ownerId);
        body.put("scopeType", scopeType);
        body.put("scopeId", scopeId);
        return body;
    }

    private Mono<AgentMemoryItem> postMemoryAction(
            String path,
            String memoryId,
            String ownerId,
            String scopeType,
            Long scopeId,
            boolean replaceConflicts) {
        return memoryConflictAware(json(
                webClient.post()
                        .uri(uriBuilder -> scopedMemoryUri(
                                uriBuilder, path, memoryId, ownerId, scopeType, scopeId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("replaceConflicts", replaceConflicts)),
                AgentMemoryItem.class
        ));
    }

    private URI scopedMemoryUri(
            UriBuilder uriBuilder,
            String path,
            String memoryId,
            String ownerId,
            String scopeType,
            Long scopeId) {
        return uriBuilder.path(path)
                .queryParam("ownerId", ownerId)
                .queryParam("scopeType", scopeType)
                .queryParam("scopeId", scopeId)
                .build(memoryId);
    }

    private Mono<AgentMemoryItem> sendMemoryPatch(
            String path,
            String memoryId,
            String ownerId,
            String scopeType,
            Long scopeId,
            Map<String, Object> body) {
        return memoryConflictAware(json(
                webClient.patch()
                        .uri(uriBuilder -> scopedMemoryUri(
                                uriBuilder, path, memoryId, ownerId, scopeType, scopeId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body),
                AgentMemoryItem.class
        ));
    }

    private <T> Mono<T> memoryConflictAware(Mono<T> source) {
        return source.onErrorMap(
                error -> error instanceof AgentUpstreamException upstream
                        && upstream.getStatusCode() == 409,
                error -> memoryConflictException(((AgentUpstreamException) error).responseBody())
        );
    }

    private AgentMemoryConflictException memoryConflictException(String payload) {
        try {
            JsonNode detail = objectMapper.readTree(payload).path("detail");
            String message = detail.path("message").asText("记忆字段发生冲突，请先确认是否替换");
            AgentMemoryConflictPreview preview = detail.has("preview")
                    ? objectMapper.treeToValue(
                            detail.path("preview"),
                            AgentMemoryConflictPreview.class
                    )
                    : null;
            return new AgentMemoryConflictException(message, preview);
        } catch (JsonProcessingException exception) {
            return new AgentMemoryConflictException("记忆字段发生冲突，请先确认是否替换", null);
        }
    }

    private AgentGenerationStatus generationStatus(StatefulAgentResponse response) {
        if (StringUtils.hasText(response.getGenerationStatus())) {
            try {
                return AgentGenerationStatus.from(response.getGenerationStatus());
            } catch (IllegalArgumentException ignored) {
                // 远端扩展状态继续按顶层 status 归一化。
            }
        }
        return "degraded".equalsIgnoreCase(response.getStatus())
                ? AgentGenerationStatus.DEGRADED : AgentGenerationStatus.COMPLETED;
    }

    private Map<String, Object> trustedContext(AgentAnswerContext context) {
        Map<String, Object> trusted = new LinkedHashMap<>();
        if (context.getSchoolDetail() != null) {
            trusted.put("school", context.getSchoolDetail().getSchool());
            trusted.put("resources", context.getSchoolDetail().getResources());
        }
        trusted.put("region", context.getRegionDetail());
        trusted.put("resource", context.getResource());
        trusted.put("retrieval", context.getRetrieval());
        trusted.put("citationCandidates", context.getRetrieval() == null
                ? List.of() : context.getRetrieval().getCitationCandidates());
        return trusted;
    }

    private StreamEvent decodeEvent(ServerSentEvent<String> event) {
        String eventName = StringUtils.hasText(event.event()) ? event.event() : "message";
        if (!StringUtils.hasText(event.data())) {
            return new StreamEvent(eventName, Collections.emptyMap());
        }
        try {
            Map<String, Object> data = objectMapper.readValue(
                    event.data(),
                    new TypeReference<LinkedHashMap<String, Object>>() { }
            );
            return new StreamEvent(eventName, data);
        } catch (JsonProcessingException exception) {
            throw new AgentUpstreamException(
                    502,
                    "agent_stream_invalid_event",
                    true,
                    "",
                    exception
            );
        }
    }

    private <T> Mono<T> json(WebClient.RequestHeadersSpec<?> request, Class<T> type) {
        return withJsonTimeout(request.exchangeToMono(response -> decode(response, type)));
    }

    private <T> Mono<T> json(
            WebClient.RequestHeadersSpec<?> request,
            ParameterizedTypeReference<T> type) {
        return withJsonTimeout(request.exchangeToMono(response -> decode(response, type)));
    }

    private <T> Mono<T> decode(ClientResponse response, Class<T> type) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(type);
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(upstreamError(response.statusCode().value(), body)));
    }

    private <T> Mono<T> decode(
            ClientResponse response,
            ParameterizedTypeReference<T> type) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(type);
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(upstreamError(response.statusCode().value(), body)));
    }

    private Mono<Void> noContent(WebClient.RequestHeadersSpec<?> request) {
        return withJsonTimeout(request.exchangeToMono(response -> {
            if (response.statusCode().is2xxSuccessful()) {
                return response.releaseBody();
            }
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(upstreamError(
                            response.statusCode().value(), body
                    )));
        }));
    }

    private <T> Mono<T> required(Mono<T> source, String code) {
        return source.switchIfEmpty(Mono.error(emptyResponse(code)));
    }

    private <T> Mono<T> withJsonTimeout(Mono<T> source) {
        return source.timeout(jsonTimeout)
                .onErrorMap(this::isTransportError, this::transportError);
    }

    private boolean isTransportError(Throwable error) {
        return error instanceof TimeoutException || error instanceof WebClientRequestException;
    }

    private Throwable transportError(Throwable error) {
        if (error instanceof TimeoutException) {
            return new AgentUpstreamException(504, "agent_timeout", true, "", error);
        }
        return new AgentUpstreamException(503, "agent_unavailable", true, "", error);
    }

    private AgentUpstreamException emptyResponse(String code) {
        return new AgentUpstreamException(502, code, true, "", null);
    }

    private AgentUpstreamException upstreamError(int statusCode, String body) {
        String code = upstreamCode(body);
        return new AgentUpstreamException(
                statusCode,
                code,
                statusCode == 408 || statusCode == 429 || statusCode >= 500,
                body,
                null
        );
    }

    private String upstreamCode(String body) {
        if (StringUtils.hasText(body)) {
            try {
                JsonNode root = objectMapper.readTree(body);
                String code = root.path("detail").path("code").asText("");
                if (!StringUtils.hasText(code)) {
                    code = root.path("code").asText("");
                }
                if (StringUtils.hasText(code)) {
                    return code;
                }
            } catch (JsonProcessingException ignored) {
                // 非 JSON 错误正文不向调用方透传。
            }
        }
        return "agent_upstream_error";
    }

    public record StreamEvent(String event, Map<String, Object> data) {

        public Map<String, Object> safeData() {
            return data == null ? Collections.emptyMap() : data;
        }
    }
}
