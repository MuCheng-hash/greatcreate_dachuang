from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Awaitable, Sequence
from typing import Any, TypeVar

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

T = TypeVar("T")


async def _await_checkpoint_operation(operation: Awaitable[T]) -> T:
    """在传播任务取消前，先完成已经开始的 Psycopg 操作。

    如果流水线命令仍在执行时归还连接，Psycopg 会丢弃该连接，服务端命令也可能继续运行。
    检查点语句执行时间较短，因此取消操作会立即停止图和模型工作，
    但会等待已经开始的数据库语句完成。
    """

    task = asyncio.ensure_future(operation)
    try:
        return await asyncio.shield(task)
    except asyncio.CancelledError:
        while not task.done():
            try:
                await asyncio.shield(task)
            except asyncio.CancelledError:
                continue
        if not task.cancelled():
            try:
                task.result()
            except Exception:
                # 调用方已经被取消；消费该异常可避免任务成为未观察到的后台失败。
                pass
        raise


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
        value = await _await_checkpoint_operation(
            self.delegate.aget_tuple(self._scoped(config))
        )
        return self._unscoped_tuple(value)

    async def alist(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> AsyncIterator[CheckpointTuple]:
        """列出当前命名空间的检查点，并在返回前去除内部前缀。"""
        scoped_config = self._scoped(config or {"configurable": {}})
        scoped_before = self._scoped(before) if before is not None else None
        iterator = self.delegate.alist(
            scoped_config, filter=filter, before=scoped_before, limit=limit
        )
        try:
            while True:
                try:
                    value = await _await_checkpoint_operation(anext(iterator))
                except StopAsyncIteration:
                    break
                unscoped = self._unscoped_tuple(value)
                if unscoped is not None:
                    # 调用方只能看到自己的逻辑命名空间，不能据此拼接其他轮次的存储键。
                    yield unscoped
        finally:
            # 提前退出迭代也要关闭数据库游标，避免长连接泄漏到后续 Agent 轮次。
            await _await_checkpoint_operation(iterator.aclose())

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        """将图状态写入命名空间隔离后的存储键，并还原调用方配置。"""
        stored = await _await_checkpoint_operation(
            self.delegate.aput(
                self._scoped(config), checkpoint, metadata, new_versions
            )
        )
        return self._unscoped(stored)

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        """持久化图节点的增量写入，保持与所属检查点相同的命名空间。"""
        await _await_checkpoint_operation(
            self.delegate.aput_writes(
                self._scoped(config), writes, task_id, task_path
            )
        )

    async def adelete_thread(self, thread_id: str) -> None:
        """删除委托存储中指定线程的全部命名空间检查点。"""
        await _await_checkpoint_operation(
            self.delegate.adelete_thread(thread_id)
        )

    def _scoped(self, config: RunnableConfig) -> RunnableConfig:
        """为底层存储键附加受控前缀，隔离不同图或任务的检查点。"""
        configurable = dict(config.get("configurable") or {})
        inner_namespace = str(configurable.get("checkpoint_ns") or "")
        configurable["checkpoint_ns"] = (
            self.namespace
            if not inner_namespace
            else f"{self.namespace}{self._separator}{inner_namespace}"
        )
        return {**config, "configurable": configurable}

    def _unscoped(self, config: RunnableConfig) -> RunnableConfig:
        """移除仅供持久化使用的前缀，使上层恢复时保持原有配置形状。"""
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
        """还原检查点及其父配置的命名空间，避免恢复链暴露内部存储坐标。"""
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
        """按事件循环缓存 saver，避免跨事件循环复用异步资源。"""
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
        """仅在开发初始化路径创建检查点表，完成后立即校验完整迁移版本。"""
        if migration_dsn:
            async with AsyncPostgresSaver.from_conn_string(
                migration_dsn, serde=self.serializer
            ) as migration_saver:
                await _await_checkpoint_operation(migration_saver.setup())
        else:
            await _await_checkpoint_operation(self.saver.setup())
        return await self.validate()

    async def validate(self) -> int:
        """验证生产环境检查点表和迁移序列完整，拒绝带缺口的部分初始化状态。"""
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
            # 只比较最大版本不足会放过中间缺失迁移，因此同时校验最小值、最大值和数量。
            raise CheckpointSchemaError(
                f"checkpointer schema version is not current (expected {self.latest_version})"
            )
        return self.latest_version

    async def has_checkpoint(self, thread_id: str, checkpoint_ns: str) -> bool:
        """判断指定轮次与命名空间是否已有可恢复图状态，不读取其正文。"""
        value = await _await_checkpoint_operation(
            self.saver.aget_tuple(self.config(thread_id, checkpoint_ns))
        )
        return value is not None

    def scoped_saver(self, checkpoint_ns: str) -> NamespaceCheckpointSaver:
        """返回限定命名空间的 saver，供单个图执行防止键空间串用。"""
        return NamespaceCheckpointSaver(self.saver, checkpoint_ns)

    async def delete_thread(self, thread_id: str) -> None:
        """清理轮次关联的检查点；调用方负责先取得清理任务的租约。"""
        await _await_checkpoint_operation(
            self.saver.adelete_thread(thread_id)
        )

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
