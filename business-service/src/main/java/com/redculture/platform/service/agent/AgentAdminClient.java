package com.redculture.platform.service.agent;

import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class AgentAdminClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final String promptAdminToken;
    private final String observabilityAdminToken;

    public AgentAdminClient(AppMapProperties appMapProperties, AgentProperties agentProperties) {
        this.restClient = RestClient.builder()
                .baseUrl(appMapProperties.getLlmServiceBaseUrl())
                .requestFactory(requestFactory(agentProperties))
                .build();
        this.promptAdminToken = agentProperties.getPromptAdminToken();
        this.observabilityAdminToken = agentProperties.getObservabilityAdminToken();
    }

    public Map<String, Object> observabilitySummary(Map<String, String> filters) {
        return getMap("/admin/observability/summary", filters, this::applyObservabilityToken);
    }

    public List<Map<String, Object>> observabilityTraces(Map<String, String> filters) {
        return getList("/admin/observability/traces", filters, this::applyObservabilityToken);
    }

    public List<Map<String, Object>> toolTraces(Map<String, String> filters) {
        return getList("/admin/observability/tool-traces", filters, this::applyObservabilityToken);
    }

    public Map<String, Object> memoryMetrics() {
        return getMap("/admin/memory-metrics", Map.of(), this::applyObservabilityToken);
    }

    public List<Map<String, Object>> promptVersions(String promptKey) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/admin/prompts/{promptKey}/versions").build(promptKey))
                .headers(this::applyPromptToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(LIST_TYPE);
    }

    public List<Map<String, Object>> promptMetrics(String promptKey) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/admin/prompts/{promptKey}/metrics").build(promptKey))
                .headers(this::applyPromptToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(LIST_TYPE);
    }

    public Map<String, Object> activatePrompt(String promptKey, String version) {
        return restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/admin/prompts/{promptKey}/versions/{version}/activate")
                        .build(promptKey, version))
                .headers(this::applyPromptToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(MAP_TYPE);
    }

    private Map<String, Object> getMap(String path,
                                       Map<String, String> filters,
                                       Consumer<HttpHeaders> headers) {
        return restClient.get()
                .uri(uriBuilder -> buildUri(uriBuilder, path, filters))
                .headers(headers)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(MAP_TYPE);
    }

    private List<Map<String, Object>> getList(String path,
                                              Map<String, String> filters,
                                              Consumer<HttpHeaders> headers) {
        return restClient.get()
                .uri(uriBuilder -> buildUri(uriBuilder, path, filters))
                .headers(headers)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(LIST_TYPE);
    }

    private java.net.URI buildUri(UriBuilder uriBuilder, String path, Map<String, String> filters) {
        UriBuilder builder = uriBuilder.path(path);
        if (filters != null) {
            filters.forEach((key, value) -> {
                if (StringUtils.hasText(value)) {
                    builder.queryParam(key, value);
                }
            });
        }
        return builder.build();
    }

    private void applyPromptToken(HttpHeaders headers) {
        if (StringUtils.hasText(promptAdminToken)) {
            headers.set("X-Prompt-Admin-Token", promptAdminToken);
        }
    }

    private void applyObservabilityToken(HttpHeaders headers) {
        if (StringUtils.hasText(observabilityAdminToken)) {
            headers.set("X-Observability-Admin-Token", observabilityAdminToken);
        }
    }

    private SimpleClientHttpRequestFactory requestFactory(AgentProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(1, properties.getConnectTimeoutMs())));
        factory.setReadTimeout(Duration.ofMillis(Math.max(1, properties.getReadTimeoutMs())));
        return factory;
    }
}
