package com.redculture.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.agent.AgentMemoryConflictException;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.request.AgentMemoryResolutionRequest;
import com.redculture.platform.vo.request.AgentMemoryCreateRequest;
import com.redculture.platform.vo.request.AgentMemorySettingUpdateRequest;
import com.redculture.platform.vo.request.AgentMemoryUpdateRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryControllerTest {

    @Test
    void proxiesAllMemoryOperationsWithLoginDerivedOwnerAndSchoolScope() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        List<String> requests = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        server.createContext("/agent/memory-settings", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"available\":true,\"enabled\":true,\"effectiveEnabled\":true}");
        });
        server.createContext("/agent/memories", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (path.endsWith("/permanent")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            String item = "{\"id\":\"memory-1\",\"memoryType\":\"PROFILE\","
                    + "\"fieldKey\":\"grade\",\"content\":\"常教四年级\","
                    + "\"status\":\"active\",\"source\":\"profile_ui\","
                    + "\"createdAt\":\"2026-07-31T00:00:00Z\","
                    + "\"updatedAt\":\"2026-07-31T00:00:00Z\"}";
            respond(exchange,
                    "GET".equals(exchange.getRequestMethod()) && "/agent/memories".equals(path)
                            ? "[" + item + "]" : item);
        });
        server.start();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            AppMapProperties mapProperties = new AppMapProperties();
            mapProperties.setLlmServiceBaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            AgentProperties agentProperties = new AgentProperties();
            agentProperties.setInternalServiceToken("memory-token");
            AgentRuntimeClient client = new AgentRuntimeClient(
                    mapProperties, agentProperties, objectMapper);
            AgentMemoryController controller = new AgentMemoryController(client);
            MockHttpServletRequest servletRequest = schoolRequest();

            controller.settings(servletRequest).block();
            controller.updateSettings(
                    new AgentMemorySettingUpdateRequest(true), servletRequest
            ).block();
            controller.list("active", "PROFILE", servletRequest).block();

            AgentMemoryCreateRequest createRequest = objectMapper.readValue(
                    "{\"ownerId\":\"account:attacker\",\"scopeId\":999,"
                            + "\"memoryType\":\"PROFILE\",\"fieldKey\":\"grade\","
                            + "\"content\":\"常教四年级\"}",
                    AgentMemoryCreateRequest.class);
            controller.create(createRequest, servletRequest).block();
            controller.update(
                    "memory-1",
                    new AgentMemoryUpdateRequest("PROFILE", "grade", "常教五年级"),
                    servletRequest).block();
            controller.confirmationPreview("memory-1", servletRequest).block();
            controller.confirm(
                    "memory-1", new AgentMemoryResolutionRequest(true), servletRequest
            ).block();
            controller.delete("memory-1", servletRequest).block();
            controller.restore(
                    "memory-1", new AgentMemoryResolutionRequest(true), servletRequest
            ).block();
            controller.permanentDelete("memory-1", servletRequest).block();

            assertEquals(10, requests.size());
            assertTrue(requests.stream()
                    .filter(value -> value.contains("?"))
                    .allMatch(value -> value.contains("ownerId=account:1")));
            assertTrue(requests.stream()
                    .filter(value -> value.contains("?"))
                    .allMatch(value -> value.contains("scopeType=SCHOOL")));
            assertTrue(requests.stream()
                    .filter(value -> value.contains("?"))
                    .allMatch(value -> value.contains("scopeId=7")));
            String allBodies = String.join("\n", bodies);
            assertTrue(allBodies.contains("\"ownerId\":\"account:1\""));
            assertTrue(allBodies.contains("\"scopeId\":7"));
            assertFalse(allBodies.contains("account:attacker"));
            assertFalse(allBodies.contains("\"scopeId\":999"));
            assertTrue(requests.stream().anyMatch(value -> value.contains("/confirm")));
            assertTrue(requests.stream().anyMatch(value -> value.contains("/confirmation-preview")));
            assertTrue(requests.stream().anyMatch(value -> value.contains("/restore")));
            assertTrue(requests.stream().anyMatch(value -> value.contains("/permanent")));
            assertTrue(bodyFor(requests, bodies, "PUT /agent/memory-settings")
                    .contains("\"enabled\":true"));
            assertTrue(bodyFor(requests, bodies, "PATCH /agent/memories/memory-1")
                    .contains("\"content\":\"常教五年级\""));
            assertTrue(bodyFor(requests, bodies, "POST /agent/memories/memory-1/confirm")
                    .contains("\"replaceConflicts\":true"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void platformAdministratorCannotUseUserMemoryContentRoutes() {
        AgentRuntimeClient client = org.mockito.Mockito.mock(AgentRuntimeClient.class);
        AgentMemoryController controller = new AgentMemoryController(client);
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setAccountId(99L);
        user.setRoleCode("platform_admin");
        user.setSchoolId(7L);
        request.setAttribute(AuthContext.CURRENT_USER_ATTRIBUTE, user);

        assertThrows(IllegalArgumentException.class,
                () -> controller.list("active", null, request));
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void forwardsConflictAsNoSideEffectWithoutRetryingReplacement() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        List<String> bodies = new ArrayList<>();
        server.createContext("/agent/memories", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 409, "{\"detail\":{\"code\":\"memory_conflict\","
                    + "\"message\":\"该字段已有已生效记忆，请先确认是否替换\","
                    + "\"preview\":{\"candidate\":{\"id\":\"candidate-1\","
                    + "\"memoryType\":\"PROFILE\",\"fieldKey\":\"grade\","
                    + "\"content\":\"常教五年级\",\"status\":\"pending\","
                    + "\"source\":\"inferred_chat\",\"createdAt\":\"2026-08-01T00:00:00Z\","
                    + "\"updatedAt\":\"2026-08-01T00:00:00Z\"},\"conflicts\":[],\"duplicate\":false}}}");
        });
        server.start();
        try {
            AppMapProperties properties = new AppMapProperties();
            properties.setLlmServiceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            AgentRuntimeClient client = new AgentRuntimeClient(
                    properties, new AgentProperties(), new ObjectMapper());

            AgentMemoryConflictException conflict = assertThrows(
                    AgentMemoryConflictException.class,
                    () -> client.confirmMemory(
                            "candidate-1", "account:1", "SCHOOL", 7L, false
                    ).block());

            assertEquals("candidate-1", conflict.getPreview().getCandidate().getId());
            assertEquals(1, bodies.size());
            assertTrue(bodies.get(0).contains("\"replaceConflicts\":false"));
        } finally {
            server.stop(0);
        }
    }

    private MockHttpServletRequest schoolRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setAccountId(1L);
        user.setSchoolId(7L);
        user.setRoleCode("school_admin");
        request.setAttribute(AuthContext.CURRENT_USER_ATTRIBUTE, user);
        return request;
    }

    private String bodyFor(List<String> requests, List<String> bodies, String requestPrefix) {
        for (int index = 0; index < requests.size(); index += 1) {
            if (requests.get(index).startsWith(requestPrefix)) {
                return bodies.get(index);
            }
        }
        return "";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws java.io.IOException {
        respond(exchange, 200, body);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
