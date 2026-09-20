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
    """客户端轮次键与既有状态、身份或请求载荷冲突。"""
    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(message)


class TurnLeaseLostError(RuntimeError):
    """当前执行者不再持有轮次租约，禁止继续写入最终状态。"""
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
        """原子注册或恢复客户端轮次，并为实际执行者取得租约。

        ``client_turn_id`` 以身份、范围、任务类型和请求哈希绑定，防止不同请求借用
        同一个幂等键。事务同时锁定客户端键和会话，确保一条会话至多有一个可继续
        的轮次；已完成结果直接标记为 ``resumed``，不会再次运行模型。
        """
        now = utc_now()
        lease_expires_at = now + timedelta(seconds=lease_seconds)
        scope_value = str(scope_id)
        async with self.database.transaction() as connection:
            # 客户端键锁覆盖新建和恢复两条路径，避免并发重试创建两次轮次。
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
                # 同 clientTurnId 只能恢复语义完全相同的请求，先校验再决定是否复用记录。
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
                    # 已提交的最终结果是幂等终点，不能因网络重试重新调用模型或工具。
                    return TurnRegistration(self._from_row(existing), resumed=True)
                if status == "awaiting_confirmation":
                    raise TurnConflictError(
                        "action_confirmation_required",
                        "请求的轮次正在等待动作确认",
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
                        "turn_cancelled", "请求的轮次已取消"
                    )
                if status == "running" and self._lease_valid(existing, now):
                    # 有效租约说明另一个执行者仍在写入，当前请求只能等待或重连。
                    raise TurnConflictError(
                        "turn_in_progress", "请求的轮次正在执行"
                    )
                if status == "failed" and not bool(existing.get("retryable")):
                    raise TurnConflictError(
                        "client_turn_conflict", "请求的轮次不可重试"
                    )
                thread_id = str(existing["thread_id"])
                await self._lock_thread(connection, thread_id)
                # 会话锁与客户端键锁分层：前者阻止不同 clientTurnId 并行篡改同一上下文。
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
                        "thread_busy", "该会话正由另一个未完成轮次占用"
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
                # 复用既有会话时锁定会话行，并在写入轮次前复核它仍属于当前认证范围。
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
                    raise PermissionError("会话已归档")
                thread_id = requested_thread_id
            else:
                # 新会话和首个轮次在同一事务中创建，避免出现没有执行记录的孤立会话。
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
                # 一个会话只允许一个未完成轮次，确保后续消息顺序和摘要游标可推导。
                raise TurnConflictError(
                    "thread_busy", "该会话正由另一个未完成轮次占用"
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
        """读取轮次，并在给出身份参数时校验其所属会话范围。"""
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
        """在持有租约时登记检查点命名空间，供中断恢复定位图状态。"""
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
            # 写入条件同时校验状态、租约与未取消标记；失败代表执行权已转移或终止。
            raise TurnLeaseLostError(turn_id)

    async def heartbeat(
        self, turn_id: str, lease_owner: str, lease_seconds: int
    ) -> bool:
        """续租正在执行的轮次，并返回是否已有客户端取消请求。

        租约所有者不匹配时抛出 ``TurnLeaseLostError``，使旧执行者停止写入，避免
        超时恢复后的两个执行者竞争同一轮次。
        """
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
            # 心跳失败时旧执行者必须停止，不能继续提交部分回答或最终消息。
            raise TurnLeaseLostError(turn_id)
        return row.get("cancel_requested_at") is not None

    async def update_partial(
        self, turn_id: str, lease_owner: str, partial_answer: str
    ) -> None:
        """在租约仍有效时保存部分回答，供 SSE 断开和失败恢复使用。"""
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
        """请求取消当前范围内的轮次，并处理已过期租约留下的悬挂状态。

        运行中的有效租约只设置取消标记，由执行者的心跳安全终止；没有有效执行者时
        在同一事务中完成取消，避免客户端无限等待。
        """
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
                    # 过期租约不会再有合法执行者处理取消，可在当前事务内收敛终态。
                    cancelled = await self._finalize_expired_cancel(
                        connection, row, now
                    )
                    return self._from_row(cancelled)
                updated = await (
                    # 幂等地保留首次取消时间，后续重复取消不改变审计事实。
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
        """返回轮次是否已被标记取消，供执行器在持久化前确认最终状态。"""
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
        """原子提交用户消息、助手消息、响应快照及完成状态。

        只有当前租约所有者可提交；已完成轮次直接返回既有记录，从而使客户端重试
        幂等。取消标记优先于完成，防止取消后仍产生正式回答。
        """
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
                # 重复完成提交只返回已持久化快照，防止重试重复写入用户或助手消息。
                return self._from_row(turn)
            if (
                str(turn["status"]) != "running"
                or str(turn.get("lease_owner") or "") != lease_owner
            ):
                raise TurnLeaseLostError(turn_id)
            if turn.get("cancel_requested_at") is not None:
                # 取消请求在最终提交前再次检查，优先级高于已经生成好的模型结果。
                raise TurnConflictError("turn_cancelled", "已请求取消轮次")
            await self._upsert_message(
                # 用户和助手消息与轮次终态在一个事务中提交，恢复时不会看到半套正式历史。
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
        """持久化取消、可恢复中断或不可重试失败时的部分结果。

        用户消息始终保存，助手消息只在有部分文本时保存；一旦检测到取消标记便将
        最终状态固定为 ``cancelled``，并禁止后续重试。
        """
        if status not in {"interrupted", "failed", "cancelled"}:
            raise ValueError("无效的未完成轮次状态")
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
            # 显式取消永远不可重试；其他中断才由调用方标记为可通过同一幂等键恢复。
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
                # 没有有效文本时不创建空助手消息，避免恢复接口把空记录显示为部分回答。
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
        """批量认领过期终态轮次的检查点清理任务。

        ``FOR UPDATE SKIP LOCKED`` 使多个清理工作者可并发运行而不重复删除同一
        检查点；陈旧认领可被重新取得以容忍工作者崩溃。
        """
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
                # 认领时间只在本批锁定记录上更新；其他工作者会跳过它们并领取不同任务。
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
        """记录检查点是否已删除并释放清理认领，供后续失败重试。"""
        async with self.database.transaction() as connection:
            # 无论删除是否成功都释放领取标记；失败记录保留 checkpoint_deleted_at 为空以便重试。
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
        """按轮次和角色写入唯一消息，允许部分回答被最终内容覆盖。"""
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
            # 首次写入建立每轮每角色唯一消息；后续部分回答和最终回答覆盖同一条记录。
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
            # 更新不改 created_at，客户端仍可按照初次消息顺序恢复会话历史。
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
        """为已失去租约且已请求取消的轮次补写可恢复历史并完成取消。"""
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
            # 即使原执行者崩溃，取消后的历史也应包含本轮问题以便用户理解部分回答来源。
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
            # 仅保存已经刷盘的部分回答，不能尝试从丢失的模型流重新推断文本。
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
        """获取事务级会话顾问锁，串行化同一会话的未完成轮次判断。"""
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
        """验证重试请求与原轮次的范围、会话、任务和载荷完全一致。"""
        AgentTurnRepository._validate_thread_scope(
            row, owner_id, scope_type, scope_id
        )
        if requested_thread_id and str(row["thread_id"]) != requested_thread_id:
            raise TurnConflictError(
                "client_turn_conflict", "clientTurnId 属于其他会话"
            )
        if (
            str(row["task_type"]) != task_type
            or str(row["request_hash"]) != request_hash
        ):
            raise TurnConflictError(
                "client_turn_conflict", "clientTurnId 对应的载荷不匹配"
            )

    @staticmethod
    def _validate_thread_scope(
        row: dict[str, Any], owner_id: str, scope_type: str, scope_id: str
    ) -> None:
        """校验轮次所属会话的账号和范围，拒绝跨租户读取或操作。"""
        if (
            str(row["owner_id"]) != owner_id
            or str(row["scope_type"]) != scope_type
            or str(row["scope_id"]) != scope_id
        ):
            raise PermissionError("会话范围不匹配")

    @staticmethod
    def _lease_valid(row: dict[str, Any], now: datetime) -> bool:
        """判断租约是否仍在当前时间之后；缺失或非时间值一律视为失效。"""
        expires = row.get("lease_expires_at")
        return isinstance(expires, datetime) and expires > now

    @staticmethod
    def _select_sql(predicate: str, *, for_update: bool = False) -> str:
        """构造轮次与会话联表查询，使后续身份校验基于同一行快照。"""
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
