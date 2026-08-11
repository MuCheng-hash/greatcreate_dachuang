ALTER TABLE agent_turn
    DROP CONSTRAINT IF EXISTS agent_turn_status_check;

ALTER TABLE agent_turn
    ADD CONSTRAINT agent_turn_status_check CHECK (
        status IN (
            'running', 'awaiting_confirmation', 'completed',
            'interrupted', 'failed', 'cancelled'
        )
    );

CREATE TABLE agent_action (
    action_id UUID PRIMARY KEY,
    turn_id UUID NOT NULL REFERENCES agent_turn(turn_id) ON DELETE CASCADE,
    logical_call_id TEXT NOT NULL,
    tool_name TEXT NOT NULL,
    arguments_hash CHAR(64) NOT NULL CHECK (length(arguments_hash) = 64),
    arguments_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    sanitized_arguments_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    risk_level TEXT NOT NULL CHECK (risk_level IN ('LOW', 'HIGH')),
    requires_confirmation BOOLEAN NOT NULL DEFAULT TRUE,
    status TEXT NOT NULL CHECK (
        status IN (
            'pending_confirmation', 'approved', 'executing',
            'succeeded', 'rejected', 'failed', 'expired'
        )
    ),
    decision TEXT CHECK (decision IS NULL OR decision IN ('approve', 'reject')),
    decision_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    result_json JSONB,
    result_summary TEXT,
    resource_reference TEXT,
    error_code TEXT,
    payload_redacted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_action_turn_call UNIQUE (turn_id, logical_call_id),
    CONSTRAINT agent_action_decision_consistent CHECK (
        (decision IS NULL AND decision_at IS NULL)
        OR (decision IS NOT NULL AND decision_at IS NOT NULL)
    )
);

CREATE INDEX idx_agent_action_turn_created
    ON agent_action(turn_id, created_at DESC);
CREATE INDEX idx_agent_action_pending_expiry
    ON agent_action(status, expires_at)
    WHERE status = 'pending_confirmation';
CREATE INDEX idx_agent_action_redaction
    ON agent_action(finished_at, payload_redacted_at)
    WHERE status IN ('succeeded', 'rejected', 'failed', 'expired')
      AND payload_redacted_at IS NULL;
