package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.agent.AgentAdminClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAdminControllerTest {

    @Test
    void proxiesObservabilityAndPromptOperations() {
        AgentAdminClient client = mock(AgentAdminClient.class);
        AgentAdminController controller = new AgentAdminController(client);
        Map<String, String> filters = Map.of("limit", "50");
        when(client.observabilitySummary(filters)).thenReturn(Mono.just(Map.of("calls", 2)));
        when(client.memoryMetrics()).thenReturn(Mono.just(Map.of(
                "memories", Map.of("total", 3, "byStatus", Map.of("active", 2)))));
        when(client.promptVersions("agent")).thenReturn(Mono.just(
                List.of(Map.of("version", "v1"))
        ));
        when(client.activatePrompt("agent", "v1")).thenReturn(Mono.just(Map.of("active", 1)));

        ApiResponse<Map<String, Object>> summary = controller.summary(filters).block();
        ApiResponse<Map<String, Object>> memoryMetrics = controller.memoryMetrics().block();
        ApiResponse<List<Map<String, Object>>> versions = controller.promptVersions("agent").block();
        ApiResponse<Map<String, Object>> activated = controller.activatePrompt("agent", "v1").block();

        assertEquals(200, summary.getCode());
        assertEquals(2, summary.getData().get("calls"));
        assertEquals(3, ((Map<?, ?>) memoryMetrics.getData().get("memories")).get("total"));
        assertFalse(memoryMetrics.getData().toString().contains("content"));
        assertEquals("v1", versions.getData().get(0).get("version"));
        assertEquals(1, activated.getData().get("active"));
        verify(client).observabilitySummary(eq(filters));
        verify(client).memoryMetrics();
        verify(client).promptVersions("agent");
        verify(client).activatePrompt("agent", "v1");
    }
}
