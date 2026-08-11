ALTER TABLE agent_thread
    ADD COLUMN summary_through_message_id BIGINT NOT NULL DEFAULT 0;

ALTER TABLE agent_thread
    ADD CONSTRAINT agent_thread_summary_cursor_nonnegative
    CHECK (summary_through_message_id >= 0);

-- 旧摘要没有可靠游标，无法判断其中已包含哪些消息。保留原始消息，重建摘要。
UPDATE agent_thread
SET summary = '',
    summary_through_message_id = 0;

CREATE TABLE agent_turn (
    turn_id UUID PRIMARY KEY,
    client_turn_id TEXT NOT NULL UNIQUE,
    thread_id TEXT NOT NULL REFERENCES agent_thread(thread_id) ON DELETE CASCADE,
    task_type TEXT NOT NULL CHECK (
        task_type IN ('CHAT', 'TEACHING_PLAN', 'RESOURCE_DISCOVERY')
    ),
    request_hash TEXT NOT NULL CHECK (length(request_hash) = 64),
    request_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL CHECK (
        status IN ('running', 'completed', 'interrupted', 'failed', 'cancelled')
    ),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    graph_version TEXT,
    checkpoint_namespace TEXT,
    lease_owner TEXT,
    lease_expires_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    partial_answer TEXT NOT NULL DEFAULT '',
    response_json JSONB,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    last_error_code TEXT,
    cancel_requested_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    checkpoint_cleanup_claimed_at TIMESTAMPTZ,
    checkpoint_deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agent_turn_lease_consistent CHECK (
        (lease_owner IS NULL) = (lease_expires_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_agent_turn_running_thread
    ON agent_turn(thread_id)
    WHERE status = 'running';
CREATE INDEX idx_agent_turn_thread_created
    ON agent_turn(thread_id, created_at DESC);
CREATE INDEX idx_agent_turn_cleanup
    ON agent_turn(status, finished_at, checkpoint_deleted_at)
    WHERE status IN ('completed', 'cancelled', 'failed', 'interrupted')
      AND checkpoint_deleted_at IS NULL;

ALTER TABLE agent_message
    ADD COLUMN turn_id UUID REFERENCES agent_turn(turn_id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_agent_message_turn_user
    ON agent_message(turn_id)
    WHERE turn_id IS NOT NULL AND role = 'user';
CREATE UNIQUE INDEX uq_agent_message_turn_assistant
    ON agent_message(turn_id)
    WHERE turn_id IS NOT NULL AND role = 'assistant';
CREATE INDEX idx_agent_message_turn
    ON agent_message(turn_id)
    WHERE turn_id IS NOT NULL;

ALTER TABLE agent_tool_audit
    ADD COLUMN turn_id UUID REFERENCES agent_turn(turn_id) ON DELETE SET NULL,
    ADD COLUMN tool_call_id TEXT;

CREATE UNIQUE INDEX uq_agent_tool_audit_turn_call
    ON agent_tool_audit(turn_id, tool_call_id)
    WHERE turn_id IS NOT NULL AND tool_call_id IS NOT NULL;
CREATE INDEX idx_agent_tool_audit_turn
    ON agent_tool_audit(turn_id, created_at)
    WHERE turn_id IS NOT NULL;

ALTER TABLE agent_memory
    ADD COLUMN source_turn_id UUID REFERENCES agent_turn(turn_id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_agent_memory_source_turn
    ON agent_memory(
        source_turn_id,
        memory_type,
        COALESCE(field_key, ''),
        md5(content)
    )
    WHERE source_turn_id IS NOT NULL;
