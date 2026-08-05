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
        server.createContext("/collections/test-active/points/search", exchange -> {
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
            properties.setQdrantAlias("test-active");
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

    @Test
    void resolvesAndAtomicallySwitchesAlias() throws Exception {
        AtomicReference<String> aliasActions = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/aliases", exchange -> {
            byte[] response = ("{\"result\":{\"aliases\":["
                    + "{\"alias_name\":\"test-active\",\"collection_name\":\"test-v1\"},"
                    + "{\"alias_name\":\"other\",\"collection_name\":\"other-v1\"}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/collections/aliases", exchange -> {
            aliasActions.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"result\":true,\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            RagProperties properties = new RagProperties();
            properties.setQdrantBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            QdrantChunkVectorStore store = new QdrantChunkVectorStore(properties, new ObjectMapper());

            assertEquals("test-v1", store.resolveAlias("test-active"));
            store.switchAlias("test-active", "test-v2");

            assertTrue(aliasActions.get().contains("\"delete_alias\""));
            assertTrue(aliasActions.get().contains("\"create_alias\""));
            assertTrue(aliasActions.get().contains("\"test-v2\""));
        } finally {
            server.stop(0);
        }
    }
}
