package com.redculture.platform.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.vo.ai.AgentRuntimeRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
