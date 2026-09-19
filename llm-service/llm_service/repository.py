from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from psycopg.types.json import Jsonb

from .database import Database


class ThreadNotFoundError(LookupError):
    """指定会话不存在，或已不在当前持久化存储中。"""
    pass


class ThreadScopeError(PermissionError):
    """会话所有者或学校/区域范围与经认证请求不一致。"""
    pass


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(value: Any) -> str:
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc).isoformat()
    return str(value)


@dataclass(slots=True)
class ThreadRecord:
    thread_id: str
    owner_id: str
    scope_type: str
    scope_id: str
    status: str
    summary: str
    summary_through_message_id: int
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
    """会话、消息、摘要及工具审计的 PostgreSQL 持久化边界。

    所有会话读取与状态修改都以 ``owner_id``、``scope_type`` 和 ``scope_id`` 为
    数据所有权边界。该仓库保存已验证的应用层数据，不接受模型自行声明的范围。
    """
    def __init__(self, database: Database):
        self.database = database

    async def create_thread(
        self, owner_id: str, scope_type: str, scope_id: str | int
    ) -> ThreadRecord:
        """在指定账号和范围下创建活动会话，并持久化其初始摘要游标。"""
        # 会话标识在应用层生成，使创建结果无需依赖数据库的序列返回值。
        now = utc_now()
        record = ThreadRecord(
            thread_id=str(uuid.uuid4()),
            owner_id=owner_id,
            scope_type=scope_type,
            scope_id=str(scope_id),
            status="active",
            summary="",
            summary_through_message_id=0,
            created_at=_iso(now),
            updated_at=_iso(now),
        )
        async with self.database.transaction() as connection:
            # 新会话必须以空摘要开始；摘要游标隐含地从第一条消息之前的位置起算。
            await connection.execute(
                """
                INSERT INTO agent_thread(
                    thread_id, owner_id, scope_type, scope_id, status,
                    summary, created_at, updated_at
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (
                    record.thread_id,
                    record.owner_id,
                    record.scope_type,
                    record.scope_id,
                    record.status,
                    record.summary,
                    now,
                    now,
                ),
            )
        return record

    async def require_thread(
        self,
        thread_id: str,
        owner_id: str,
        scope_type: str | None = None,
        scope_id: str | int | None = None,
    ) -> ThreadRecord:
        """读取会话并强制校验调用者身份与范围；不匹配时不泄露会话是否存在。"""
        record = await self.get_thread(thread_id, owner_id, scope_type, scope_id)
        if record.status != "active":
            raise ThreadScopeError("thread is archived")
        return record

    async def get_thread(
        self,
        thread_id: str,
        owner_id: str,
        scope_type: str | None = None,
        scope_id: str | int | None = None,
    ) -> ThreadRecord:
        """读取会话，并以传入的已认证账号和可选范围限制访问。"""
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    "SELECT * FROM agent_thread WHERE thread_id = %s AND owner_id = %s",
                    (thread_id, owner_id),
                )
            ).fetchone()
        if row is None:
            # 查询条件已包含 owner_id，避免仅凭 thread_id 探测其他账号的会话存在。
            raise ThreadNotFoundError(thread_id)
        record = self._thread_from_row(row)
        if scope_type is not None and record.scope_type != scope_type:
            # 账号相同也不能跨学校、区域或资源范围复用会话。
            raise ThreadScopeError("thread scope does not match")
        if scope_id is not None and record.scope_id != str(scope_id):
            raise ThreadScopeError("thread scope does not match")
        return record

    async def list_threads(
        self,
        owner_id: str,
        task_type: str = "CHAT",
        scope_type: str | None = None,
        scope_id: str | int | None = None,
        limit: int = 50,
        status: str = "active",
    ) -> list[ThreadSummaryRecord]:
        """分页列出当前账号在指定范围内可见的会话摘要，不返回跨范围记录。"""
        normalized_status = self._normalize_thread_status(status)
        clauses = ["t.owner_id = %s", "t.status = %s"]
        parameters: list[Any] = [owner_id, normalized_status]
        if scope_type is not None:
            clauses.append("t.scope_type = %s")
            parameters.append(scope_type)
        if scope_id is not None:
            clauses.append("t.scope_id = %s")
            parameters.append(str(scope_id))
        clauses.append(
            # 仅列出与当前任务类型关联的会话，避免不同 Agent 任务在会话列表混杂。
            "EXISTS (SELECT 1 FROM agent_message tm WHERE tm.thread_id = t.thread_id "
            "AND tm.role = 'user' AND tm.metadata_json ->> 'taskType' = %s)"
        )
        parameters.extend((task_type, max(1, min(limit, 100))))
        query = f"""
            SELECT t.thread_id, t.scope_type, t.scope_id, t.created_at, t.updated_at,
                   (SELECT content FROM agent_message first_message
                    WHERE first_message.thread_id = t.thread_id
                      AND first_message.role = 'user'
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
            LIMIT %s
        """
        async with self.database.connection() as connection:
            rows = await (await connection.execute(query, parameters)).fetchall()
        return [
            ThreadSummaryRecord(
                thread_id=str(row["thread_id"]),
                scope_type=str(row["scope_type"]),
                scope_id=str(row["scope_id"]),
                title=self._preview(row["title_source"], 40) or "新对话",
                preview=self._preview(row["preview_source"], 80),
                message_count=int(row["message_count"] or 0),
                created_at=_iso(row["created_at"]),
                updated_at=_iso(row["updated_at"]),
            )
            for row in rows
        ]

    async def append_message(
        self,
        thread_id: str,
        role: str,
        content: str,
        metadata: dict[str, Any] | None = None,
    ) -> int:
        """追加消息及其元数据；调用者须先完成会话所有权校验。"""
        now = utc_now()
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    INSERT INTO agent_message(
                        thread_id, role, content, metadata_json, created_at
                    ) VALUES (%s, %s, %s, %s, %s)
                    RETURNING id
                    """,
                    (thread_id, role, content, Jsonb(metadata or {}), now),
                )
            ).fetchone()
            await connection.execute(
                # 消息写入与会话更新时间必须同一事务提交，列表排序才反映真实最后活动时间。
                "UPDATE agent_thread SET updated_at = %s WHERE thread_id = %s",
                (now, thread_id),
            )
        return int((row or {})["id"])

    async def count_completed_formal_account_chat_turns(self) -> int:
        """统计已完成且来源为正式账号的聊天轮次，供运行指标使用。"""
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    """
                    SELECT COUNT(*) AS completed_question_count
                    FROM agent_message m
                    INNER JOIN agent_thread t ON t.thread_id = m.thread_id
                    WHERE t.owner_id LIKE 'account:%'
                      AND t.scope_type = 'SCHOOL'
                      AND m.role = 'assistant'
                      AND m.metadata_json ->> 'status' = 'completed'
                      AND (
                          m.metadata_json ->> 'taskType' = 'CHAT'
                          OR NOT (m.metadata_json ? 'taskType')
                      )
                    """
                )
            ).fetchone()
        return int((row or {}).get("completed_question_count") or 0)

    async def list_messages(self, thread_id: str) -> list[dict[str, Any]]:
        """按持久化顺序读取会话消息；范围校验由上层会话入口负责。"""
        async with self.database.connection() as connection:
            rows = await (
                await connection.execute(
                    """
                    SELECT id, role, content, metadata_json, created_at
                    FROM agent_message WHERE thread_id = %s ORDER BY id
                    """,
                    (thread_id,),
                )
            ).fetchall()
        return [
            {
                "id": int(row["id"]),
                "role": str(row["role"]),
                "content": str(row["content"]),
                "metadata": dict(row["metadata_json"] or {}),
                "created_at": _iso(row["created_at"]),
            }
            for row in rows
        ]

    async def list_context_messages(self, thread_id: str) -> list[dict[str, Any]]:
        """只返回可进入后续模型上下文的正式历史。"""
        async with self.database.connection() as connection:
            # 中断、失败和等待确认的轮次不能进入模型历史，防止把半成品当作事实。
            rows = await (
                await connection.execute(
                    """
                    SELECT m.id, m.role, m.content, m.metadata_json, m.created_at
                    FROM agent_message m
                    LEFT JOIN agent_turn tr ON tr.turn_id = m.turn_id
                    WHERE m.thread_id = %s
                      AND (m.turn_id IS NULL OR tr.status = 'completed')
                    ORDER BY m.id
                    """,
                    (thread_id,),
                )
            ).fetchall()
        return [
            {
                "id": int(row["id"]),
                "role": str(row["role"]),
                "content": str(row["content"]),
                "metadata": dict(row["metadata_json"] or {}),
                "created_at": _iso(row["created_at"]),
            }
            for row in rows
        ]

    async def list_messages_for_turn(
        self, turn_id: str
    ) -> list[dict[str, Any]]:
        """读取单轮次已持久化消息，用于恢复和审计而非模型上下文拼接。"""
        async with self.database.connection() as connection:
            rows = await (
                await connection.execute(
                    """
                    SELECT id, thread_id, role, content, metadata_json, created_at
                    FROM agent_message
                    WHERE turn_id = %s
                    ORDER BY id
                    """,
                    (turn_id,),
                )
            ).fetchall()
        return [
            {
                "id": int(row["id"]),
                "thread_id": str(row["thread_id"]),
                "role": str(row["role"]),
                "content": str(row["content"]),
                "metadata": dict(row["metadata_json"] or {}),
                "created_at": _iso(row["created_at"]),
            }
            for row in rows
        ]

    async def find_assistant_message_by_client_turn_id(
        self,
        client_turn_id: str,
        owner_id: str,
        scope_type: str,
        scope_id: str | int,
    ) -> dict[str, Any] | None:
        """在调用者所属范围内查找已完成轮次的助手回复。

        未完成轮次的消息不会作为可恢复的正式回复返回，避免客户端将部分输出误认为
        已提交结果。
        """
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    """
                    SELECT m.id, m.thread_id, m.role, m.content,
                           m.metadata_json, m.created_at
                    FROM agent_message m
                    INNER JOIN agent_thread t ON t.thread_id = m.thread_id
                    WHERE t.owner_id = %s
                      AND t.scope_type = %s
                      AND t.scope_id = %s
                      AND m.role = 'assistant'
                      AND (m.turn_id IS NULL OR EXISTS (
                          SELECT 1 FROM agent_turn completed_turn
                          WHERE completed_turn.turn_id = m.turn_id
                            AND completed_turn.status = 'completed'
                      ))
                      AND m.metadata_json ->> 'clientTurnId' = %s
                    ORDER BY m.id DESC
                    LIMIT 1
                    """,
                    (owner_id, scope_type, str(scope_id), client_turn_id),
                )
            ).fetchone()
        if row is None:
            return None
        return {
            "id": int(row["id"]),
            "thread_id": str(row["thread_id"]),
            "role": str(row["role"]),
            "content": str(row["content"]),
            "metadata": dict(row["metadata_json"] or {}),
            "created_at": _iso(row["created_at"]),
        }

    async def update_summary(
        self,
        thread_id: str,
        summary: str,
        *,
        expected_cursor: int = 0,
        new_cursor: int = 0,
    ) -> bool:
        """以摘要游标的乐观并发条件提交压缩结果。

        返回 ``False`` 表示另一执行者已经推进游标；调用方应重新读取会话后构建
        新窗口，不能用旧摘要覆盖较新的上下文。
        """
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    UPDATE agent_thread
                    SET summary = %s,
                        summary_through_message_id = %s,
                        updated_at = %s
                    WHERE thread_id = %s
                      AND summary_through_message_id = %s
                      AND %s >= summary_through_message_id
                    RETURNING thread_id
                    """,
                    (
                        summary,
                        new_cursor,
                        utc_now(),
                        thread_id,
                        expected_cursor,
                        new_cursor,
                    ),
                )
            ).fetchone()
        return row is not None

    async def find_tool_audit(
        self, turn_id: str, tool_call_id: str
    ) -> dict[str, Any] | None:
        """按轮次和确定性工具调用键查询既有审计结果，支持幂等恢复。"""
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    """
                    SELECT tool_name, status, duration_ms, result_preview
                    FROM agent_tool_audit
                    WHERE turn_id = %s AND tool_call_id = %s
                    """,
                    (turn_id, tool_call_id),
                )
            ).fetchone()
        return dict(row) if row is not None else None

    async def add_tool_audit(
        self,
        thread_id: str,
        tool_name: str,
        arguments: dict[str, Any],
        status: str,
        duration_ms: int,
        result_preview: str,
        *,
        turn_id: str | None = None,
        tool_call_id: str | None = None,
    ) -> dict[str, Any]:
        """持久化工具执行摘要，并按 ``turn_id``/``tool_call_id`` 实现回放语义。

        非失败记录在冲突时保持原值，确保恢复不会覆盖已完成事实；失败记录允许被
        后续重试结果替换。传入参数应已脱敏，仓库不会再解释或过滤其业务字段。
        """
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                """
                INSERT INTO agent_tool_audit(
                    thread_id, tool_name, arguments_json, status,
                    duration_ms, result_preview, created_at, turn_id, tool_call_id
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (turn_id, tool_call_id)
                    WHERE turn_id IS NOT NULL AND tool_call_id IS NOT NULL
                DO UPDATE SET
                    arguments_json = EXCLUDED.arguments_json,
                    status = EXCLUDED.status,
                    duration_ms = EXCLUDED.duration_ms,
                    result_preview = EXCLUDED.result_preview
                WHERE agent_tool_audit.status = 'failed'
                RETURNING tool_name, status, duration_ms, result_preview
                """,
                (
                    thread_id,
                    tool_name,
                    Jsonb(arguments),
                    status,
                    duration_ms,
                    result_preview,
                    utc_now(),
                    turn_id,
                    tool_call_id,
                ),
                )
            ).fetchone()
            if row is None and turn_id and tool_call_id:
                # 非失败审计记录拒绝被覆盖时没有 RETURNING 行，读取既有事实作为恢复结果。
                row = await (
                    await connection.execute(
                        """
                        SELECT tool_name, status, duration_ms, result_preview
                        FROM agent_tool_audit
                        WHERE turn_id = %s AND tool_call_id = %s
                        """,
                        (turn_id, tool_call_id),
                    )
                ).fetchone()
        return dict(row or {
            "tool_name": tool_name,
            "status": status,
            "duration_ms": duration_ms,
            "result_preview": result_preview,
        })

    async def list_tool_audits(
        self,
        tool_name: str | None = None,
        status: str | None = None,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        """按可选工具名和状态查询受限数量的审计记录，供受控运维视图使用。"""
        clauses: list[str] = []
        parameters: list[Any] = []
        if tool_name and tool_name.strip():
            clauses.append("tool_name = %s")
            parameters.append(tool_name.strip())
        if status and status.strip():
            clauses.append("status = %s")
            parameters.append(status.strip())
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        parameters.append(max(1, min(int(limit), 100)))
        query = f"""
            SELECT id, thread_id, turn_id, tool_call_id, tool_name,
                   arguments_json, status, duration_ms, result_preview, created_at
            FROM agent_tool_audit
            {where}
            ORDER BY id DESC
            LIMIT %s
        """
        async with self.database.connection() as connection:
            rows = await (await connection.execute(query, parameters)).fetchall()
        return [
            {
                "id": int(row["id"]),
                "threadId": str(row["thread_id"]),
                "turnId": str(row["turn_id"]) if row.get("turn_id") else None,
                "toolCallId": str(row["tool_call_id"]) if row.get("tool_call_id") else None,
                "toolName": str(row["tool_name"]),
                "arguments": dict(row["arguments_json"] or {}),
                "status": str(row["status"]),
                "durationMs": int(row["duration_ms"]),
                "resultPreview": str(row["result_preview"]),
                "createdAt": _iso(row["created_at"]),
            }
            for row in rows
        ]

    async def archive_thread(
        self,
        thread_id: str,
        owner_id: str,
        scope_type: str | None = None,
        scope_id: str | int | None = None,
    ) -> None:
        """将当前调用者可见的活动会话归档；归档后不允许继续写入该会话。"""
        await self._change_status(
            thread_id,
            owner_id,
            "active",
            "archived",
            scope_type,
            scope_id,
        )

    async def restore_thread(
        self,
        thread_id: str,
        owner_id: str,
        scope_type: str | None = None,
        scope_id: str | int | None = None,
    ) -> None:
        """恢复当前调用者可见的归档会话；不改变其原始账号和范围归属。"""
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    SELECT * FROM agent_thread
                    WHERE thread_id = %s AND owner_id = %s
                    FOR UPDATE
                    """,
                    (thread_id, owner_id),
                )
            ).fetchone()
            if row is None:
                raise ThreadNotFoundError(thread_id)
            record = self._thread_from_row(row)
            self._validate_scope(record, scope_type, scope_id)
            if record.status == "active":
                # 恢复接口对活动会话幂等，重复调用不会改变更新时间或消息内容。
                return
            if record.status != "archived":
                raise ThreadScopeError("thread status does not match")
            await connection.execute(
                "UPDATE agent_thread SET status = 'active', updated_at = %s WHERE thread_id = %s",
                (utc_now(), thread_id),
            )

    async def _change_status(
        self,
        thread_id: str,
        owner_id: str,
        expected_status: str,
        next_status: str,
        scope_type: str | None,
        scope_id: str | int | None,
    ) -> None:
        """在所有权、范围和当前状态同时匹配时执行单向状态迁移。"""
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    SELECT * FROM agent_thread
                    WHERE thread_id = %s AND owner_id = %s
                    FOR UPDATE
                    """,
                    (thread_id, owner_id),
                )
            ).fetchone()
            if row is None:
                raise ThreadNotFoundError(thread_id)
            record = self._thread_from_row(row)
            self._validate_scope(record, scope_type, scope_id)
            if record.status != expected_status:
                raise ThreadScopeError("thread status does not match")
            await connection.execute(
                "UPDATE agent_thread SET status = %s, updated_at = %s WHERE thread_id = %s",
                (next_status, utc_now(), thread_id),
            )

    @staticmethod
    def _thread_from_row(row: dict[str, Any]) -> ThreadRecord:
        return ThreadRecord(
            thread_id=str(row["thread_id"]),
            owner_id=str(row["owner_id"]),
            scope_type=str(row["scope_type"]),
            scope_id=str(row["scope_id"]),
            status=str(row["status"]),
            summary=str(row["summary"]),
            summary_through_message_id=int(
                row.get("summary_through_message_id") or 0
            ),
            created_at=_iso(row["created_at"]),
            updated_at=_iso(row["updated_at"]),
        )

    @staticmethod
    def _validate_scope(
        record: ThreadRecord,
        scope_type: str | None,
        scope_id: str | int | None,
    ) -> None:
        if scope_type is not None and record.scope_type != scope_type:
            raise ThreadScopeError("thread scope does not match")
        if scope_id is not None and record.scope_id != str(scope_id):
            raise ThreadScopeError("thread scope does not match")

    @staticmethod
    def _preview(value: str | None, limit: int) -> str:
        normalized = " ".join((value or "").split())
        return normalized if len(normalized) <= limit else f"{normalized[:limit]}..."

    @staticmethod
    def _normalize_thread_status(status: str) -> str:
        normalized = str(status or "").strip().lower()
        if normalized not in {"active", "archived"}:
            raise ValueError("thread status must be active or archived")
        return normalized
