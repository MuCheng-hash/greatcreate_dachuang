from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from psycopg.types.json import Jsonb

from .database import Database


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class TurnConflictError(RuntimeError):
    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(message)


class TurnLeaseLostError(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class AgentTurnRecord:
    turn_id: str
    client_turn_id: str
    thread_id: str
    owner_id: str
    scope_type: str
    scope_id: str
    task_type: str
    request_hash: str
    request_summary: dict[str, Any]
    status: str
    attempt_count: int
    graph_version: str | None
    checkpoint_namespace: str | None
    lease_owner: str | None
    lease_expires_at: datetime | None
    partial_answer: str
    response: dict[str, Any] | None
    retryable: bool
    cancel_requested_at: datetime | None
    created_at: datetime
    updated_at: datetime
    finished_at: datetime | None


@dataclass(frozen=True, slots=True)
class TurnRegistration:
    turn: AgentTurnRecord
    resumed: bool


class AgentTurnRepository:
    """轮次注册、租约和最终消息提交的原子 PostgreSQL 边界。"""

    def __init__(self, database: Database):
        self.database = database

    async def register(
        self,
        *,
        client_turn_id: str,
        requested_thread_id: str | None,
        owner_id: str,
        scope_type: str,
        scope_id: str | int,
        task_type: str,
        request_hash: str,
        request_summary: dict[str, Any],
        lease_owner: str,
        lease_seconds: int,
    ) -> TurnRegistration:
        now = utc_now()
        lease_expires_at = now + timedelta(seconds=lease_seconds)
        scope_value = str(scope_id)
        async with self.database.transaction() as connection:
            await connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                (f"agent-turn-client:{client_turn_id}",),
            )
            existing = await (
                await connection.execute(
                    self._select_sql("tr.client_turn_id = %s", for_update=True),
                    (client_turn_id,),
                )
            ).fetchone()
            if existing is not None:
                self._validate_identity(
                    existing,
                    requested_thread_id,
                    owner_id,
                    scope_type,
                    scope_value,
                    task_type,
                    request_hash,
                )
                status = str(existing["status"])
                if status == "completed":
                    return TurnRegistration(self._from_row(existing), resumed=True)
                if status == "awaiting_confirmation":
                    raise TurnConflictError(
                        "action_confirmation_required",
                        "the requested turn is waiting for action confirmation",
                    )
                if (
                    status == "running"
                    and existing.get("cancel_requested_at")
                    and not self._lease_valid(existing, now)
                ):
                    cancelled = await self._finalize_expired_cancel(
                        connection, existing, now
                    )
                    return TurnRegistration(
                        self._from_row(cancelled), resumed=True
                    )
                if status == "cancelled" or existing.get("cancel_requested_at"):
                    raise TurnConflictError(
                        "turn_cancelled", "the requested turn was cancelled"
                    )
                if status == "running" and self._lease_valid(existing, now):
                    raise TurnConflictError(
                        "turn_in_progress", "the requested turn is already running"
                    )
                if status == "failed" and not bool(existing.get("retryable")):
                    raise TurnConflictError(
                        "client_turn_conflict", "the requested turn cannot be retried"
                    )
                thread_id = str(existing["thread_id"])
                await self._lock_thread(connection, thread_id)
                competing = await (
                    await connection.execute(
                        """
                        SELECT client_turn_id
                        FROM agent_turn
                        WHERE thread_id = %s
                          AND client_turn_id <> %s
                          AND (
                              status IN ('running', 'awaiting_confirmation', 'interrupted')
                              OR (status = 'failed' AND retryable)
                          )
                        LIMIT 1
                        FOR UPDATE
                        """,
                        (thread_id, client_turn_id),
                    )
                ).fetchone()
                if competing is not None:
                    raise TurnConflictError(
                        "thread_busy", "another unfinished turn owns this thread"
                    )
                row = await (
                    await connection.execute(
                        """
                        UPDATE agent_turn
                        SET status = 'running',
                            attempt_count = attempt_count + 1,
                            lease_owner = %s,
                            lease_expires_at = %s,
                            heartbeat_at = %s,
                            retryable = FALSE,
                            last_error_code = NULL,
                            finished_at = NULL,
                            updated_at = %s
                        WHERE turn_id = %s
                        RETURNING *
                        """,
                        (
                            lease_owner,
                            lease_expires_at,
                            now,
                            now,
                            existing["turn_id"],
                        ),
                    )
                ).fetchone()
                return TurnRegistration(
                    self._from_row({**existing, **(row or {})}), resumed=True
                )

            if requested_thread_id:
                thread = await (
                    await connection.execute(
                        """
                        SELECT * FROM agent_thread
                        WHERE thread_id = %s
                        FOR UPDATE
                        """,
                        (requested_thread_id,),
                    )
                ).fetchone()
                if thread is None:
                    raise LookupError(requested_thread_id)
                self._validate_thread_scope(
                    thread, owner_id, scope_type, scope_value
                )
                if str(thread["status"]) != "active":
                    raise PermissionError("thread is archived")
                thread_id = requested_thread_id
            else:
                thread_id = str(uuid.uuid4())
                await connection.execute(
                    """
                    INSERT INTO agent_thread(
                        thread_id, owner_id, scope_type, scope_id, status,
                        summary, created_at, updated_at
                    ) VALUES (%s, %s, %s, %s, 'active', '', %s, %s)
                    """,
                    (thread_id, owner_id, scope_type, scope_value, now, now),
                )

            await self._lock_thread(connection, thread_id)
            competing = await (
                await connection.execute(
                    """
                    SELECT client_turn_id
                    FROM agent_turn
                    WHERE thread_id = %s
                      AND (
                          status IN ('running', 'awaiting_confirmation', 'interrupted')
                          OR (status = 'failed' AND retryable)
                      )
                    LIMIT 1
                    FOR UPDATE
                    """,
                    (thread_id,),
                )
            ).fetchone()
            if competing is not None:
                raise TurnConflictError(
                    "thread_busy", "another unfinished turn owns this thread"
                )

            turn_id = str(uuid.uuid4())
            row = await (
                await connection.execute(
                    """
                    INSERT INTO agent_turn(
                        turn_id, client_turn_id, thread_id, task_type,
                        request_hash, request_summary_json, status,
                        attempt_count, graph_version, lease_owner,
                        lease_expires_at, heartbeat_at, created_at, updated_at
                    ) VALUES (
                        %s, %s, %s, %s, %s, %s, 'running',
                        1, %s, %s, %s, %s, %s, %s
                    )
                    RETURNING *
                    """,
                    (
                        turn_id,
                        client_turn_id,
                        thread_id,
                        task_type,
                        request_hash,
                        Jsonb(request_summary),
                        "chat-v1" if task_type == "CHAT" else None,
                        lease_owner,
                        lease_expires_at,
                        now,
                        now,
                        now,
                    ),
                )
            ).fetchone()
            joined = {
                **(row or {}),
                "owner_id": owner_id,
                "scope_type": scope_type,
                "scope_id": scope_value,
            }
            return TurnRegistration(self._from_row(joined), resumed=False)

    async def get(
        self,
        client_turn_id: str,
        *,
        owner_id: str | None = None,
        scope_type: str | None = None,
        scope_id: str | int | None = None,
    ) -> AgentTurnRecord | None:
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    self._select_sql("tr.client_turn_id = %s"),
                    (client_turn_id,),
                )
            ).fetchone()
        if row is None:
            return None
        if owner_id is not None:
            self._validate_thread_scope(
                row, owner_id, scope_type or str(row["scope_type"]),
                str(scope_id) if scope_id is not None else str(row["scope_id"]),
            )
        return self._from_row(row)

    async def set_checkpoint_namespace(
        self, turn_id: str, lease_owner: str, checkpoint_namespace: str
    ) -> None:
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET checkpoint_namespace = %s, updated_at = %s
                    WHERE turn_id = %s AND status = 'running'
                      AND lease_owner = %s AND cancel_requested_at IS NULL
                    RETURNING turn_id
                    """,
                    (checkpoint_namespace, utc_now(), turn_id, lease_owner),
                )
            ).fetchone()
        if row is None:
            raise TurnLeaseLostError(turn_id)

    async def heartbeat(
        self, turn_id: str, lease_owner: str, lease_seconds: int
    ) -> bool:
        now = utc_now()
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET lease_expires_at = %s,
                        heartbeat_at = %s,
                        updated_at = %s
                    WHERE turn_id = %s AND status = 'running'
                      AND lease_owner = %s
                    RETURNING cancel_requested_at
                    """,
                    (
                        now + timedelta(seconds=lease_seconds),
                        now,
                        now,
                        turn_id,
                        lease_owner,
                    ),
                )
            ).fetchone()
        if row is None:
            raise TurnLeaseLostError(turn_id)
        return row.get("cancel_requested_at") is not None

    async def update_partial(
        self, turn_id: str, lease_owner: str, partial_answer: str
    ) -> None:
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET partial_answer = %s, updated_at = %s
                    WHERE turn_id = %s AND status = 'running'
                      AND lease_owner = %s
                    RETURNING turn_id
                    """,
                    (partial_answer, utc_now(), turn_id, lease_owner),
                )
            ).fetchone()
        if row is None:
            raise TurnLeaseLostError(turn_id)

    async def request_cancel(
        self,
        client_turn_id: str,
        owner_id: str,
        scope_type: str,
        scope_id: str | int,
    ) -> AgentTurnRecord:
        now = utc_now()
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    self._select_sql("tr.client_turn_id = %s", for_update=True),
                    (client_turn_id,),
                )
            ).fetchone()
            if row is None:
                raise LookupError(client_turn_id)
            self._validate_thread_scope(
                row, owner_id, scope_type, str(scope_id)
            )
            status = str(row["status"])
            if status == "completed":
                return self._from_row(row)
            if status == "cancelled":
                return self._from_row(row)
            if status == "running":
                if not self._lease_valid(row, now):
                    cancelled = await self._finalize_expired_cancel(
                        connection, row, now
                    )
                    return self._from_row(cancelled)
                updated = await (
                    await connection.execute(
                        """
                        UPDATE agent_turn
                        SET cancel_requested_at = COALESCE(cancel_requested_at, %s),
                            updated_at = %s
                        WHERE turn_id = %s
                        RETURNING *
                        """,
                        (now, now, row["turn_id"]),
                    )
                ).fetchone()
                return self._from_row({**row, **(updated or {})})
            updated = await (
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET status = 'cancelled', retryable = FALSE,
                        cancel_requested_at = COALESCE(cancel_requested_at, %s),
                        lease_owner = NULL, lease_expires_at = NULL,
                        finished_at = COALESCE(finished_at, %s), updated_at = %s
                    WHERE turn_id = %s
                    RETURNING *
                    """,
                    (now, now, now, row["turn_id"]),
                )
            ).fetchone()
            return self._from_row({**row, **(updated or {})})

    async def cancel_requested(self, turn_id: str) -> bool:
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    "SELECT cancel_requested_at FROM agent_turn WHERE turn_id = %s",
                    (turn_id,),
                )
            ).fetchone()
        return bool(row and row.get("cancel_requested_at") is not None)

    async def complete(
        self,
        *,
        turn_id: str,
        lease_owner: str,
        user_content: str,
        user_metadata: dict[str, Any],
        assistant_content: str,
        assistant_metadata: dict[str, Any],
        response: dict[str, Any],
    ) -> AgentTurnRecord:
        now = utc_now()
        async with self.database.transaction() as connection:
            turn = await (
                await connection.execute(
                    self._select_sql("tr.turn_id = %s", for_update=True),
                    (turn_id,),
                )
            ).fetchone()
            if turn is None:
                raise LookupError(turn_id)
            if str(turn["status"]) == "completed":
                return self._from_row(turn)
            if (
                str(turn["status"]) != "running"
                or str(turn.get("lease_owner") or "") != lease_owner
            ):
                raise TurnLeaseLostError(turn_id)
            if turn.get("cancel_requested_at") is not None:
                raise TurnConflictError("turn_cancelled", "turn cancellation requested")
            await self._upsert_message(
                connection,
                turn_id,
                str(turn["thread_id"]),
                "user",
                user_content,
                user_metadata,
                now,
            )
            await self._upsert_message(
                connection,
                turn_id,
                str(turn["thread_id"]),
                "assistant",
                assistant_content,
                assistant_metadata,
                now,
            )
            updated = await (
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET status = 'completed', partial_answer = %s,
                        response_json = %s, retryable = FALSE,
                        lease_owner = NULL, lease_expires_at = NULL,
                        finished_at = %s, updated_at = %s
                    WHERE turn_id = %s
                    RETURNING *
                    """,
                    (
                        assistant_content,
                        Jsonb(response),
                        now,
                        now,
                        turn_id,
                    ),
                )
            ).fetchone()
            await connection.execute(
                "UPDATE agent_thread SET updated_at = %s WHERE thread_id = %s",
                (now, turn["thread_id"]),
            )
        return self._from_row({**turn, **(updated or {})})

    async def finish_incomplete(
        self,
        *,
        turn_id: str,
        lease_owner: str,
        status: str,
        retryable: bool,
        error_code: str,
        user_content: str,
        user_metadata: dict[str, Any],
        partial_answer: str,
        assistant_metadata: dict[str, Any],
    ) -> AgentTurnRecord:
        if status not in {"interrupted", "failed", "cancelled"}:
            raise ValueError("invalid incomplete turn status")
        now = utc_now()
        async with self.database.transaction() as connection:
            turn = await (
                await connection.execute(
                    self._select_sql("tr.turn_id = %s", for_update=True),
                    (turn_id,),
                )
            ).fetchone()
            if turn is None:
                raise LookupError(turn_id)
            if str(turn["status"]) == "completed":
                return self._from_row(turn)
            if (
                str(turn["status"]) == "running"
                and str(turn.get("lease_owner") or "") not in {"", lease_owner}
            ):
                raise TurnLeaseLostError(turn_id)
            final_status = (
                "cancelled"
                if turn.get("cancel_requested_at") is not None or status == "cancelled"
                else status
            )
            final_retryable = bool(retryable and final_status != "cancelled")
            await self._upsert_message(
                connection,
                turn_id,
                str(turn["thread_id"]),
                "user",
                user_content,
                user_metadata,
                now,
            )
            if partial_answer:
                await self._upsert_message(
                    connection,
                    turn_id,
                    str(turn["thread_id"]),
                    "assistant",
                    partial_answer,
                    assistant_metadata,
                    now,
                )
            updated = await (
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET status = %s, partial_answer = %s,
                        retryable = %s, last_error_code = %s,
                        lease_owner = NULL, lease_expires_at = NULL,
                        finished_at = %s, updated_at = %s
                    WHERE turn_id = %s
                    RETURNING *
                    """,
                    (
                        final_status,
                        partial_answer,
                        final_retryable,
                        error_code,
                        now,
                        now,
                        turn_id,
                    ),
                )
            ).fetchone()
            await connection.execute(
                "UPDATE agent_thread SET updated_at = %s WHERE thread_id = %s",
                (now, turn["thread_id"]),
            )
        return self._from_row({**turn, **(updated or {})})

    async def claim_checkpoint_cleanup(
        self, retention_days: int, batch_size: int
    ) -> list[str]:
        cutoff = utc_now() - timedelta(days=retention_days)
        stale_claim = utc_now() - timedelta(minutes=15)
        async with self.database.transaction() as connection:
            rows = await (
                await connection.execute(
                    """
                    SELECT turn_id
                    FROM agent_turn
                    WHERE status IN ('completed', 'cancelled', 'failed', 'interrupted')
                      AND finished_at < %s
                      AND checkpoint_deleted_at IS NULL
                      AND (
                          checkpoint_cleanup_claimed_at IS NULL
                          OR checkpoint_cleanup_claimed_at < %s
                      )
                      AND (
                          lease_expires_at IS NULL
                          OR lease_expires_at <= CURRENT_TIMESTAMP
                      )
                    ORDER BY finished_at
                    LIMIT %s
                    FOR UPDATE SKIP LOCKED
                    """,
                    (cutoff, stale_claim, max(1, min(batch_size, 500))),
                )
            ).fetchall()
            ids = [str(row["turn_id"]) for row in rows]
            if ids:
                await connection.execute(
                    """
                    UPDATE agent_turn
                    SET checkpoint_cleanup_claimed_at = %s, updated_at = %s
                    WHERE turn_id = ANY(%s::uuid[])
                    """,
                    (utc_now(), utc_now(), ids),
                )
        return ids

    async def finish_checkpoint_cleanup(
        self, turn_id: str, *, deleted: bool
    ) -> None:
        async with self.database.transaction() as connection:
            await connection.execute(
                """
                UPDATE agent_turn
                SET checkpoint_deleted_at = CASE WHEN %s THEN %s ELSE checkpoint_deleted_at END,
                    checkpoint_cleanup_claimed_at = NULL,
                    updated_at = %s
                WHERE turn_id = %s
                """,
                (deleted, utc_now(), utc_now(), turn_id),
            )

    @staticmethod
    async def _upsert_message(
        connection: Any,
        turn_id: str,
        thread_id: str,
        role: str,
        content: str,
        metadata: dict[str, Any],
        created_at: datetime,
    ) -> None:
        row = await (
            await connection.execute(
                """
                SELECT id FROM agent_message
                WHERE turn_id = %s AND role = %s
                FOR UPDATE
                """,
                (turn_id, role),
            )
        ).fetchone()
        if row is None:
            await connection.execute(
                """
                INSERT INTO agent_message(
                    thread_id, turn_id, role, content, metadata_json, created_at
                ) VALUES (%s, %s, %s, %s, %s, %s)
                """,
                (thread_id, turn_id, role, content, Jsonb(metadata), created_at),
            )
            return
        await connection.execute(
            """
            UPDATE agent_message
            SET content = %s, metadata_json = %s
            WHERE id = %s
            """,
            (content, Jsonb(metadata), row["id"]),
        )

    @classmethod
    async def _finalize_expired_cancel(
        cls, connection: Any, turn: dict[str, Any], now: datetime
    ) -> dict[str, Any]:
        request_summary = turn.get("request_summary_json")
        summary = request_summary if isinstance(request_summary, dict) else {}
        client_turn_id = str(turn["client_turn_id"])
        task_type = str(turn["task_type"])
        metadata = {
            "taskType": task_type,
            "clientTurnId": client_turn_id,
            "incomplete": True,
            "turnStatus": "cancelled",
        }
        intent = summary.get("intent")
        if intent is not None:
            metadata["intent"] = intent
        user_content = str(summary.get("message") or "")
        if user_content:
            await cls._upsert_message(
                connection,
                str(turn["turn_id"]),
                str(turn["thread_id"]),
                "user",
                user_content,
                metadata,
                now,
            )
        partial_answer = str(turn.get("partial_answer") or "")
        if partial_answer:
            await cls._upsert_message(
                connection,
                str(turn["turn_id"]),
                str(turn["thread_id"]),
                "assistant",
                partial_answer,
                {
                    "status": "incomplete",
                    "taskType": task_type,
                    "clientTurnId": client_turn_id,
                    "incomplete": True,
                    "turnStatus": "cancelled",
                },
                now,
            )
        updated = await (
            await connection.execute(
                """
                UPDATE agent_turn
                SET status = 'cancelled', retryable = FALSE,
                    cancel_requested_at = COALESCE(cancel_requested_at, %s),
                    lease_owner = NULL, lease_expires_at = NULL,
                    finished_at = COALESCE(finished_at, %s), updated_at = %s
                WHERE turn_id = %s
                RETURNING *
                """,
                (now, now, now, turn["turn_id"]),
            )
        ).fetchone()
        await connection.execute(
            "UPDATE agent_thread SET updated_at = %s WHERE thread_id = %s",
            (now, turn["thread_id"]),
        )
        return {**turn, **(updated or {})}

    @staticmethod
    async def _lock_thread(connection: Any, thread_id: str) -> None:
        await connection.execute(
            "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
            (f"agent-turn-thread:{thread_id}",),
        )

    @staticmethod
    def _validate_identity(
        row: dict[str, Any],
        requested_thread_id: str | None,
        owner_id: str,
        scope_type: str,
        scope_id: str,
        task_type: str,
        request_hash: str,
    ) -> None:
        AgentTurnRepository._validate_thread_scope(
            row, owner_id, scope_type, scope_id
        )
        if requested_thread_id and str(row["thread_id"]) != requested_thread_id:
            raise TurnConflictError(
                "client_turn_conflict", "clientTurnId belongs to another thread"
            )
        if (
            str(row["task_type"]) != task_type
            or str(row["request_hash"]) != request_hash
        ):
            raise TurnConflictError(
                "client_turn_conflict", "clientTurnId payload does not match"
            )

    @staticmethod
    def _validate_thread_scope(
        row: dict[str, Any], owner_id: str, scope_type: str, scope_id: str
    ) -> None:
        if (
            str(row["owner_id"]) != owner_id
            or str(row["scope_type"]) != scope_type
            or str(row["scope_id"]) != scope_id
        ):
            raise PermissionError("thread scope does not match")

    @staticmethod
    def _lease_valid(row: dict[str, Any], now: datetime) -> bool:
        expires = row.get("lease_expires_at")
        return isinstance(expires, datetime) and expires > now

    @staticmethod
    def _select_sql(predicate: str, *, for_update: bool = False) -> str:
        suffix = " FOR UPDATE OF tr" if for_update else ""
        return f"""
            SELECT tr.*, t.owner_id, t.scope_type, t.scope_id
            FROM agent_turn tr
            INNER JOIN agent_thread t ON t.thread_id = tr.thread_id
            WHERE {predicate}{suffix}
        """

    @staticmethod
    def _from_row(row: dict[str, Any]) -> AgentTurnRecord:
        response = row.get("response_json")
        return AgentTurnRecord(
            turn_id=str(row["turn_id"]),
            client_turn_id=str(row["client_turn_id"]),
            thread_id=str(row["thread_id"]),
            owner_id=str(row["owner_id"]),
            scope_type=str(row["scope_type"]),
            scope_id=str(row["scope_id"]),
            task_type=str(row["task_type"]),
            request_hash=str(row["request_hash"]),
            request_summary=(
                dict(row["request_summary_json"])
                if isinstance(row.get("request_summary_json"), dict)
                else {}
            ),
            status=str(row["status"]),
            attempt_count=int(row.get("attempt_count") or 0),
            graph_version=(
                str(row["graph_version"])
                if row.get("graph_version") is not None
                else None
            ),
            checkpoint_namespace=(
                str(row["checkpoint_namespace"])
                if row.get("checkpoint_namespace") is not None
                else None
            ),
            lease_owner=(
                str(row["lease_owner"])
                if row.get("lease_owner") is not None
                else None
            ),
            lease_expires_at=row.get("lease_expires_at"),
            partial_answer=str(row.get("partial_answer") or ""),
            response=dict(response) if isinstance(response, dict) else None,
            retryable=bool(row.get("retryable")),
            cancel_requested_at=row.get("cancel_requested_at"),
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            finished_at=row.get("finished_at"),
        )
