package com.redculture.platform.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.vo.ai.AgentRuntimeRequest;
import com.redculture.platform.vo.ai.AgentRuntimeResponse;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class AgentRuntimeClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AgentRuntimeClient(AppMapProperties appMapProperties,
                              AgentProperties agentProperties,
                              ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(appMapProperties.getLlmServiceBaseUrl())
                .requestFactory(requestFactory(agentProperties))
                .build();
        this.objectMapper = objectMapper;
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

    private void readEvents(java.io.InputStream inputStream, Consumer<StreamEvent> consumer) {
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
