package com.redculture.platform.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.RagProperties;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class QdrantChunkVectorStore implements ChunkVectorStore {

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public QdrantChunkVectorStore(RagProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public void ensureCollection() {
        boolean exists = true;
        try {
            restClient.get().uri(collectionEndpoint()).headers(this::addAuthHeader)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404) {
                throw exception;
            }
            exists = false;
        }

        if (!exists) {
            Map<String, Object> vectors = Map.of(
                    "size", properties.getEmbeddingDimensions(),
                    "distance", "Cosine"
            );
            Map<String, Object> body = Map.of(
                    "vectors", vectors,
                    "hnsw_config", Map.of("m", 16, "ef_construct", 100)
            );
            restClient.put().uri(collectionEndpoint())
                    .headers(this::addAuthHeader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        }
        ensureEntityKeyIndex();
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> values = points.stream().map(point -> Map.<String, Object>of(
                "id", point.chunkId(),
                "vector", point.vector(),
                "payload", Map.of("chunk_id", point.chunkId(), "entity_key", point.entityKey())
        )).toList();
        restClient.put().uri(collectionEndpoint() + "/points?wait=true")
                .headers(this::addAuthHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", values))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public List<VectorSearchCandidate> search(float[] queryVector, Set<String> entityKeys, int limit) {
        if (entityKeys == null || entityKeys.isEmpty() || limit <= 0) {
            return List.of();
        }
        Map<String, Object> match = Map.of("any", entityKeys);
        Map<String, Object> condition = Map.of("key", "entity_key", "match", match);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", queryVector);
        body.put("filter", Map.of("must", List.of(condition)));
        body.put("limit", limit);
        body.put("score_threshold", properties.getMinimumVectorScore());
        body.put("with_payload", true);
        body.put("params", Map.of("hnsw_ef", Math.max(64, limit * 4), "exact", false));

        String response = restClient.post().uri(collectionEndpoint() + "/points/search")
                .headers(this::addAuthHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parseCandidates(response);
    }

    private List<VectorSearchCandidate> parseCandidates(String response) {
        try {
            JsonNode result = objectMapper.readTree(response).path("result");
            if (!result.isArray()) {
                throw new IllegalStateException("Qdrant response has no result array");
            }
            List<VectorSearchCandidate> candidates = new ArrayList<>();
            for (JsonNode item : result) {
                JsonNode chunkId = item.path("payload").path("chunk_id");
                if (chunkId.canConvertToLong()) {
                    candidates.add(new VectorSearchCandidate(chunkId.asLong(), item.path("score").asDouble()));
                }
            }
            return candidates;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse Qdrant response", exception);
        }
    }

    private void addAuthHeader(HttpHeaders headers) {
        if (StringUtils.hasText(properties.getQdrantApiKey())) {
            headers.set("api-key", properties.getQdrantApiKey());
        }
    }

    private void ensureEntityKeyIndex() {
        restClient.put().uri(collectionEndpoint() + "/index?wait=true")
                .headers(this::addAuthHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("field_name", "entity_key", "field_schema", "keyword"))
                .retrieve()
                .toBodilessEntity();
    }

    private String collectionEndpoint() {
        return properties.getQdrantBaseUrl().replaceAll("/+$", "")
                + "/collections/" + properties.getQdrantCollection();
    }
}
