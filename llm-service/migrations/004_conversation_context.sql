ALTER TABLE agent_thread
    ADD COLUMN summary_state_json JSONB NOT NULL DEFAULT
    '{"schemaVersion":1,"goals":[],"hardConstraints":[],"decisions":[],"openQuestions":[],"facts":[],"legacyNotes":[]}'::jsonb;

-- 旧文本摘要仍保留在兼容字段中，同时迁入可审计的结构化状态，避免升级时丢失。
UPDATE agent_thread
SET summary_state_json = jsonb_build_object(
    'schemaVersion', 1,
    'goals', '[]'::jsonb,
    'hardConstraints', '[]'::jsonb,
    'decisions', '[]'::jsonb,
    'openQuestions', '[]'::jsonb,
    'facts', '[]'::jsonb,
    'legacyNotes', CASE
        WHEN btrim(summary) <> '' THEN jsonb_build_array(summary)
        ELSE '[]'::jsonb
    END
)
WHERE summary_state_json = '{"schemaVersion":1,"goals":[],"hardConstraints":[],"decisions":[],"openQuestions":[],"facts":[],"legacyNotes":[]}'::jsonb;

CREATE TABLE agent_history_index_job (
    message_id BIGINT PRIMARY KEY REFERENCES agent_message(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (
        status IN ('pending', 'processing', 'completed', 'skipped', 'failed')
    ),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner TEXT,
    lease_expires_at TIMESTAMPTZ,
    indexed_content_sha256 CHAR(64),
    last_error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agent_history_index_job_lease_consistent CHECK (
        (lease_owner IS NULL) = (lease_expires_at IS NULL)
    )
);

CREATE INDEX idx_agent_history_index_job_ready
    ON agent_history_index_job(status, available_at)
    WHERE status IN ('pending', 'failed');

CREATE TABLE agent_history_vector_cleanup_job (
    thread_id TEXT PRIMARY KEY,
    status TEXT NOT NULL CHECK (status IN ('pending', 'processing', 'completed', 'failed')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner TEXT,
    lease_expires_at TIMESTAMPTZ,
    last_error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agent_history_cleanup_job_lease_consistent CHECK (
        (lease_owner IS NULL) = (lease_expires_at IS NULL)
    )
);

CREATE INDEX idx_agent_history_cleanup_job_ready
    ON agent_history_vector_cleanup_job(status, available_at)
    WHERE status IN ('pending', 'failed');

CREATE OR REPLACE FUNCTION enqueue_agent_history_index_job()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND NEW.content IS NOT DISTINCT FROM OLD.content
       AND NEW.metadata_json IS NOT DISTINCT FROM OLD.metadata_json THEN
        RETURN NEW;
    END IF;

    INSERT INTO agent_history_index_job(
        message_id, status, available_at, created_at, updated_at
    ) VALUES (
        NEW.id, 'pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    )
    ON CONFLICT (message_id) DO UPDATE
    SET status = 'pending',
        attempt_count = 0,
        available_at = CURRENT_TIMESTAMP,
        lease_owner = NULL,
        lease_expires_at = NULL,
        indexed_content_sha256 = NULL,
        last_error_code = NULL,
        updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_agent_message_history_index
AFTER INSERT OR UPDATE OF content, metadata_json ON agent_message
FOR EACH ROW
EXECUTE FUNCTION enqueue_agent_history_index_job();

CREATE OR REPLACE FUNCTION enqueue_agent_history_vector_cleanup_job()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO agent_history_vector_cleanup_job(
        thread_id, status, available_at, created_at, updated_at
    ) VALUES (
        OLD.thread_id, 'pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    )
    ON CONFLICT (thread_id) DO UPDATE
    SET status = 'pending',
        available_at = CURRENT_TIMESTAMP,
        lease_owner = NULL,
        lease_expires_at = NULL,
        last_error_code = NULL,
        updated_at = CURRENT_TIMESTAMP;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_agent_thread_history_cleanup
AFTER DELETE ON agent_thread
FOR EACH ROW
EXECUTE FUNCTION enqueue_agent_history_vector_cleanup_job();
