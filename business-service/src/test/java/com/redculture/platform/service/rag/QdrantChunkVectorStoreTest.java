package com.redculture.platform.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.RagProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QdrantChunkVectorStoreTest {

    @Test
    void sendsFilteredHnswSearchAndMapsCandidates() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/test/points/search", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"result\":[{\"id\":42,\"score\":0.87,\"payload\":{\"chunk_id\":42}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            RagProperties properties = new RagProperties();
            properties.setQdrantBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setQdrantCollection("test");
            QdrantChunkVectorStore store = new QdrantChunkVectorStore(properties, new ObjectMapper());

            List<VectorSearchCandidate> candidates = store.search(
                    new float[]{0.1F, 0.2F}, Set.of("resource:7"), 12);

            assertEquals(List.of(new VectorSearchCandidate(42L, 0.87D)), candidates);
            assertTrue(requestBody.get().contains("\"entity_key\""));
            assertTrue(requestBody.get().contains("\"resource:7\""));
            assertTrue(requestBody.get().contains("\"exact\":false"));
        } finally {
            server.stop(0);
        }
    }
}
