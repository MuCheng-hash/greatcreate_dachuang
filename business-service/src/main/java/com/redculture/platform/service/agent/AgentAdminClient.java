package com.redculture.platform.service.agent;

import com.redculture.platform.config.AgentAsyncConfiguration;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Component
public class AgentAdminClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() { };

    private final WebClient webClient;
    private final Duration requestTimeout;
    private final String promptAdminToken;
    private final String observabilityAdminToken;

    @Autowired
    public AgentAdminClient(AppMapProperties appMapProperties,
                            AgentProperties agentProperties,
                            @Qualifier("agentWebClient") WebClient webClient) {
        this.webClient = webClient;
        this.requestTimeout = Duration.ofMillis(Math.max(1, agentProperties.getReadTimeoutMs()));
        this.promptAdminToken = agentProperties.getPromptAdminToken();
        this.observabilityAdminToken = agentProperties.getObservabilityAdminToken();
    }

    public AgentAdminClient(AppMapProperties appMapProperties,
                            AgentProperties agentProperties) {
        this(
                appMapProperties,
                agentProperties,
                AgentAsyncConfiguration.createAgentWebClient(
                        appMapProperties, agentProperties
                )
        );
    }

    public Mono<Map<String, Object>> observabilitySummary(Map<String, String> filters) {
        return getMap("/admin/observability/summary", filters, this::applyObservabilityToken);
    }

    public Mono<List<Map<String, Object>>> observabilityTraces(Map<String, String> filters) {
        return getList("/admin/observability/traces", filters, this::applyObservabilityToken);
    }

    public Mono<List<Map<String, Object>>> toolTraces(Map<String, String> filters) {
        return getList("/admin/observability/tool-traces", filters, this::applyObservabilityToken);
    }

    public Mono<Map<String, Object>> memoryMetrics() {
        return getMap("/admin/memory-metrics", Map.of(), this::applyObservabilityToken);
    }

    public Mono<List<Map<String, Object>>> promptVersions(String promptKey) {
        return json(
                webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/admin/prompts/{promptKey}/versions")
                                .build(promptKey))
                        .headers(this::applyPromptToken)
                        .accept(MediaType.APPLICATION_JSON),
                LIST_TYPE
        );
    }

    public Mono<List<Map<String, Object>>> promptMetrics(String promptKey) {
        return json(
                webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/admin/prompts/{promptKey}/metrics")
                                .build(promptKey))
                        .headers(this::applyPromptToken)
                        .accept(MediaType.APPLICATION_JSON),
                LIST_TYPE
        );
    }

    public Mono<Map<String, Object>> activatePrompt(String promptKey, String version) {
        return json(
                webClient.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/admin/prompts/{promptKey}/versions/{version}/activate")
                                .build(promptKey, version))
                        .headers(this::applyPromptToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of()),
                MAP_TYPE
        );
    }

    private Mono<Map<String, Object>> getMap(
            String path,
            Map<String, String> filters,
            Consumer<HttpHeaders> headers) {
        return json(
                webClient.get()
                        .uri(uriBuilder -> buildUri(uriBuilder, path, filters))
                        .headers(headers)
                        .accept(MediaType.APPLICATION_JSON),
                MAP_TYPE
        );
    }

    private Mono<List<Map<String, Object>>> getList(
            String path,
            Map<String, String> filters,
            Consumer<HttpHeaders> headers) {
        return json(
                webClient.get()
                        .uri(uriBuilder -> buildUri(uriBuilder, path, filters))
                        .headers(headers)
                        .accept(MediaType.APPLICATION_JSON),
                LIST_TYPE
        );
    }

    private java.net.URI buildUri(
            UriBuilder uriBuilder, String path, Map<String, String> filters) {
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

    private <T> Mono<T> json(
            WebClient.RequestHeadersSpec<?> request,
            ParameterizedTypeReference<T> type) {
        return request.exchangeToMono(response -> decode(response, type))
                .timeout(requestTimeout)
                .onErrorMap(
                        error -> error instanceof TimeoutException
                                || error instanceof WebClientRequestException,
                        error -> new AgentUpstreamException(
                                error instanceof TimeoutException ? 504 : 503,
                                error instanceof TimeoutException
                                        ? "agent_timeout" : "agent_unavailable",
                                true,
                                "",
                                error
                        )
                );
    }

    private <T> Mono<T> decode(
            ClientResponse response,
            ParameterizedTypeReference<T> type) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(type);
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(new AgentUpstreamException(
                        response.statusCode().value(),
                        "agent_admin_upstream_error",
                        response.statusCode().value() >= 500,
                        body,
                        null
                )));
    }
}
