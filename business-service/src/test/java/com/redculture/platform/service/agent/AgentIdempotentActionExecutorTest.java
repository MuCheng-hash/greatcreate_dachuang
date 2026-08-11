package com.redculture.platform.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.entity.AgentActionIdempotency;
import com.redculture.platform.mapper.AgentActionIdempotencyMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentIdempotentActionExecutorTest {

    @Test
    void replaysCompletedResponseAndRejectsDifferentPayload() {
        AgentActionIdempotencyMapper mapper = mock(AgentActionIdempotencyMapper.class);
        AgentActionIdempotency record = new AgentActionIdempotency();
        record.setActionId("action-1");
        record.setTurnId("turn-1");
        record.setOperation("resource.update");
        record.setStatus("PROCESSING");
        when(mapper.insertIfAbsent(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            if (record.getRequestHash() == null) {
                record.setRequestHash(invocation.getArgument(3));
                record.setRequestJson(invocation.getArgument(4));
            }
            return 1;
        });
        when(mapper.selectForUpdate("action-1")).thenReturn(record);
        doAnswer(invocation -> 1).when(mapper)
                .updateById(any(AgentActionIdempotency.class));
        AgentIdempotentActionExecutor executor = new AgentIdempotentActionExecutor(
                mapper, new ObjectMapper()
        );
        AtomicInteger mutations = new AtomicInteger();

        AgentIdempotentActionExecutor.Result first = executor.execute(
                "action-1", "turn-1", "resource.update", Map.of("id", 7),
                () -> {
                    mutations.incrementAndGet();
                    return Map.of("resourceId", 7);
                }
        );
        AgentIdempotentActionExecutor.Result replay = executor.execute(
                "action-1", "turn-1", "resource.update", Map.of("id", 7),
                () -> {
                    mutations.incrementAndGet();
                    return Map.of("resourceId", 7);
                }
        );

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(1, mutations.get());
        assertEquals(7, replay.body().get("resourceId"));
        IdempotencyConflictException conflict = assertThrows(
                IdempotencyConflictException.class,
                () -> executor.execute(
                        "action-1", "turn-1", "resource.update", Map.of("id", 8), Map::of
                )
        );
        assertEquals("idempotency_conflict", conflict.getCode());
    }

    @Test
    void requiresStableActionAndTurnIdentifiers() {
        AgentIdempotentActionExecutor executor = new AgentIdempotentActionExecutor(
                mock(AgentActionIdempotencyMapper.class), new ObjectMapper()
        );
        assertThrows(IllegalArgumentException.class, () -> executor.execute(
                "", "turn-1", "resource.update", Map.of(), Map::of
        ));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(
                "action-1", "", "resource.update", Map.of(), Map::of
        ));
    }
}
