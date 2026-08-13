package com.redculture.platform.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import com.redculture.platform.vo.ai.StatefulAgentRequest;
import com.redculture.platform.vo.request.AgentQaRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeClientTest {

    @Test
    void parsesOrderedSseEventsFromFastApi() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent/messages/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(("event: run.started\n"
                        + "data: {\"runId\":\"run-1\"}\n\n"
                        + "event: token\n"
                        + "data: {\"runId\":\"run-1\",\n"
                        + "data: \"delta\":\"你好\"}\n\n"
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
            StatefulAgentRequest request = new StatefulAgentRequest();
            request.setOwnerId("account:1");
            request.setScopeType("SCHOOL");
            request.setScopeId(1L);
            request.setTaskType("CHAT");
            request.setMessage("你好");
            List<AgentRuntimeClient.StreamEvent> events = client.stream(request)
                    .collectList()
                    .block();

            assertEquals(List.of("run.started", "token", "final", "done"),
                    events.stream().map(AgentRuntimeClient.StreamEvent::event).toList());
            assertEquals("你好", events.get(1).safeData().get("delta"));
            assertTrue(request.getClientTurnId() != null
                    && !request.getClientTurnId().isBlank());
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
            byte[] body = ("{\"threadId\":\"thread-1\",\"answer\":\"状态回答\","
                    + "\"status\":\"completed\",\"citations\":[],\"followUpQuestions\":[],"
                    + "\"toolExecutions\":[],\"memoryCandidates\":[{\"id\":\"memory-1\","
                    + "\"memoryType\":\"PROFILE\",\"content\":\"偏好项目式教学\","
                    + "\"status\":\"pending\",\"source\":\"inferred_chat\","
                    + "\"createdAt\":\"2026-07-31T00:00:00Z\","
                    + "\"updatedAt\":\"2026-07-31T00:00:00Z\"}],"
                    + "\"memoryApplied\":{\"count\":1,\"memoryIds\":[\"profile-1\"]}}")
                    .getBytes(StandardCharsets.UTF_8);
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
        server.createContext("/models", exchange -> {
            receivedTokens.add(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            byte[] body = "{\"models\":[{\"id\":\"deepseek\",\"displayName\":\"DeepSeek\",\"provider\":\"deepseek\",\"model\":\"deepseek-chat\",\"isDefault\":true}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/agent/turns/turn-client-1/cancel", exchange -> {
            receivedTokens.add(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            byte[] body = ("{\"clientTurnId\":\"turn-client-1\","
                    + "\"threadId\":\"thread-1\",\"turnStatus\":\"running\","
                    + "\"cancellationRequested\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
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
            request.setClientTurnId("turn-client-1");
            request.setQuestion("附近有哪些资源？");
            request.setModelId("deepseek");
            AuthCurrentUserVO user = new AuthCurrentUserVO();
            user.setAccountId(1L);
            AgentAnswerContext context = new AgentAnswerContext();
            context.setQuestion(request.getQuestion());
            context.setIntent(AgentIntent.NEARBY_RESOURCE);
            context.setScopeType(KnowledgeScopeType.SCHOOL);
            context.setScopeId(1L);

            AgentRuntimeResult result = client.generate(request, user, context).block();
            List<AgentRuntimeClient.StreamEvent> events = client
                    .streamStateful(request, user, context)
                    .collectList()
                    .block();
            var models = client.listModels().block();
            var cancellation = client.cancelConversationTurn(
                    "turn-client-1", "account:1", "SCHOOL", 1L
            ).block();

            assertEquals("状态回答", result.getAnswer().getAnswer());
            assertEquals("memory-1", result.getMemoryCandidates().get(0).getId());
            assertEquals(1, result.getMemoryApplied().getCount());
            assertEquals(List.of("run.started", "token", "final", "done"),
                    events.stream().map(AgentRuntimeClient.StreamEvent::event).toList());
            assertEquals("deepseek-chat", models.get(0).getModel());
            assertTrue(cancellation.isCancellationRequested());
            assertEquals("thread-1", cancellation.getThreadId());
            assertEquals(List.of("internal-secret", "internal-secret", "internal-secret", "internal-secret"), receivedTokens);
            assertTrue(requestBodies.stream().allMatch(body -> body.contains("\"ownerId\":\"account:1\"")));
            assertTrue(requestBodies.stream().allMatch(body -> body.contains("\"threadId\":\"thread-1\"")));
            assertTrue(requestBodies.stream().allMatch(body -> body.contains("\"clientTurnId\":\"turn-client-1\"")));
            assertTrue(requestBodies.stream().allMatch(body -> body.contains("\"modelId\":\"deepseek\"")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsNon2xxSseResponsesToSanitizedDomainErrors() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent/messages/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"detail\":{\"code\":\"thread_busy\",\"message\":\"sensitive\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(409, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            StepVerifier.create(clientFor(server, 5000).stream(streamRequest()))
                    .expectErrorSatisfies(error -> {
                        assertTrue(error instanceof AgentUpstreamException);
                        AgentUpstreamException upstream = (AgentUpstreamException) error;
                        assertEquals(409, upstream.getStatusCode());
                        assertEquals("thread_busy", upstream.getCode());
                        assertEquals("thread_busy", upstream.getMessage());
                    })
                    .verify(Duration.ofSeconds(2));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appliesIdleTimeoutToSilentSseStreams() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent/messages/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.flush();
                Thread.sleep(300);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            StepVerifier.create(clientFor(server, 50).stream(streamRequest()))
                    .expectErrorSatisfies(error -> {
                        assertTrue(error instanceof AgentUpstreamException);
                        AgentUpstreamException upstream = (AgentUpstreamException) error;
                        assertEquals(504, upstream.getStatusCode());
                        assertEquals("agent_timeout", upstream.getCode());
                    })
                    .verify(Duration.ofSeconds(2));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellingSubscriberClosesUpstreamSseConnection() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        server.createContext("/agent/messages/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                for (int index = 0; index < 500; index++) {
                    output.write(("event: token\ndata: {\"delta\":\"" + index + "\"}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    Thread.sleep(10);
                }
            } catch (IOException exception) {
                connectionClosed.countDown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            StepVerifier.create(clientFor(server, 5000).stream(streamRequest()))
                    .expectNextCount(1)
                    .thenCancel()
                    .verify(Duration.ofSeconds(2));
            assertTrue(connectionClosed.await(3, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
        }
    }

    private AgentRuntimeClient clientFor(HttpServer server, long streamTimeoutMs) {
        AppMapProperties mapProperties = new AppMapProperties();
        mapProperties.setLlmServiceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setConnectTimeoutMs(1000);
        agentProperties.setReadTimeoutMs(5000);
        agentProperties.setStreamTimeoutMs(streamTimeoutMs);
        return new AgentRuntimeClient(mapProperties, agentProperties, new ObjectMapper());
    }

    private StatefulAgentRequest streamRequest() {
        StatefulAgentRequest request = new StatefulAgentRequest();
        request.setOwnerId("account:1");
        request.setScopeType("SCHOOL");
        request.setScopeId(1L);
        request.setTaskType("CHAT");
        request.setMessage("你好");
        return request;
    }
}
