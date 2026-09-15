-- Apply once after add_knowledge_ingestion_mvp.sql; no existing rows are deleted.
ALTER TABLE knowledge_ingest_job
    ADD COLUMN generation BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN execution_attempts INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS knowledge_ingest_outbox (
    event_id CHAR(36) PRIMARY KEY,
    job_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner CHAR(36) NULL,
    lease_expires_at DATETIME NULL,
    error_summary VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    UNIQUE KEY uk_ingest_event_generation(job_id, generation),
    INDEX idx_ingest_outbox_ready(status, next_attempt_at, lease_expires_at),
    CONSTRAINT fk_ingest_outbox_job FOREIGN KEY(job_id) REFERENCES knowledge_ingest_job(id) ON DELETE CASCADE
);
