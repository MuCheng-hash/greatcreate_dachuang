package com.redculture.platform.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.mapper.AgentActionOutboxMapper;
import com.redculture.platform.entity.AgentActionOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
public class AgentActionOutboxService {

    private final AgentActionOutboxMapper mapper;
    private final ObjectMapper objectMapper;

    public AgentActionOutboxService(AgentActionOutboxMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(
            transactionManager = "mysqlTransactionManager",
            propagation = Propagation.MANDATORY
    )
    public void enqueue(String actionId, String eventType, Map<String, Object> payload) {
        if (!StringUtils.hasText(actionId) || !StringUtils.hasText(eventType)) {
            throw new IllegalArgumentException("actionId and eventType are required");
        }
        try {
            mapper.insertIfAbsent(
                    UUID.randomUUID().toString(), actionId, eventType,
                    objectMapper.writeValueAsString(payload == null ? Map.of() : payload)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("outbox payload is not serializable", exception);
        }
    }

    @Transactional(
            transactionManager = "mysqlTransactionManager",
            propagation = Propagation.REQUIRES_NEW
    )
    public List<AgentActionOutbox> claimBatch(String leaseOwner, int batchSize,
                                               int leaseSeconds) {
        int boundedBatch = Math.max(1, Math.min(batchSize, 100));
        int boundedLease = Math.max(5, leaseSeconds);
        List<AgentActionOutbox> candidates = mapper.selectClaimableForUpdate(boundedBatch);
        return candidates.stream()
                .filter(item -> mapper.markClaimed(
                        item.getEventId(), leaseOwner, boundedLease
                ) == 1)
                .toList();
    }

    @Transactional(transactionManager = "mysqlTransactionManager",
            propagation = Propagation.REQUIRES_NEW)
    public void markPublished(String eventId, String leaseOwner) {
        if (mapper.markPublished(eventId, leaseOwner) != 1) {
            throw new IllegalStateException("outbox lease was lost before publish commit");
        }
    }

    @Transactional(transactionManager = "mysqlTransactionManager",
            propagation = Propagation.REQUIRES_NEW)
    public void markRetry(String eventId, String leaseOwner, int retrySeconds,
                          String errorSummary) {
        String boundedError = errorSummary == null ? "publish_failed"
                : errorSummary.substring(0, Math.min(errorSummary.length(), 1000));
        mapper.markRetry(eventId, leaseOwner, Math.max(1, retrySeconds), boundedError);
    }
}
