from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


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

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA journal_mode = WAL")
        return connection

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
        if record.status != "active":
            raise ThreadScopeError("thread is archived")
        return record

    def get_thread(self, thread_id: str, owner_id: str) -> ThreadRecord:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM agent_thread WHERE thread_id = ? AND owner_id = ?", (thread_id, owner_id)
            ).fetchone()
        if row is None:
            raise ThreadNotFoundError(thread_id)
        return ThreadRecord(**dict(row))

    def list_threads(
        self,
        owner_id: str,
        task_type: str = "CHAT",
        scope_type: str | None = None,
        scope_id: str | int | None = None,
        limit: int = 50,
    ) -> list[ThreadSummaryRecord]:
        clauses = ["t.owner_id = ?", "t.status = 'active'"]
        parameters: list[Any] = [owner_id]
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
