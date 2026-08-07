package com.redculture.platform.service;

import com.redculture.platform.config.RagProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeVectorCleanupService {
    private final RagProperties rag;
    private final RestClient client = RestClient.create();
    public KnowledgeVectorCleanupService(RagProperties rag) { this.rag = rag; }
    public void deleteDocument(Long documentId) {
        String url = rag.getQdrantBaseUrl().replaceAll("/+$", "") + "/collections/knowledge_documents/points/delete?wait=true";
        client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", Map.of("must", List.of(Map.of("key", "documentId", "match", Map.of("value", documentId))))))
                .retrieve().toBodilessEntity();
    }
}
