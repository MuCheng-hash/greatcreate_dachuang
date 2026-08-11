from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Sequence
from typing import Any

from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    ChannelVersions,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
)
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.checkpoint.postgres.base import MIGRATIONS
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer

from .database import Database


CHECKPOINT_RELATIONS = (
    "checkpoint_migrations",
    "checkpoints",
    "checkpoint_blobs",
    "checkpoint_writes",
)


class CheckpointSchemaError(RuntimeError):
    """Checkpointer 表缺失或版本不匹配；错误消息不得包含 DSN。"""


class NamespaceCheckpointSaver(BaseCheckpointSaver):
    """把 LangGraph 根命名空间映射为持久化的模型尝试命名空间。

    LangGraph 会把根图调用中的非空 ``checkpoint_ns`` 归一化为空字符串，
    因此不能直接依靠调用配置隔离模型降级尝试。该适配器只改变存储坐标，
    返回给图的配置仍保持根命名空间语义。
    """

    _separator = "|"

    def __init__(self, delegate: AsyncPostgresSaver, namespace: str):
        if not namespace.strip():
            raise ValueError("checkpoint namespace cannot be empty")
        super().__init__(serde=delegate.serde)
        self.delegate = delegate
        self.namespace = namespace.strip()

    @property
    def config_specs(self) -> list:
        return self.delegate.config_specs

    def get_next_version(self, current: Any | None, channel: None) -> Any:
        return self.delegate.get_next_version(current, channel)

    async def aget_tuple(
        self, config: RunnableConfig
    ) -> CheckpointTuple | None:
        value = await self.delegate.aget_tuple(self._scoped(config))
        return self._unscoped_tuple(value)

    async def alist(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> AsyncIterator[CheckpointTuple]:
        scoped_config = self._scoped(config or {"configurable": {}})
        scoped_before = self._scoped(before) if before is not None else None
        async for value in self.delegate.alist(
            scoped_config, filter=filter, before=scoped_before, limit=limit
        ):
            unscoped = self._unscoped_tuple(value)
            if unscoped is not None:
                yield unscoped

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        stored = await self.delegate.aput(
            self._scoped(config), checkpoint, metadata, new_versions
        )
        return self._unscoped(stored)

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        await self.delegate.aput_writes(
            self._scoped(config), writes, task_id, task_path
        )

    async def adelete_thread(self, thread_id: str) -> None:
        await self.delegate.adelete_thread(thread_id)

    def _scoped(self, config: RunnableConfig) -> RunnableConfig:
        configurable = dict(config.get("configurable") or {})
        inner_namespace = str(configurable.get("checkpoint_ns") or "")
        configurable["checkpoint_ns"] = (
            self.namespace
            if not inner_namespace
            else f"{self.namespace}{self._separator}{inner_namespace}"
        )
        return {**config, "configurable": configurable}

    def _unscoped(self, config: RunnableConfig) -> RunnableConfig:
        configurable = dict(config.get("configurable") or {})
        stored_namespace = str(configurable.get("checkpoint_ns") or "")
        prefix = f"{self.namespace}{self._separator}"
        if stored_namespace == self.namespace:
            configurable["checkpoint_ns"] = ""
        elif stored_namespace.startswith(prefix):
            configurable["checkpoint_ns"] = stored_namespace[len(prefix):]
        return {**config, "configurable": configurable}

    def _unscoped_tuple(
        self, value: CheckpointTuple | None
    ) -> CheckpointTuple | None:
        if value is None:
            return None
        return CheckpointTuple(
            config=self._unscoped(value.config),
            checkpoint=value.checkpoint,
            metadata=value.metadata,
            parent_config=(
                self._unscoped(value.parent_config)
                if value.parent_config is not None
                else None
            ),
            pending_writes=value.pending_writes,
        )


class CheckpointManager:
    """单轮 LangGraph 执行状态的 PostgreSQL 持久化入口。"""

    def __init__(self, database: Database):
        self.database = database
        self.serializer = JsonPlusSerializer(
            pickle_fallback=False,
            allowed_json_modules=None,
            allowed_msgpack_modules=None,
        )
        self._saver: AsyncPostgresSaver | None = None
        self._saver_loop: asyncio.AbstractEventLoop | None = None

    @property
    def saver(self) -> AsyncPostgresSaver:
        loop = asyncio.get_running_loop()
        if self._saver is None or self._saver_loop is not loop:
            self._saver = AsyncPostgresSaver(
                self.database.pool, serde=self.serializer
            )
            self._saver_loop = loop
        return self._saver

    @property
    def latest_version(self) -> int:
        return len(MIGRATIONS) - 1

    async def setup(self, migration_dsn: str | None = None) -> int:
        if migration_dsn:
            async with AsyncPostgresSaver.from_conn_string(
                migration_dsn, serde=self.serializer
            ) as migration_saver:
                await migration_saver.setup()
        else:
            await self.saver.setup()
        return await self.validate()

    async def validate(self) -> int:
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    """
                    SELECT
                        to_regclass('checkpoint_migrations') AS migrations,
                        to_regclass('checkpoints') AS checkpoints,
                        to_regclass('checkpoint_blobs') AS blobs,
                        to_regclass('checkpoint_writes') AS writes
                    """
                )
            ).fetchone()
            if not row or any(row.get(key) is None for key in row):
                raise CheckpointSchemaError("checkpointer schema is not initialized")
            version = await (
                await connection.execute(
                    """
                    SELECT COALESCE(MIN(v), -1) AS minimum,
                           COALESCE(MAX(v), -1) AS maximum,
                           COUNT(*) AS migration_count
                    FROM checkpoint_migrations
                    """
                )
            ).fetchone()
        expected_count = self.latest_version + 1
        if (
            not version
            or int(version["minimum"]) != 0
            or int(version["maximum"]) != self.latest_version
            or int(version["migration_count"]) != expected_count
        ):
            raise CheckpointSchemaError(
                f"checkpointer schema version is not current (expected {self.latest_version})"
            )
        return self.latest_version

    async def has_checkpoint(self, thread_id: str, checkpoint_ns: str) -> bool:
        value = await self.saver.aget_tuple(
            self.config(thread_id, checkpoint_ns)
        )
        return value is not None

    def scoped_saver(self, checkpoint_ns: str) -> NamespaceCheckpointSaver:
        return NamespaceCheckpointSaver(self.saver, checkpoint_ns)

    async def delete_thread(self, thread_id: str) -> None:
        await self.saver.adelete_thread(thread_id)

    @staticmethod
    def config(thread_id: str, checkpoint_ns: str) -> dict[str, Any]:
        return {
            "configurable": {
                "thread_id": thread_id,
                "checkpoint_ns": checkpoint_ns,
            }
        }

    @staticmethod
    def graph_config(thread_id: str) -> dict[str, Any]:
        return {"configurable": {"thread_id": thread_id}}
