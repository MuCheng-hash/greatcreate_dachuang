package com.redculture.platform.service.agent;

import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.entity.AgentActionOutbox;
import com.redculture.platform.mapper.AgentActionIdempotencyMapper;
import com.redculture.platform.mapper.AgentActionOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.agent", name = "write-tools-enabled", havingValue = "true")
public class AgentActionOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentActionOutboxWorker.class);

    private final AgentActionOutboxService outboxService;
    private final AgentActionOutboxMapper outboxMapper;
    private final AgentActionIdempotencyMapper idempotencyMapper;
    private final AgentProperties properties;
    private final List<AgentOutboxPublisher> publishers;
    private final String leaseOwner = UUID.randomUUID().toString();

    public AgentActionOutboxWorker(AgentActionOutboxService outboxService,
                                   AgentActionOutboxMapper outboxMapper,
                                   AgentActionIdempotencyMapper idempotencyMapper,
                                   AgentProperties properties,
                                   List<AgentOutboxPublisher> publishers) {
        this.outboxService = outboxService;
        this.outboxMapper = outboxMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.properties = properties;
        this.publishers = publishers;
    }

    @Scheduled(fixedDelayString = "${app.agent.outbox-poll-interval-ms:1000}")
    public void dispatch() {
        for (AgentActionOutbox event : outboxService.claimBatch(
                leaseOwner, properties.getOutboxBatchSize(), properties.getOutboxLeaseSeconds())) {
            try {
                AgentOutboxPublisher publisher = publishers.stream()
                        .filter(item -> item.supports(event.getEventType()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "no idempotent publisher for event type " + event.getEventType()));
                publisher.publish(event.getActionId(), event.getEventType(), event.getPayloadJson());
                outboxService.markPublished(event.getEventId(), leaseOwner);
            } catch (Exception exception) {
                log.warn("Agent outbox publish failed: eventId={}", event.getEventId(), exception);
                outboxService.markRetry(event.getEventId(), leaseOwner,
                        properties.getOutboxRetrySeconds(), exception.getClass().getSimpleName());
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.agent.action-cleanup-interval-ms:3600000}")
    @Transactional(transactionManager = "mysqlTransactionManager",
            propagation = Propagation.REQUIRES_NEW)
    public void redactExpiredPayloads() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusDays(Math.max(1, properties.getActionPayloadRetentionDays()));
        int batchSize = Math.max(1, Math.min(properties.getOutboxBatchSize(), 100));
        idempotencyMapper.redactCompletedBefore(cutoff, batchSize);
        outboxMapper.redactPublishedBefore(cutoff, batchSize);
    }
}
