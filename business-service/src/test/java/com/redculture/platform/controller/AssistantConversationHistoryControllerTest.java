package com.redculture.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantConversationHistoryControllerTest {

    @Test
    void forwardsOwnedSchoolHistoryAndArchivesWithoutModelCalls() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        List<String> requests = new ArrayList<>();
        server.createContext("/agent/threads", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.endsWith("/archive")) {
                body = "{}";
            } else if (path.equals("/agent/threads/thread-1")) {
                body = "{\"threadId\":\"thread-1\",\"scopeType\":\"SCHOOL\",\"scopeId\":\"7\",\"status\":\"active\",\"summary\":\"\",\"createdAt\":\"2026-07-28T00:00:00Z\",\"updatedAt\":\"2026-07-28T01:00:00Z\",\"messages\":[{\"id\":1,\"role\":\"user\",\"content\":\"历史问题\",\"createdAt\":\"2026-07-28T00:00:00Z\",\"metadata\":{}}]}";
            } else {
                body = "[{\"threadId\":\"thread-1\",\"scopeType\":\"SCHOOL\",\"scopeId\":\"7\",\"title\":\"历史问题\",\"preview\":\"历史回答\",\"messageCount\":2,\"createdAt\":\"2026-07-28T00:00:00Z\",\"updatedAt\":\"2026-07-28T01:00:00Z\"}]";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            AppMapProperties properties = new AppMapProperties();
            properties.setLlmServiceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            AgentRuntimeClient client = new AgentRuntimeClient(properties, new AgentProperties(), new ObjectMapper());
            AssistantConversationHistoryController controller = new AssistantConversationHistoryController(client);
            AuthCurrentUserVO user = new AuthCurrentUserVO();
            user.setAccountId(1L);
            user.setSchoolId(7L);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute(AuthContext.CURRENT_USER_ATTRIBUTE, user);

            var history = controller.list(request);
            var detail = controller.detail("thread-1", request);
            var archived = controller.archive("thread-1", request);

            assertEquals("历史问题", history.getData().get(0).getTitle());
            assertEquals("历史问题", detail.getData().getMessages().get(0).getContent());
            assertEquals(200, archived.getCode());
            assertEquals(3, requests.size());
            assertTrue(requests.stream().allMatch(value -> value.contains("ownerId=account:1")));
            assertTrue(requests.stream().allMatch(value -> value.contains("scopeType=SCHOOL")));
            assertTrue(requests.stream().allMatch(value -> value.contains("scopeId=7")));
            assertTrue(requests.get(2).startsWith("POST "));
        } finally {
            server.stop(0);
        }
    }
}
