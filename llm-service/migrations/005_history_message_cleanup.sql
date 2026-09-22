-- 004 may already be applied in local environments; keep it immutable.
-- This migration backfills old messages and records message-level vector cleanup.

CREATE TABLE IF NOT EXISTS agent_history_message_cleanup_job (
    -- The source row is already gone when this job is inserted, so this ID
    -- intentionally has no foreign key back to agent_message.
    message_id BIGINT PRIMARY KEY,
    status TEXT NOT NULL CHECK (status IN ('pending', 'processing', 'completed', 'failed')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner TEXT,
    lease_expires_at TIMESTAMPTZ,
    last_error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_history_message_cleanup_ready
    ON agent_history_message_cleanup_job(status, available_at)
    WHERE status IN ('pending', 'failed');

INSERT INTO agent_history_index_job(message_id, status)
SELECT m.id, 'pending'
FROM agent_message m
LEFT JOIN agent_history_index_job j ON j.message_id = m.id
WHERE j.message_id IS NULL
ON CONFLICT (message_id) DO NOTHING;

CREATE OR REPLACE FUNCTION enqueue_history_message_cleanup()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO agent_history_message_cleanup_job(message_id, status)
    VALUES (OLD.id, 'pending')
    ON CONFLICT (message_id) DO UPDATE
    SET status = 'pending',
        available_at = CURRENT_TIMESTAMP,
        lease_owner = NULL,
        lease_expires_at = NULL,
        last_error_code = NULL,
        updated_at = CURRENT_TIMESTAMP;
    RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_message_history_cleanup ON agent_message;
CREATE TRIGGER trg_agent_message_history_cleanup
AFTER DELETE ON agent_message
FOR EACH ROW EXECUTE FUNCTION enqueue_history_message_cleanup();
