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

    def archive_thread(self, thread_id: str, owner_id: str) -> None:
        self.require_thread(thread_id, owner_id)
        with self._lock, self._connect() as connection:
            connection.execute(
                "UPDATE agent_thread SET status = 'archived', updated_at = ? WHERE thread_id = ?",
                (utc_now(), thread_id),
            )
