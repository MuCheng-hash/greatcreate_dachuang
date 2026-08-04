from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator


class ThreadNotFoundError(LookupError):
    pass


class ThreadScopeError(PermissionError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass(slots=True)
class ThreadRecord:
    thread_id: str
    owner_id: str
    scope_type: str
    scope_id: str
    status: str
    summary: str
    created_at: str
    updated_at: str


@dataclass(slots=True)
class ThreadSummaryRecord:
    thread_id: str
    scope_type: str
    scope_id: str
    title: str
    preview: str
    message_count: int
    created_at: str
    updated_at: str


class ConversationRepository:
    def __init__(self, database_path: Path | str):
        self.database_path = Path(database_path)
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._initialize()

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.database_path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA journal_mode = WAL")
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._lock, self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS agent_thread (
                    thread_id TEXT PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    scope_type TEXT NOT NULL,
                    scope_id TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'active',
                    summary TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_agent_thread_owner
                    ON agent_thread(owner_id, updated_at DESC);
                CREATE TABLE IF NOT EXISTS agent_message (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    thread_id TEXT NOT NULL REFERENCES agent_thread(thread_id) ON DELETE CASCADE,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_agent_message_thread
                    ON agent_message(thread_id, id);
                CREATE TABLE IF NOT EXISTS agent_tool_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    thread_id TEXT NOT NULL REFERENCES agent_thread(thread_id) ON DELETE CASCADE,
                    tool_name TEXT NOT NULL,
                    arguments_json TEXT NOT NULL DEFAULT '{}',
                    status TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    result_preview TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL
                );
                """
            )

    def create_thread(self, owner_id: str, scope_type: str, scope_id: str | int) -> ThreadRecord:
        now = utc_now()
        record = ThreadRecord(
            thread_id=str(uuid.uuid4()), owner_id=owner_id, scope_type=scope_type,
            scope_id=str(scope_id), status="active", summary="", created_at=now, updated_at=now,
        )
        with self._lock, self._connect() as connection:
            connection.execute(
                "INSERT INTO agent_thread VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (record.thread_id, record.owner_id, record.scope_type, record.scope_id,
                 record.status, record.summary, record.created_at, record.updated_at),
            )
        return record

    def require_thread(
        self, thread_id: str, owner_id: str, scope_type: str | None = None, scope_id: str | int | None = None
    ) -> ThreadRecord:
        record = self.get_thread(thread_id, owner_id, scope_type, scope_id)
        if record.status != "active":
            raise ThreadScopeError("thread is archived")
        return record

    def get_thread(
        self,
        thread_id: str,
        owner_id: str,
        scope_type: str | None = None,
        scope_id: str | int | None = None,
    ) -> ThreadRecord:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM agent_thread WHERE thread_id = ? AND owner_id = ?", (thread_id, owner_id)
            ).fetchone()
        if row is None:
            raise ThreadNotFoundError(thread_id)
        record = ThreadRecord(**dict(row))
        if scope_type is not None and record.scope_type != scope_type:
            raise ThreadScopeError("thread scope does not match")
        if scope_id is not None and record.scope_id != str(scope_id):
            raise ThreadScopeError("thread scope does not match")
        return record

    def list_threads(
        self,
        owner_id: str,
        task_type: str = "CHAT",
        scope_type: str | None = None,
        scope_id: str | int | None = None,
        limit: int = 50,
        status: str = "active",
    ) -> list[ThreadSummaryRecord]:
        normalized_status = self._normalize_thread_status(status)
        clauses = ["t.owner_id = ?", "t.status = ?"]
        parameters: list[Any] = [owner_id, normalized_status]
        if scope_type is not None:
            clauses.append("t.scope_type = ?")
            parameters.append(scope_type)
        if scope_id is not None:
            clauses.append("t.scope_id = ?")
            parameters.append(str(scope_id))
        clauses.append(
            "EXISTS (SELECT 1 FROM agent_message tm WHERE tm.thread_id = t.thread_id "
            "AND tm.role = 'user' AND json_extract(tm.metadata_json, '$.taskType') = ?)"
        )
        parameters.extend((task_type, max(1, min(limit, 100))))
        sql = f"""
            SELECT t.thread_id, t.scope_type, t.scope_id, t.created_at, t.updated_at,
                   (SELECT content FROM agent_message first_message
                    WHERE first_message.thread_id = t.thread_id AND first_message.role = 'user'
                    ORDER BY first_message.id ASC LIMIT 1) AS title_source,
                   (SELECT content FROM agent_message last_message
                    WHERE last_message.thread_id = t.thread_id
                    ORDER BY last_message.id DESC LIMIT 1) AS preview_source,
                   (SELECT COUNT(*) FROM agent_message counted
                    WHERE counted.thread_id = t.thread_id
                      AND counted.role IN ('user', 'assistant')) AS message_count
            FROM agent_thread t
            WHERE {' AND '.join(clauses)}
            ORDER BY t.updated_at DESC
            LIMIT ?
        """
        with self._connect() as connection:
            rows = connection.execute(sql, parameters).fetchall()
        return [
            ThreadSummaryRecord(
                thread_id=row["thread_id"],
                scope_type=row["scope_type"],
                scope_id=row["scope_id"],
                title=self._preview(row["title_source"], 40) or "新对话",
                preview=self._preview(row["preview_source"], 80),
                message_count=int(row["message_count"] or 0),
                created_at=row["created_at"],
                updated_at=row["updated_at"],
            )
            for row in rows
        ]

    @staticmethod
    def _preview(value: str | None, limit: int) -> str:
        normalized = " ".join((value or "").split())
        return normalized if len(normalized) <= limit else f"{normalized[:limit]}..."

    def append_message(self, thread_id: str, role: str, content: str, metadata: dict[str, Any] | None = None) -> int:
        now = utc_now()
        metadata_json = json.dumps(metadata or {}, ensure_ascii=False)
        with self._lock, self._connect() as connection:
            cursor = connection.execute(
                "INSERT INTO agent_message(thread_id, role, content, metadata_json, created_at) VALUES (?, ?, ?, ?, ?)",
                (thread_id, role, content, metadata_json, now),
            )
            connection.execute("UPDATE agent_thread SET updated_at = ? WHERE thread_id = ?", (now, thread_id))
            return int(cursor.lastrowid)

    def list_messages(self, thread_id: str) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT id, role, content, metadata_json, created_at FROM agent_message WHERE thread_id = ? ORDER BY id",
                (thread_id,),
            ).fetchall()
        return [
            {
                "id": row["id"], "role": row["role"], "content": row["content"],
                "metadata": json.loads(row["metadata_json"] or "{}"), "created_at": row["created_at"],
            }
            for row in rows
        ]

    def find_assistant_message_by_client_turn_id(
        self,
        client_turn_id: str,
        owner_id: str,
        scope_type: str,
        scope_id: str | int,
    ) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT m.id, m.thread_id, m.role, m.content, m.metadata_json, m.created_at
                FROM agent_message m
                INNER JOIN agent_thread t ON t.thread_id = m.thread_id
                WHERE t.owner_id = ?
                  AND t.scope_type = ?
                  AND t.scope_id = ?
                  AND m.role = 'assistant'
                  AND json_extract(m.metadata_json, '$.clientTurnId') = ?
                ORDER BY m.id DESC
                LIMIT 1
                """,
                (owner_id, scope_type, str(scope_id), client_turn_id),
            ).fetchone()
        if row is None:
            return None
        return {
            "id": row["id"],
            "thread_id": row["thread_id"],
            "role": row["role"],
            "content": row["content"],
            "metadata": json.loads(row["metadata_json"] or "{}"),
            "created_at": row["created_at"],
        }

    def update_summary(self, thread_id: str, summary: str) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                "UPDATE agent_thread SET summary = ?, updated_at = ? WHERE thread_id = ?",
                (summary, utc_now(), thread_id),
            )

    def add_tool_audit(
        self, thread_id: str, tool_name: str, arguments: dict[str, Any], status: str,
        duration_ms: int, result_preview: str,
    ) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """INSERT INTO agent_tool_audit(
                       thread_id, tool_name, arguments_json, status, duration_ms, result_preview, created_at
                   ) VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (thread_id, tool_name, json.dumps(arguments, ensure_ascii=False), status,
                 duration_ms, result_preview[:1000], utc_now()),
            )

    def list_tool_audits(
        self,
        tool_name: str | None = None,
        status: str | None = None,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        clauses: list[str] = []
        parameters: list[Any] = []
        if tool_name and tool_name.strip():
            clauses.append("tool_name = ?")
            parameters.append(tool_name.strip())
        if status and status.strip():
            clauses.append("status = ?")
            parameters.append(status.strip())

        safe_limit = max(1, min(int(limit), 100))
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        sql = f"""
            SELECT id, thread_id, tool_name, arguments_json, status,
                   duration_ms, result_preview, created_at
            FROM agent_tool_audit
            {where}
            ORDER BY id DESC
            LIMIT ?
        """
        parameters.append(safe_limit)
        with self._connect() as connection:
            rows = connection.execute(sql, parameters).fetchall()

        audits: list[dict[str, Any]] = []
        for row in rows:
            try:
                arguments = json.loads(row["arguments_json"] or "{}")
            except (TypeError, json.JSONDecodeError):
                arguments = {}
            audits.append(
                {
                    "id": row["id"],
                    "threadId": row["thread_id"],
                    "toolName": row["tool_name"],
                    "arguments": arguments,
                    "status": row["status"],
                    "durationMs": row["duration_ms"],
                    "resultPreview": row["result_preview"],
                    "createdAt": row["created_at"],
                }
            )
        return audits

    def archive_thread(
        self, thread_id: str, owner_id: str,
        scope_type: str | None = None, scope_id: str | int | None = None,
    ) -> None:
        self.require_thread(thread_id, owner_id, scope_type, scope_id)
        with self._lock, self._connect() as connection:
            connection.execute(
                "UPDATE agent_thread SET status = 'archived', updated_at = ? WHERE thread_id = ?",
                (utc_now(), thread_id),
            )

    def restore_thread(
        self, thread_id: str, owner_id: str,
        scope_type: str | None = None, scope_id: str | int | None = None,
    ) -> None:
        record = self.get_thread(thread_id, owner_id, scope_type, scope_id)
        if record.status != "archived":
            return
        with self._lock, self._connect() as connection:
            connection.execute(
                "UPDATE agent_thread SET status = 'active', updated_at = ? WHERE thread_id = ?",
                (utc_now(), thread_id),
            )

    @staticmethod
    def _normalize_thread_status(status: str) -> str:
        normalized = str(status or "").strip().lower()
        if normalized not in {"active", "archived"}:
            raise ValueError("thread status must be active or archived")
        return normalized
