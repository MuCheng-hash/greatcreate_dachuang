package com.redculture.platform.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.AgentGenerationStatus;
import com.redculture.platform.vo.ai.StatefulAgentRequest;
import com.redculture.platform.vo.ai.StatefulAgentResponse;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class AgentRuntimeClient {

    private final RestClient restClient;
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
        try {
            StatefulAgentResponse response = send(body);
            if (response == null || !StringUtils.hasText(response.getAnswer())) {
                return null;
            }
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
                    response.getThreadId(), response.getStatus(), toolNames
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Calls the stateful SSE endpoint after Java has resolved authorization and trusted context. */
    public void streamStateful(AgentQaRequest request,
                               AuthCurrentUserVO user,
                               AgentAnswerContext context,
                               Consumer<StreamEvent> consumer) {
        stream(chatRequest(request, user, context), consumer);
    }

    public StatefulAgentResponse send(StatefulAgentRequest request) {
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

    public void stream(StatefulAgentRequest request, Consumer<StreamEvent> consumer) {
        restClient.post()
                .uri("/agent/messages/stream")
                .headers(this::applyInternalServiceToken)
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

    private String ownerId(AuthCurrentUserVO user) {
        if (user.getAccountId() != null) {
            return "account:" + user.getAccountId();
        }
        return "username:" + (user.getUsername() == null ? "unknown" : user.getUsername());
    }

    public StatefulAgentRequest chatRequest(AgentQaRequest request,
                                            AuthCurrentUserVO user,
                                            AgentAnswerContext context) {
        StatefulAgentRequest body = new StatefulAgentRequest();
        body.setOwnerId(ownerId(user));
        body.setScopeType(context.getScopeType().name());
        body.setScopeId(context.getScopeId());
        body.setThreadId(request.getThreadId());
        body.setTaskType("CHAT");
        body.setMessage(context.getQuestion());
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
        request.setTaskType(taskType);
        request.setMessage(message);
        request.setTaskPayload(taskPayload == null ? new LinkedHashMap<>() : taskPayload);
        request.setContext(context == null ? new LinkedHashMap<>() : context);
        return request;
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
                    emitEvent(eventName, data.toString(), consumer);
                    eventName = "message";
                    data.setLength(0);
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
        factory.setConnectTimeout(Duration.ofMillis(Math.max(1, properties.getConnectTimeoutMs())));
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
