package com.redculture.platform.service.agent;

import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.entity.AgentActionOutbox;
import com.redculture.platform.mapper.AgentActionIdempotencyMapper;
import com.redculture.platform.mapper.AgentActionOutboxMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentActionOutboxWorkerTest {

    @Test
    void retriesWithTheSameActionIdAfterAnUncertainPublishFailure() {
        AgentActionOutboxService service = mock(AgentActionOutboxService.class);
        AgentActionOutbox event = new AgentActionOutbox();
        event.setEventId("event-1");
        event.setActionId("stable-action-1");
        event.setEventType("notification.requested");
        event.setPayloadJson("{\"message\":\"hello\"}");
        when(service.claimBatch(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(event), List.of(event));

        AtomicInteger publishes = new AtomicInteger();
        AgentOutboxPublisher publisher = mock(AgentOutboxPublisher.class);
        when(publisher.supports("notification.requested")).thenReturn(true);
        doAnswer(invocation -> {
            if (publishes.incrementAndGet() == 1) {
                throw new IllegalStateException("response lost after downstream commit");
            }
            return null;
        }).when(publisher).publish(
                "stable-action-1", "notification.requested", "{\"message\":\"hello\"}"
        );
        AgentProperties properties = new AgentProperties();
        AgentActionOutboxWorker worker = new AgentActionOutboxWorker(
                service,
                mock(AgentActionOutboxMapper.class),
                mock(AgentActionIdempotencyMapper.class),
                properties,
                List.of(publisher)
        );

        worker.dispatch();
        worker.dispatch();

        verify(publisher, times(2)).publish(
                "stable-action-1", "notification.requested", "{\"message\":\"hello\"}"
        );
        verify(service).markRetry(
                org.mockito.ArgumentMatchers.eq("event-1"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(properties.getOutboxRetrySeconds()),
                org.mockito.ArgumentMatchers.eq("IllegalStateException")
        );
        verify(service).markPublished(
                org.mockito.ArgumentMatchers.eq("event-1"),
                org.mockito.ArgumentMatchers.anyString()
        );
    }
}
