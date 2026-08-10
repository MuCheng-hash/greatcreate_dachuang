package com.redculture.platform.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.AgentGenerationStatus;
import com.redculture.platform.vo.ai.StatefulAgentRequest;
import com.redculture.platform.vo.ai.StatefulAgentResponse;
import com.redculture.platform.vo.ai.AgentMemoryItem;
import com.redculture.platform.vo.ai.AgentMemoryConflictPreview;
import com.redculture.platform.vo.ai.AgentMemorySetting;
import com.redculture.platform.vo.ai.LlmModelOption;
import com.redculture.platform.vo.ai.AssistantConversationDetail;
import com.redculture.platform.vo.ai.AssistantConversationTurnCancellation;
import com.redculture.platform.vo.ai.AssistantConversationTurnRecovery;
import com.redculture.platform.vo.ai.AssistantConversationSummary;
import com.redculture.platform.vo.request.AgentQaRequest;
import com.redculture.platform.vo.request.AgentMemoryCreateRequest;
import com.redculture.platform.vo.request.AgentMemoryUpdateRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class AgentRuntimeClient {

    private final RestClient restClient;
    private final HttpClient patchHttpClient;
    private final Duration patchRequestTimeout;
    private final ObjectMapper objectMapper;
    private final AppMapProperties appMapProperties;
    private final String internalServiceToken;

    public AgentRuntimeClient(AppMapProperties appMapProperties,
                              AgentProperties agentProperties,
                              ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(appMapProperties.getLlmServiceBaseUrl())
                .requestFactory(requestFactory(agentProperties))
                .build();
        this.patchHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(
                        1, agentProperties.getConnectTimeoutMs()
                )))
                .build();
        this.patchRequestTimeout = Duration.ofMillis(Math.max(
                1L,
                Math.max(
                        agentProperties.getReadTimeoutMs(),
                        agentProperties.getStreamTimeoutMs()
                )
        ));
        this.objectMapper = objectMapper;
        this.appMapProperties = appMapProperties;
        this.internalServiceToken = agentProperties.getInternalServiceToken();
    }

    /** Calls the stateful runtime after Java has resolved authorization and trusted context. */
    public AgentRuntimeResult generate(AgentQaRequest request,
                                       AuthCurrentUserVO user,
                                       AgentAnswerContext context) {
        if (!appMapProperties.isAgentRuntimeEnabled()
                || !StringUtils.hasText(appMapProperties.getLlmServiceBaseUrl())) {
            return null;
        }
        StatefulAgentRequest body = chatRequest(request, user, context);
        StatefulAgentResponse response = send(body);
        List<String> citationIds = response.getCitations() == null ? new ArrayList<>()
                : response.getCitations().stream()
                .map(item -> item.getCitationId())
                .filter(StringUtils::hasText)
                .toList();
        List<String> followUps = response.getFollowUpQuestions() == null ? new ArrayList<>()
                : response.getFollowUpQuestions();
        List<String> toolNames = response.getToolExecutions() == null ? new ArrayList<>()
                : response.getToolExecutions().stream()
                .map(StatefulAgentResponse.ToolExecutionResponse::getName)
                .filter(StringUtils::hasText)
                .toList();
        AgentGenerationStatus generationStatus = generationStatus(response);
        return new AgentRuntimeResult(
                new GeneratedAnswer(response.getAnswer(), citationIds, followUps, generationStatus),
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

    /** Calls the stateful SSE endpoint after Java has resolved authorization and trusted context. */
    public void streamStateful(AgentQaRequest request,
                               AuthCurrentUserVO user,
                               AgentAnswerContext context,
                               Consumer<StreamEvent> consumer) {
        stream(chatRequest(request, user, context), consumer);
    }

    public StatefulAgentResponse send(StatefulAgentRequest request) {
        ensureClientTurnId(request);
        StatefulAgentResponse response = restClient.post()
                .uri("/agent/messages")
                .headers(this::applyInternalServiceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(StatefulAgentResponse.class);
        if (response == null || !StringUtils.hasText(response.getAnswer())) {
            throw new IllegalStateException("stateful agent returned an empty response");
        }
        return response;
    }

    public List<LlmModelOption> listModels() {
        Map<String, List<LlmModelOption>> response = restClient.get()
                .uri("/models")
                .headers(this::applyInternalServiceToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return response == null || response.get("models") == null
                ? List.of() : response.get("models");
    }

    public AgentMemorySetting getMemorySetting(
            String ownerId, String scopeType, Long scopeId) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/agent/memory-settings")
                        .queryParam("ownerId", ownerId)
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .build())
                .headers(this::applyInternalServiceToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(AgentMemorySetting.class);
    }

    public AgentMemorySetting updateMemorySetting(
            String ownerId, String scopeType, Long scopeId, boolean enabled) {
        Map<String, Object> body = scopeBody(ownerId, scopeType, scopeId);
        body.put("enabled", enabled);
        return restClient.put()
                .uri("/agent/memory-settings")
                .headers(this::applyInternalServiceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(AgentMemorySetting.class);
    }

    public List<AgentMemoryItem> listMemories(
            String ownerId,
            String scopeType,
            Long scopeId,
            String status,
            String memoryType) {
        List<AgentMemoryItem> response = restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/agent/memories")
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
                .headers(this::applyInternalServiceToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return response == null ? List.of() : response;
    }

    public AgentMemoryItem createMemory(
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
        try {
            return restClient.post()
                    .uri("/agent/memories")
                    .headers(this::applyInternalServiceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(AgentMemoryItem.class);
        } catch (RestClientResponseException exception) {
            throw translateMemoryConflict(exception);
        }
    }

    public AgentMemoryConflictPreview getMemoryConfirmationPreview(
            String memoryId, String ownerId, String scopeType, Long scopeId) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> scopedMemoryUri(
                            uriBuilder,
                            "/agent/memories/{memoryId}/confirmation-preview",
                            memoryId,
                            ownerId,
                            scopeType,
                            scopeId))
                    .headers(this::applyInternalServiceToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(AgentMemoryConflictPreview.class);
        } catch (RestClientResponseException exception) {
            throw translateMemoryConflict(exception);
        }
    }

    public AgentMemoryItem updateMemory(
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

    public AgentMemoryItem confirmMemory(
            String memoryId, String ownerId, String scopeType, Long scopeId) {
        return confirmMemory(memoryId, ownerId, scopeType, scopeId, false);
    }

    public AgentMemoryItem confirmMemory(
            String memoryId,
            String ownerId,
            String scopeType,
            Long scopeId,
            boolean replaceConflicts) {
        return postMemoryAction(
                "/agent/memories/{memoryId}/confirm",
                memoryId, ownerId, scopeType, scopeId, replaceConflicts);
    }

    public AgentMemoryItem deleteMemory(
            String memoryId, String ownerId, String scopeType, Long scopeId) {
        return restClient.delete()
                .uri(uriBuilder -> scopedMemoryUri(
                        uriBuilder, "/agent/memories/{memoryId}",
                        memoryId, ownerId, scopeType, scopeId))
                .headers(this::applyInternalServiceToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(AgentMemoryItem.class);
    }

    public AgentMemoryItem restoreMemory(
            String memoryId, String ownerId, String scopeType, Long scopeId) {
        return restoreMemory(memoryId, ownerId, scopeType, scopeId, false);
    }

    public AgentMemoryItem restoreMemory(
            String memoryId,
            String ownerId,
            String scopeType,
            Long scopeId,
            boolean replaceConflicts) {
        return postMemoryAction(
                "/agent/memories/{memoryId}/restore",
                memoryId, ownerId, scopeType, scopeId, replaceConflicts);
    }

    public void permanentlyDeleteMemory(
            String memoryId, String ownerId, String scopeType, Long scopeId) {
        restClient.delete()
                .uri(uriBuilder -> scopedMemoryUri(
                        uriBuilder, "/agent/memories/{memoryId}/permanent",
                        memoryId, ownerId, scopeType, scopeId))
                .headers(this::applyInternalServiceToken)
                .retrieve()
                .toBodilessEntity();
    }

    public List<AssistantConversationSummary> listConversations(
            String ownerId, String scopeType, Long scopeId) {
        return listConversations(ownerId, scopeType, scopeId, "active");
    }

    public List<AssistantConversationSummary> listConversations(
            String ownerId, String scopeType, Long scopeId, String status) {
        List<AssistantConversationSummary> response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/agent/threads")
                        .queryParam("ownerId", ownerId)
                        .queryParam("taskType", "CHAT")
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .queryParam("limit", 50)
                        .queryParam("status", status)
                        .build())
                .headers(this::applyInternalServiceToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return response == null ? List.of() : response;
    }

    public AssistantConversationDetail getConversation(
            String threadId, String ownerId, String scopeType, Long scopeId) {
        AssistantConversationDetail response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/agent/threads/{threadId}")
                        .queryParam("ownerId", ownerId)
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .build(threadId))
                .headers(this::applyInternalServiceToken)
                .retrieve()
                .body(AssistantConversationDetail.class);
        if (response == null) {
            throw new IllegalStateException("agent conversation response is empty");
        }
        return response;
    }

    public AssistantConversationTurnRecovery recoverConversationTurn(
            String clientTurnId, String ownerId, String scopeType, Long scopeId) {
        AssistantConversationTurnRecovery response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/agent/messages/recovery/{clientTurnId}")
                        .queryParam("ownerId", ownerId)
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .build(clientTurnId))
                .headers(this::applyInternalServiceToken)
                .retrieve()
                .body(AssistantConversationTurnRecovery.class);
        if (response == null) {
            throw new IllegalStateException("agent turn recovery response is empty");
        }
        return response;
    }

    public AssistantConversationTurnCancellation cancelConversationTurn(
            String clientTurnId, String ownerId, String scopeType, Long scopeId) {
        AssistantConversationTurnCancellation response = restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/agent/turns/{clientTurnId}/cancel")
                        .queryParam("ownerId", ownerId)
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .build(clientTurnId))
                .headers(this::applyInternalServiceToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(AssistantConversationTurnCancellation.class);
        if (response == null) {
            throw new IllegalStateException("agent turn cancellation response is empty");
        }
        return response;
    }

    public void archiveConversation(String threadId, String ownerId, String scopeType, Long scopeId) {
        restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/agent/threads/{threadId}/archive")
                        .queryParam("ownerId", ownerId)
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .build(threadId))
                .headers(this::applyInternalServiceToken)
                .retrieve()
                .toBodilessEntity();
    }

    public void restoreConversation(String threadId, String ownerId, String scopeType, Long scopeId) {
        restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/agent/threads/{threadId}/restore")
                        .queryParam("ownerId", ownerId)
                        .queryParam("scopeType", scopeType)
                        .queryParam("scopeId", scopeId)
                        .build(threadId))
                .headers(this::applyInternalServiceToken)
                .retrieve()
                .toBodilessEntity();
    }

    public void stream(StatefulAgentRequest request, Consumer<StreamEvent> consumer) {
        ensureClientTurnId(request);
        restClient.post()
                .uri("/agent/messages/stream")
                .headers(this::applyInternalServiceToken)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(request)
                .exchange((clientRequest, clientResponse) -> {
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException(
                                "agent stream HTTP " + clientResponse.getStatusCode().value()
                        );
                    }
                    readEvents(clientResponse.getBody(), consumer);
                    return null;
                });
    }

    public void archive(String threadId, String ownerId) {
        try {
            restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/agent/threads/{threadId}/archive")
                            .queryParam("ownerId", ownerId)
                            .build(threadId))
                    .headers(this::applyInternalServiceToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ignored) {
            // 归档失败不影响资源发现结果，审计数据仍保留在 Agent 线程中。
        }
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

    private void ensureClientTurnId(StatefulAgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("agent request is required");
        }
        if (!StringUtils.hasText(request.getClientTurnId())) {
            request.setClientTurnId(UUID.randomUUID().toString());
        }
    }

    private Map<String, Object> scopeBody(
            String ownerId, String scopeType, Long scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerId", ownerId);
        body.put("scopeType", scopeType);
        body.put("scopeId", scopeId);
        return body;
    }

    private AgentMemoryItem postMemoryAction(
            String path,
            String memoryId,
            String ownerId,
            String scopeType,
            Long scopeId,
            boolean replaceConflicts) {
        try {
            return restClient.post()
                    .uri(uriBuilder -> scopedMemoryUri(
                            uriBuilder, path, memoryId, ownerId, scopeType, scopeId))
                    .headers(this::applyInternalServiceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("replaceConflicts", replaceConflicts))
                    .retrieve()
                    .body(AgentMemoryItem.class);
        } catch (RestClientResponseException exception) {
            throw translateMemoryConflict(exception);
        }
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

    private AgentMemoryItem sendMemoryPatch(
            String path,
            String memoryId,
            String ownerId,
            String scopeType,
            Long scopeId,
            Map<String, Object> body) {
        URI uri = UriComponentsBuilder
                .fromUriString(appMapProperties.getLlmServiceBaseUrl())
                .path(path)
                .queryParam("ownerId", ownerId)
                .queryParam("scopeType", scopeType)
                .queryParam("scopeId", scopeId)
                .buildAndExpand(memoryId)
                .encode()
                .toUri();
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(patchRequestTimeout)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .method(
                            "PATCH",
                            HttpRequest.BodyPublishers.ofString(
                                    objectMapper.writeValueAsString(body),
                                    StandardCharsets.UTF_8
                            )
                    );
            if (StringUtils.hasText(internalServiceToken)) {
                requestBuilder.header("X-Agent-Service-Token", internalServiceToken);
            }
            HttpResponse<String> response = patchHttpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() == 409) {
                throw memoryConflictException(response.body());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "agent memory PATCH HTTP " + response.statusCode()
                );
            }
            return objectMapper.readValue(response.body(), AgentMemoryItem.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("agent memory PATCH interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("agent memory PATCH failed", exception);
        }
    }

    private RuntimeException translateMemoryConflict(RestClientResponseException exception) {
        if (exception.getStatusCode().value() != 409) {
            return exception;
        }
        return memoryConflictException(exception.getResponseBodyAsString());
    }

    private AgentMemoryConflictException memoryConflictException(String payload) {
        try {
            JsonNode detail = objectMapper.readTree(payload).path("detail");
            String message = detail.path("message").asText("记忆字段发生冲突，请先确认是否替换");
            AgentMemoryConflictPreview preview = detail.has("preview")
                    ? objectMapper.treeToValue(detail.path("preview"), AgentMemoryConflictPreview.class)
                    : null;
            return new AgentMemoryConflictException(message, preview);
        } catch (JsonProcessingException exception) {
            return new AgentMemoryConflictException("记忆字段发生冲突，请先确认是否替换", null);
        }
    }

    private void applyInternalServiceToken(HttpHeaders headers) {
        if (StringUtils.hasText(internalServiceToken)) {
            headers.set("X-Agent-Service-Token", internalServiceToken);
        }
    }

    private AgentGenerationStatus generationStatus(StatefulAgentResponse response) {
        if (StringUtils.hasText(response.getGenerationStatus())) {
            try {
                return AgentGenerationStatus.from(response.getGenerationStatus());
            } catch (IllegalArgumentException ignored) {
                // 非标准状态按顶层 status 继续归一化，避免远端扩展字段导致整次问答失败。
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

    private void readEvents(InputStream inputStream, Consumer<StreamEvent> consumer) {
        if (inputStream == null) {
            throw new IllegalStateException("agent stream body is empty");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String eventName = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!"message".equals(eventName) || data.length() > 0) {
                        emitEvent(eventName, data.toString(), consumer);
                    }
                    eventName = "message";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            if (data.length() > 0) {
                emitEvent(eventName, data.toString(), consumer);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("agent stream read failed", exception);
        }
    }

    private void emitEvent(String eventName, String rawData, Consumer<StreamEvent> consumer) {
        if (!StringUtils.hasText(rawData)) {
            consumer.accept(new StreamEvent(eventName, Collections.emptyMap()));
            return;
        }
        try {
            Map<String, Object> data = objectMapper.readValue(
                    rawData,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
            consumer.accept(new StreamEvent(eventName, data));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("agent stream event JSON is invalid", exception);
        }
    }

    private SimpleClientHttpRequestFactory requestFactory(AgentProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(
                Math.max(1, properties.getConnectTimeoutMs())
        ));
        factory.setReadTimeout(Duration.ofMillis(Math.max(
                1L,
                Math.max(properties.getReadTimeoutMs(), properties.getStreamTimeoutMs())
        )));
        return factory;
    }

    public record StreamEvent(String event, Map<String, Object> data) {

        public Map<String, Object> safeData() {
            return data == null ? Collections.emptyMap() : data;
        }
    }

}
