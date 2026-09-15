package com.redculture.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name="app.knowledge-mq.enabled", havingValue="true", matchIfMissing=true)
public class KnowledgeOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeOutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final KnowledgeIngestTransactions transactions;
    private final KnowledgeMessageSender sender;
    private final ObjectMapper json;

    public KnowledgeOutboxPublisher(JdbcTemplate jdbc, KnowledgeIngestTransactions transactions,
                                    KnowledgeMessageSender sender, ObjectMapper json) {
        this.jdbc=jdbc; this.transactions=transactions; this.sender=sender; this.json=json;
    }

    @Scheduled(fixedDelayString="${app.knowledge-mq.poll-ms:1000}")
    public void publish() {
        // Claim one at a time so the lease cannot expire while waiting in a local batch.
        String owner = UUID.randomUUID().toString();
        Map<String,Object> event = transactions.run(() -> {
            var rows = jdbc.queryForList("""
                SELECT * FROM knowledge_ingest_outbox
                WHERE (status IN ('PENDING','RETRY') AND next_attempt_at<=NOW())
                   OR (status='PROCESSING' AND lease_expires_at<NOW())
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
            if (rows.isEmpty()) return null;
            var row=rows.getFirst();
            jdbc.update("UPDATE knowledge_ingest_outbox SET status='PROCESSING', lease_owner=?, lease_expires_at=DATE_ADD(NOW(), INTERVAL 60 SECOND) WHERE event_id=?", owner,row.get("event_id"));
            return row;
        });
        if (event == null) return;
        Object id=event.get("event_id");
        try {
            sender.send(id.toString(), json.writeValueAsBytes(Map.of("schemaVersion",1,"eventId",id,
                "jobId",event.get("job_id"),"documentId",event.get("document_id"),"generation",event.get("generation"))));
            jdbc.update("UPDATE knowledge_ingest_outbox SET status='PUBLISHED', published_at=NOW(), lease_owner=NULL, lease_expires_at=NULL, error_summary=NULL WHERE event_id=? AND lease_owner=?",id,owner);
        } catch (Exception error) {
            int attempts=((Number)event.get("attempt_count")).intValue();
            int delay= Math.min(60, 1 << Math.min(attempts,6));
            jdbc.update("UPDATE knowledge_ingest_outbox SET status='RETRY', attempt_count=attempt_count+1, next_attempt_at=DATE_ADD(NOW(), INTERVAL ? SECOND), lease_owner=NULL, lease_expires_at=NULL, error_summary=? WHERE event_id=? AND lease_owner=?",delay,error.getClass().getSimpleName(),id,owner);
            log.warn("Knowledge event publish deferred: eventId={}, error={}", id,error.getClass().getSimpleName());
        }
    }
}
