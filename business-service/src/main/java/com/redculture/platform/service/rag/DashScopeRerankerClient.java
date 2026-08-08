package com.redculture.platform.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.RagProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DashScopeRerankerClient implements RagRerankerClient {

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DashScopeRerankerClient(RagProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(Math.max(500, properties.getRerankerTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public RerankResult rerank(String query, List<RerankDocument> documents, int topK) {
        if (!properties.isRerankerEnabled() || !StringUtils.hasText(properties.getRerankerApiKey())
                || !StringUtils.hasText(query) || documents == null || documents.isEmpty()) {
            return RerankResult.skipped();
        }
        try {
            List<String> values = documents.stream().map(RerankDocument::text).toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getRerankerModel());
            body.put("query", query);
            body.put("documents", values);
            body.put("top_n", Math.min(Math.max(1, topK), values.size()));
            String response = restClient.post().uri(endpoint()).headers(this::auth)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                results = root.path("output").path("results");
            }
            List<RerankScore> scores = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= documents.size()) {
                    continue;
                }
                double score = item.has("relevance_score") ? item.path("relevance_score").asDouble()
                        : item.path("score").asDouble();
                scores.add(new RerankScore(documents.get(index).citationId(), score));
            }
            return scores.isEmpty() ? RerankResult.failed("reranker_empty_result")
                    : new RerankResult(true, true, List.copyOf(scores), "ok");
        } catch (Exception exception) {
            return RerankResult.failed("reranker_" + exception.getClass().getSimpleName().toLowerCase());
        }
    }

    private String endpoint() {
        String base = properties.getRerankerBaseUrl().replaceAll("/+$", "");
        return base.endsWith("/reranks") ? base : base + "/reranks";
    }

    private void auth(HttpHeaders headers) {
        headers.setBearerAuth(properties.getRerankerApiKey());
    }
}
