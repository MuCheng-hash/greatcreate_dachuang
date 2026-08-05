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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        ensureCollection(properties.getQdrantCollection());
    }

    @Override
    public void ensureCollection(String collectionName) {
        boolean exists = true;
        try {
            restClient.get().uri(collectionEndpoint(collectionName)).headers(this::addAuthHeader)
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
            restClient.put().uri(collectionEndpoint(collectionName))
                    .headers(this::addAuthHeader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        }
        ensureEntityKeyIndex(collectionName);
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        upsert(properties.getQdrantCollection(), points);
    }

    @Override
    public void upsert(String collectionName, List<VectorPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> values = points.stream().map(point -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chunk_id", point.chunkId());
            payload.put("entity_key", point.entityKey());
            putIfPresent(payload, "content_hash", point.contentHash());
            putIfPresent(payload, "embedding_model", point.embeddingModel());
            if (point.embeddingDimensions() > 0) {
                payload.put("embedding_dimensions", point.embeddingDimensions());
            }
            putIfPresent(payload, "index_version", point.indexVersion());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", point.chunkId());
            value.put("vector", point.vector());
            value.put("payload", payload);
            return value;
        }).toList();
        restClient.put().uri(collectionEndpoint(collectionName) + "/points?wait=true")
                .headers(this::addAuthHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", values))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void delete(String collectionName, Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        restClient.post().uri(collectionEndpoint(collectionName) + "/points/delete?wait=true")
                .headers(this::addAuthHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", chunkIds))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public Set<Long> listPointIds(String collectionName) {
        Set<Long> ids = new LinkedHashSet<>();
        Long offset = null;
        do {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("limit", 256);
            body.put("with_payload", false);
            body.put("with_vector", false);
            if (offset != null) {
                body.put("offset", offset);
            }
            String response = restClient.post().uri(collectionEndpoint(collectionName) + "/points/scroll")
                    .headers(this::addAuthHeader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            try {
                JsonNode result = objectMapper.readTree(response).path("result");
                for (JsonNode point : result.path("points")) {
                    JsonNode id = point.path("id");
                    if (id.canConvertToLong()) {
                        ids.add(id.asLong());
                    }
                }
                JsonNode nextOffset = result.path("next_page_offset");
                offset = nextOffset.canConvertToLong() ? nextOffset.asLong() : null;
            } catch (Exception exception) {
                throw new IllegalStateException("failed to parse Qdrant scroll response", exception);
            }
        } while (offset != null);
        return ids;
    }

    @Override
    public String resolveAlias(String aliasName) {
        if (!StringUtils.hasText(aliasName)) {
            return null;
        }
        try {
            String response = restClient.get().uri(baseEndpoint() + "/aliases")
                    .headers(this::addAuthHeader).retrieve().body(String.class);
            JsonNode aliases = objectMapper.readTree(response).path("result").path("aliases");
            if (aliases.isArray()) {
                for (JsonNode alias : aliases) {
                    if (!aliasName.equals(alias.path("alias_name").asText())) {
                        continue;
                    }
                    String collectionName = alias.path("collection_name").asText();
                    return StringUtils.hasText(collectionName) ? collectionName : null;
                }
            }
            return null;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return null;
            }
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to resolve Qdrant alias", exception);
        }
    }

    @Override
    public void switchAlias(String aliasName, String collectionName) {
        String currentCollection = resolveAlias(aliasName);
        if (collectionName.equals(currentCollection)) {
            return;
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        if (StringUtils.hasText(currentCollection)) {
            actions.add(Map.of("delete_alias", Map.of("alias_name", aliasName)));
        }
        actions.add(Map.of("create_alias", Map.of(
                "collection_name", collectionName,
                "alias_name", aliasName
        )));
        restClient.post().uri(baseEndpoint() + "/collections/aliases")
                .headers(this::addAuthHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("actions", actions))
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

        String response;
        String activeCollection = StringUtils.hasText(properties.getQdrantAlias())
                ? properties.getQdrantAlias() : properties.getQdrantCollection();
        try {
            response = search(activeCollection, body);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404
                    || activeCollection.equals(properties.getQdrantCollection())) {
                throw exception;
            }
            response = search(properties.getQdrantCollection(), body);
        }
        return parseCandidates(response);
    }

    private String search(String collectionName, Map<String, Object> body) {
        return restClient.post().uri(collectionEndpoint(collectionName) + "/points/search")
                .headers(this::addAuthHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
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

    private void ensureEntityKeyIndex(String collectionName) {
        restClient.put().uri(collectionEndpoint(collectionName) + "/index?wait=true")
                .headers(this::addAuthHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("field_name", "entity_key", "field_schema", "keyword"))
                .retrieve()
                .toBodilessEntity();
    }

    private void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (StringUtils.hasText(value)) {
            payload.put(key, value);
        }
    }

    private String collectionEndpoint(String collectionName) {
        return baseEndpoint() + "/collections/" + collectionName;
    }

    private String baseEndpoint() {
        return properties.getQdrantBaseUrl().replaceAll("/+$", "");
    }
}
