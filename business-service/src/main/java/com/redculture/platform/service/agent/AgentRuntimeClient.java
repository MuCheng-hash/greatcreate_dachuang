package com.redculture.platform.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.vo.AgentCitationVO;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AgentRuntimeRequest;
import com.redculture.platform.vo.ai.AgentRuntimeResponse;
import com.redculture.platform.vo.request.AgentQaRequest;
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

    public AgentRuntimeClient(AppMapProperties appMapProperties,
                              AgentProperties agentProperties,
                              ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(appMapProperties.getLlmServiceBaseUrl())
                .requestFactory(requestFactory(agentProperties))
                .build();
        this.objectMapper = objectMapper;
        this.appMapProperties = appMapProperties;
    }

    public AgentRuntimeResponse run(AgentRuntimeRequest request) {
        AgentRuntimeResponse response = restClient.post()
                .uri("/llm/agent/run")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AgentRuntimeResponse.class);
        if (response == null || !StringUtils.hasText(response.getAnswer())) {
            throw new IllegalStateException("agent runtime returned an empty response");
        }
        return response;
    }

    /** Calls the stateful runtime after Java has resolved authorization and trusted context. */
    public AgentRuntimeResult generate(AgentQaRequest request,
                                       AuthCurrentUserVO user,
                                       AgentAnswerContext context) {
        if (!appMapProperties.isAgentRuntimeEnabled()
                || !StringUtils.hasText(appMapProperties.getLlmServiceBaseUrl())) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerId", ownerId(user));
        body.put("scopeType", context.getScopeType().name());
        body.put("scopeId", context.getScopeId());
        body.put("threadId", request.getThreadId());
        body.put("message", context.getQuestion());
        body.put("grade", context.getGrade());
        body.put("theme", context.getTheme());
        body.put("intent", context.getIntent() == null ? null : context.getIntent().name());
        body.put("context", trustedContext(context));
        try {
            StatefulAgentRuntimeResponse response = restClient.post()
                    .uri("/agent/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(StatefulAgentRuntimeResponse.class);
            if (response == null || !StringUtils.hasText(response.getAnswer())) {
                return null;
            }
            List<String> citationIds = response.getCitations() == null ? new ArrayList<>()
                    : response.getCitations().stream()
                    .map(AgentCitationVO::getCitationId)
                    .filter(StringUtils::hasText)
                    .toList();
            List<String> followUps = response.getFollowUpQuestions() == null ? new ArrayList<>()
                    : response.getFollowUpQuestions();
            List<String> toolNames = response.getToolExecutions() == null ? new ArrayList<>()
                    : response.getToolExecutions().stream()
                    .map(ToolExecutionResponse::getName)
                    .filter(StringUtils::hasText)
                    .toList();
            return new AgentRuntimeResult(
                    new GeneratedAnswer(response.getAnswer(), citationIds, followUps),
                    response.getThreadId(), response.getStatus(), toolNames
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public void stream(AgentRuntimeRequest request, Consumer<StreamEvent> consumer) {
        restClient.post()
                .uri("/llm/agent/stream")
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

    private String ownerId(AuthCurrentUserVO user) {
        if (user.getAccountId() != null) {
            return "account:" + user.getAccountId();
        }
        return "username:" + (user.getUsername() == null ? "unknown" : user.getUsername());
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

    @lombok.Data
    private static class StatefulAgentRuntimeResponse {
        private String threadId;
        private String answer;
        private String status;
        private List<AgentCitationVO> citations = new ArrayList<>();
        private List<String> followUpQuestions = new ArrayList<>();
        private List<ToolExecutionResponse> toolExecutions = new ArrayList<>();
    }

    @lombok.Data
    private static class ToolExecutionResponse {
        private String name;
        private String status;
        private Integer durationMs;
    }
}
