-- Agent write-action idempotency and transactional outbox.
-- Run after the existing platform schema and before enabling any Agent write tool.
CREATE TABLE IF NOT EXISTS agent_action_idempotency (
    action_id VARCHAR(96) PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    operation VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    request_json JSON NULL,
    status VARCHAR(24) NOT NULL,
    response_json JSON NULL,
    resource_reference VARCHAR(255) NULL,
    completed_at DATETIME NULL,
    payload_redacted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_action_idempotency_turn (turn_id, created_at),
    INDEX idx_agent_action_idempotency_cleanup (completed_at, payload_redacted_at)
);

CREATE TABLE IF NOT EXISTS agent_action_outbox (
    event_id VARCHAR(64) PRIMARY KEY,
    action_id VARCHAR(96) NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(128) NULL,
    lease_expires_at DATETIME NULL,
    published_at DATETIME NULL,
    error_summary VARCHAR(1000) NULL,
    payload_redacted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_action_outbox_action_type (action_id, event_type),
    INDEX idx_agent_action_outbox_claim (status, next_attempt_at, lease_expires_at),
    INDEX idx_agent_action_outbox_cleanup (published_at, payload_redacted_at),
    CONSTRAINT fk_agent_action_outbox_action
        FOREIGN KEY (action_id) REFERENCES agent_action_idempotency(action_id)
        ON DELETE RESTRICT
);
