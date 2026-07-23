package com.redculture.platform.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.RagProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleEmbeddingClient(RagProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of(
                "model", properties.getEmbeddingModel(),
                "input", texts,
                "dimensions", properties.getEmbeddingDimensions()
        );
        String response = restClient.post()
                .uri(embeddingEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (StringUtils.hasText(properties.getEmbeddingApiKey())) {
                        headers.setBearerAuth(properties.getEmbeddingApiKey());
                    }
                })
                .body(body)
                .retrieve()
                .body(String.class);
        return parseEmbeddings(response, texts.size());
    }

    private List<float[]> parseEmbeddings(String response, int expectedCount) {
        try {
            JsonNode data = objectMapper.readTree(response).path("data");
            if (!data.isArray()) {
                throw new IllegalStateException("embedding provider response has no data array");
            }
            List<IndexedEmbedding> indexed = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode vectorNode = item.path("embedding");
                if (!vectorNode.isArray()) {
                    throw new IllegalStateException("embedding provider returned an invalid vector");
                }
                float[] vector = new float[vectorNode.size()];
                for (int i = 0; i < vectorNode.size(); i++) {
                    vector[i] = (float) vectorNode.get(i).asDouble();
                }
                if (vector.length != properties.getEmbeddingDimensions()) {
                    throw new IllegalStateException("embedding dimension does not match app.rag.embedding-dimensions");
                }
                indexed.add(new IndexedEmbedding(item.path("index").asInt(indexed.size()), vector));
            }
            indexed.sort(Comparator.comparingInt(IndexedEmbedding::index));
            if (indexed.size() != expectedCount) {
                throw new IllegalStateException("embedding provider returned an unexpected result count");
            }
            return indexed.stream().map(IndexedEmbedding::vector).toList();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse embedding provider response", exception);
        }
    }

    private String embeddingEndpoint() {
        String baseUrl = properties.getEmbeddingBaseUrl().replaceAll("/+$", "");
        return baseUrl.endsWith("/embeddings") ? baseUrl : baseUrl + "/embeddings";
    }

    private record IndexedEmbedding(int index, float[] vector) {
    }
}
