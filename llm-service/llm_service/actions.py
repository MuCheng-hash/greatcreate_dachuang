from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from psycopg.types.json import Jsonb

from .database import Database


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class ActionConflictError(RuntimeError):
    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(message)


@dataclass(frozen=True, slots=True)
class AgentActionRecord:
    action_id: str
    turn_id: str
    client_turn_id: str
    thread_id: str
    owner_id: str
    scope_type: str
    scope_id: str
    logical_call_id: str
    tool_name: str
    arguments_hash: str
    sanitized_arguments: dict[str, Any]
    risk_level: str
    requires_confirmation: bool
    status: str
    decision: str | None
    expires_at: datetime
    result_summary: str | None
    resource_reference: str | None
    error_code: str | None
    created_at: datetime
    updated_at: datetime


class AgentActionRepository:
    """副作用动作的唯一 PostgreSQL 状态边界。"""

    def __init__(self, database: Database, confirmation_minutes: int = 15):
        self.database = database
        self.confirmation_minutes = max(1, confirmation_minutes)

    @staticmethod
    def arguments_hash(tool_name: str, arguments: dict[str, Any]) -> str:
        canonical = json.dumps(
            {"tool": tool_name, "arguments": arguments},
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    async def create_or_get(
        self,
        *,
        turn_id: str,
        logical_call_id: str,
        tool_name: str,
        arguments: dict[str, Any],
        sanitized_arguments: dict[str, Any],
        risk_level: str = "HIGH",
        requires_confirmation: bool = True,
    ) -> AgentActionRecord:
        if risk_level not in {"LOW", "HIGH"}:
            raise ValueError("invalid action risk level")
        digest = self.arguments_hash(tool_name, arguments)
        now = utc_now()
        async with self.database.transaction() as connection:
            await connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                (f"agent-action:{turn_id}:{logical_call_id}",),
            )
            existing = await (
                await connection.execute(
                    self._select_sql(
                        "a.turn_id = %s AND a.logical_call_id = %s", for_update=True
                    ),
                    (turn_id, logical_call_id),
                )
            ).fetchone()
            if existing is not None:
                if (
                    str(existing["tool_name"]) != tool_name
                    or str(existing["arguments_hash"]) != digest
                ):
                    raise ActionConflictError(
                        "action_conflict", "logical action payload does not match"
                    )
                return self._from_row(existing)
            action_id = str(uuid.uuid4())
            status = "pending_confirmation" if requires_confirmation else "approved"
            row = await (
                await connection.execute(
                    """
                    INSERT INTO agent_action(
                        action_id, turn_id, logical_call_id, tool_name,
                        arguments_hash, arguments_json, sanitized_arguments_json,
                        risk_level, requires_confirmation, status, expires_at,
                        created_at, updated_at
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    RETURNING *
                    """,
                    (
                        action_id,
                        turn_id,
                        logical_call_id,
                        tool_name,
                        digest,
                        Jsonb(arguments),
                        Jsonb(sanitized_arguments),
                        risk_level,
                        requires_confirmation,
                        status,
                        now + timedelta(minutes=self.confirmation_minutes),
                        now,
                        now,
                    ),
                )
            ).fetchone()
            if requires_confirmation:
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET status = 'awaiting_confirmation',
                        lease_owner = NULL, lease_expires_at = NULL,
                        retryable = TRUE, updated_at = %s
                    WHERE turn_id = %s AND status = 'running'
                    """,
                    (now, turn_id),
                )
            joined = await (
                await connection.execute(
                    self._select_sql("a.action_id = %s"), (action_id,)
                )
            ).fetchone()
        return self._from_row(joined or row or {})

    async def get_for_scope(
        self,
        action_id: str,
        owner_id: str,
        scope_type: str,
        scope_id: str | int,
    ) -> AgentActionRecord | None:
        await self.expire_pending(action_id=action_id)
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    self._select_sql("a.action_id = %s"), (action_id,)
                )
            ).fetchone()
        if row is None:
            return None
        self._validate_scope(row, owner_id, scope_type, str(scope_id))
        return self._from_row(row)

    async def pending_for_turn(self, turn_id: str) -> AgentActionRecord | None:
        await self.expire_pending(turn_id=turn_id)
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    self._select_sql(
                        "a.turn_id = %s AND a.status = 'pending_confirmation'"
                    ),
                    (turn_id,),
                )
            ).fetchone()
        return self._from_row(row) if row is not None else None

    async def resumable_for_turn(self, turn_id: str) -> AgentActionRecord | None:
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    self._select_sql(
                        "a.turn_id = %s AND a.status IN ('approved', 'rejected', 'executing')"
                        " ORDER BY a.updated_at DESC LIMIT 1"
                    ),
                    (turn_id,),
                )
            ).fetchone()
        return self._from_row(row) if row is not None else None

    async def decide(
        self,
        *,
        action_id: str,
        decision: str,
        owner_id: str,
        scope_type: str,
        scope_id: str | int,
    ) -> AgentActionRecord:
        if decision not in {"approve", "reject"}:
            raise ValueError("decision must be approve or reject")
        now = utc_now()
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    self._select_sql("a.action_id = %s", for_update=True),
                    (action_id,),
                )
            ).fetchone()
            if row is None:
                raise LookupError(action_id)
            self._validate_scope(row, owner_id, scope_type, str(scope_id))
            status = str(row["status"])
            if status == "pending_confirmation" and row["expires_at"] <= now:
                row = await self._expire_locked(connection, row, now)
                status = "expired"
            target = "approved" if decision == "approve" else "rejected"
            if status == target and str(row.get("decision") or "") == decision:
                return self._from_row(row)
            if status != "pending_confirmation":
                raise ActionConflictError(
                    "action_expired" if status == "expired" else "action_conflict",
                    f"action cannot be decided from status {status}",
                )
            updated = await (
                await connection.execute(
                    """
                    UPDATE agent_action
                    SET status = %s, decision = %s, decision_at = %s,
                        finished_at = CASE WHEN %s = 'reject' THEN %s ELSE finished_at END,
                        updated_at = %s
                    WHERE action_id = %s
                    RETURNING *
                    """,
                    (target, decision, now, decision, now, now, action_id),
                )
            ).fetchone()
            if decision == "approve":
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET status = 'interrupted', retryable = TRUE, updated_at = %s
                    WHERE turn_id = %s AND status = 'awaiting_confirmation'
                    """,
                    (now, row["turn_id"]),
                )
            else:
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET status = 'interrupted', retryable = TRUE, updated_at = %s
                    WHERE turn_id = %s AND status = 'awaiting_confirmation'
                    """,
                    (now, row["turn_id"]),
                )
        return self._from_row({**row, **(updated or {})})

    async def mark_executing(self, action_id: str) -> AgentActionRecord:
        return await self._transition(
            action_id, from_statuses={"approved"}, to_status="executing", started=True
        )

    async def mark_succeeded(
        self,
        action_id: str,
        *,
        result: dict[str, Any] | None = None,
        result_summary: str = "",
        resource_reference: str | None = None,
    ) -> AgentActionRecord:
        now = utc_now()
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    UPDATE agent_action
                    SET status = 'succeeded', result_json = %s,
                        result_summary = %s, resource_reference = %s,
                        finished_at = %s, updated_at = %s
                    WHERE action_id = %s AND status = 'executing'
                    RETURNING *
                    """,
                    (
                        Jsonb(result) if result is not None else None,
                        result_summary,
                        resource_reference,
                        now,
                        now,
                        action_id,
                    ),
                )
            ).fetchone()
        if row is None:
            raise ActionConflictError("action_conflict", "action is not executing")
        return await self._joined(action_id)

    async def mark_failed(self, action_id: str, error_code: str) -> AgentActionRecord:
        now = utc_now()
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    UPDATE agent_action
                    SET status = 'failed', error_code = %s,
                        finished_at = %s, updated_at = %s
                    WHERE action_id = %s AND status = 'executing'
                    RETURNING action_id
                    """,
                    (error_code, now, now, action_id),
                )
            ).fetchone()
        if row is None:
            raise ActionConflictError("action_conflict", "action is not executing")
        return await self._joined(action_id)

    async def expire_pending(
        self, *, action_id: str | None = None, turn_id: str | None = None
    ) -> int:
        now = utc_now()
        clauses = ["status = 'pending_confirmation'", "expires_at <= %s"]
        params: list[Any] = [now]
        if action_id is not None:
            clauses.append("action_id = %s")
            params.append(action_id)
        if turn_id is not None:
            clauses.append("turn_id = %s")
            params.append(turn_id)
        async with self.database.transaction() as connection:
            rows = await (
                await connection.execute(
                    f"""
                    UPDATE agent_action
                    SET status = 'expired', finished_at = %s, updated_at = %s
                    WHERE {' AND '.join(clauses)}
                    RETURNING turn_id
                    """,
                    (now, now, *params),
                )
            ).fetchall()
            for row in rows:
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET status = 'interrupted', retryable = FALSE,
                        finished_at = COALESCE(finished_at, %s), updated_at = %s
                    WHERE turn_id = %s AND status = 'awaiting_confirmation'
                    """,
                    (now, now, row["turn_id"]),
                )
        return len(rows)

    async def redact_finished(self, retention_days: int = 30, batch_size: int = 100) -> int:
        cutoff = utc_now() - timedelta(days=max(1, retention_days))
        now = utc_now()
        async with self.database.transaction() as connection:
            rows = await (
                await connection.execute(
                    """
                    SELECT action_id FROM agent_action
                    WHERE status IN ('succeeded', 'rejected', 'failed', 'expired')
                      AND finished_at < %s AND payload_redacted_at IS NULL
                    ORDER BY finished_at
                    LIMIT %s FOR UPDATE SKIP LOCKED
                    """,
                    (cutoff, max(1, min(batch_size, 500))),
                )
            ).fetchall()
            ids = [str(row["action_id"]) for row in rows]
            if ids:
                await connection.execute(
                    """
                    UPDATE agent_action
                    SET arguments_json = '{}'::jsonb,
                        sanitized_arguments_json = '{}'::jsonb,
                        result_json = NULL, result_summary = NULL,
                        payload_redacted_at = %s, updated_at = %s
                    WHERE action_id = ANY(%s::uuid[])
                    """,
                    (now, now, ids),
                )
        return len(ids)

    async def _transition(
        self,
        action_id: str,
        *,
        from_statuses: set[str],
        to_status: str,
        started: bool = False,
    ) -> AgentActionRecord:
        now = utc_now()
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    UPDATE agent_action
                    SET status = %s,
                        started_at = CASE WHEN %s THEN COALESCE(started_at, %s) ELSE started_at END,
                        updated_at = %s
                    WHERE action_id = %s AND status = ANY(%s)
                    RETURNING action_id
                    """,
                    (to_status, started, now, now, action_id, list(from_statuses)),
                )
            ).fetchone()
        if row is None:
            raise ActionConflictError("action_conflict", "invalid action transition")
        return await self._joined(action_id)

    async def _joined(self, action_id: str) -> AgentActionRecord:
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    self._select_sql("a.action_id = %s"), (action_id,)
                )
            ).fetchone()
        if row is None:
            raise LookupError(action_id)
        return self._from_row(row)

    @staticmethod
    async def _expire_locked(connection: Any, row: dict[str, Any], now: datetime) -> dict[str, Any]:
        updated = await (
            await connection.execute(
                """
                UPDATE agent_action SET status = 'expired', finished_at = %s, updated_at = %s
                WHERE action_id = %s RETURNING *
                """,
                (now, now, row["action_id"]),
            )
        ).fetchone()
        await connection.execute(
            """
            UPDATE agent_turn
            SET status = 'interrupted', retryable = FALSE,
                finished_at = COALESCE(finished_at, %s), updated_at = %s
            WHERE turn_id = %s AND status = 'awaiting_confirmation'
            """,
            (now, now, row["turn_id"]),
        )
        return {**row, **(updated or {})}

    @staticmethod
    def _validate_scope(
        row: dict[str, Any], owner_id: str, scope_type: str, scope_id: str
    ) -> None:
        if (
            str(row["owner_id"]) != owner_id
            or str(row["scope_type"]) != scope_type
            or str(row["scope_id"]) != scope_id
        ):
            raise PermissionError("action scope does not match")

    @staticmethod
    def _select_sql(predicate: str, *, for_update: bool = False) -> str:
        suffix = " FOR UPDATE OF a" if for_update else ""
        return f"""
            SELECT a.*, tr.client_turn_id, tr.thread_id,
                   t.owner_id, t.scope_type, t.scope_id
            FROM agent_action a
            INNER JOIN agent_turn tr ON tr.turn_id = a.turn_id
            INNER JOIN agent_thread t ON t.thread_id = tr.thread_id
            WHERE {predicate}{suffix}
        """

    @staticmethod
    def _from_row(row: dict[str, Any]) -> AgentActionRecord:
        sanitized = row.get("sanitized_arguments_json")
        return AgentActionRecord(
            action_id=str(row["action_id"]),
            turn_id=str(row["turn_id"]),
            client_turn_id=str(row["client_turn_id"]),
            thread_id=str(row["thread_id"]),
            owner_id=str(row["owner_id"]),
            scope_type=str(row["scope_type"]),
            scope_id=str(row["scope_id"]),
            logical_call_id=str(row["logical_call_id"]),
            tool_name=str(row["tool_name"]),
            arguments_hash=str(row["arguments_hash"]),
            sanitized_arguments=dict(sanitized) if isinstance(sanitized, dict) else {},
            risk_level=str(row["risk_level"]),
            requires_confirmation=bool(row["requires_confirmation"]),
            status=str(row["status"]),
            decision=str(row["decision"]) if row.get("decision") is not None else None,
            expires_at=row["expires_at"],
            result_summary=(
                str(row["result_summary"])
                if row.get("result_summary") is not None
                else None
            ),
            resource_reference=(
                str(row["resource_reference"])
                if row.get("resource_reference") is not None
                else None
            ),
            error_code=str(row["error_code"]) if row.get("error_code") is not None else None,
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )
