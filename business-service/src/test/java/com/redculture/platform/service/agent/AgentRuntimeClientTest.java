package com.redculture.platform.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import com.redculture.platform.vo.ai.AgentRuntimeRequest;
import com.redculture.platform.vo.request.AgentQaRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeClientTest {

    @Test
    void parsesOrderedSseEventsFromFastApi() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/llm/agent/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(("event: run.started\n"
                        + "data: {\"runId\":\"run-1\"}\n\n"
                        + "event: token\n"
                        + "data: {\"runId\":\"run-1\",\"delta\":\"你好\"}\n\n"
                        + "event: final\n"
                        + "data: {\"runId\":\"run-1\",\"response\":{\"answer\":\"你好\"}}\n\n"
                        + "event: done\n"
                        + "data: {\"runId\":\"run-1\"}\n\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            AppMapProperties mapProperties = new AppMapProperties();
            mapProperties.setLlmServiceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            AgentProperties agentProperties = new AgentProperties();
            agentProperties.setConnectTimeoutMs(1000);
            agentProperties.setReadTimeoutMs(5000);
            AgentRuntimeClient client = new AgentRuntimeClient(
                    mapProperties, agentProperties, new ObjectMapper()
            );
            List<AgentRuntimeClient.StreamEvent> events = new ArrayList<>();

            client.stream(new AgentRuntimeRequest(), events::add);

            assertEquals(List.of("run.started", "token", "final", "done"),
                    events.stream().map(AgentRuntimeClient.StreamEvent::event).toList());
            assertEquals("你好", events.get(1).safeData().get("delta"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesStatefulEndpointsAndPropagatesInternalServiceToken() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        List<String> requestBodies = new ArrayList<>();
        List<String> receivedTokens = new ArrayList<>();
        server.createContext("/agent/messages", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedTokens.add(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            byte[] body = "{\"threadId\":\"thread-1\",\"answer\":\"状态回答\",\"status\":\"completed\",\"citations\":[],\"followUpQuestions\":[],\"toolExecutions\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/agent/messages/stream", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedTokens.add(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(("event: run.started\n"
                        + "data: {\"runId\":\"run-stateful\"}\n\n"
                        + "event: token\n"
                        + "data: {\"runId\":\"run-stateful\",\"delta\":\"状态\"}\n\n"
                        + "event: final\n"
                        + "data: {\"runId\":\"run-stateful\",\"threadId\":\"thread-1\"}\n\n"
                        + "event: done\n"
                        + "data: {\"runId\":\"run-stateful\"}\n\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            AppMapProperties mapProperties = new AppMapProperties();
            mapProperties.setLlmServiceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            AgentProperties agentProperties = new AgentProperties();
            agentProperties.setInternalServiceToken("internal-secret");
            agentProperties.setConnectTimeoutMs(1000);
            agentProperties.setReadTimeoutMs(5000);
            AgentRuntimeClient client = new AgentRuntimeClient(
                    mapProperties, agentProperties, new ObjectMapper()
            );

            AgentQaRequest request = new AgentQaRequest();
            request.setThreadId("thread-1");
            request.setQuestion("附近有哪些资源？");
            AuthCurrentUserVO user = new AuthCurrentUserVO();
            user.setAccountId(1L);
            AgentAnswerContext context = new AgentAnswerContext();
            context.setQuestion(request.getQuestion());
            context.setIntent(AgentIntent.NEARBY_RESOURCE);
            context.setScopeType(KnowledgeScopeType.SCHOOL);
            context.setScopeId(1L);

            AgentRuntimeResult result = client.generate(request, user, context);
            List<AgentRuntimeClient.StreamEvent> events = new ArrayList<>();
            client.streamStateful(request, user, context, events::add);

            assertEquals("状态回答", result.getAnswer().getAnswer());
            assertEquals(List.of("run.started", "token", "final", "done"),
                    events.stream().map(AgentRuntimeClient.StreamEvent::event).toList());
            assertEquals(List.of("internal-secret", "internal-secret"), receivedTokens);
            assertTrue(requestBodies.stream().allMatch(body -> body.contains("\"ownerId\":\"account:1\"")));
            assertTrue(requestBodies.stream().allMatch(body -> body.contains("\"threadId\":\"thread-1\"")));
        } finally {
            server.stop(0);
        }
    }
}
