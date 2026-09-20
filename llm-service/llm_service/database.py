from __future__ import annotations

import asyncio
import hashlib
import os
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import anyio
from psycopg import AsyncConnection
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from .settings import Settings


MIGRATION_ROOT = Path(__file__).resolve().parent.parent / "migrations"
SCHEMA_LOCK_KEY = "red-culture-agent-schema-migration-v1"


def configure_windows_event_loop_policy() -> None:
    """Windows 上的 Psycopg 异步连接需要选择器事件循环。"""
    if os.name == "nt" and hasattr(asyncio, "WindowsSelectorEventLoopPolicy"):
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())


def selector_event_loop_factory() -> asyncio.AbstractEventLoop:
    """Uvicorn 在 Windows 上运行时，返回 Psycopg 所需的事件循环。"""
    return asyncio.SelectorEventLoop()


configure_windows_event_loop_policy()


class SchemaMigrationError(RuntimeError):
    """数据库 Schema 版本无效；消息不得包含连接凭据。"""


class SchemaOutOfDateError(SchemaMigrationError):
    pass


@dataclass(frozen=True, slots=True)
class MigrationFile:
    version: int
    name: str
    path: Path
    checksum: str


class Database:
    """应用唯一的 PostgreSQL 异步连接池。"""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.pool = AsyncConnectionPool(
            conninfo=settings.database_dsn,
            min_size=settings.database_pool_min_size,
            max_size=settings.database_pool_max_size,
            timeout=settings.database_pool_timeout_seconds,
            open=False,
            kwargs={
                "autocommit": True,
                "prepare_threshold": 0,
                "row_factory": dict_row,
            },
            name="agent-state",
        )

    async def open(self) -> None:
        await self.pool.open()
        try:
            await self.pool.wait(
                timeout=self.settings.database_pool_open_timeout_seconds
            )
        except BaseException:
            await self.pool.close()
            raise

    async def close(self) -> None:
        await self.pool.close()

    @asynccontextmanager
    async def connection(self) -> AsyncIterator[AsyncConnection[dict[str, Any]]]:
    # 连接池上下文还会进入 Psycopg 连接上下文。命令执行期间若任务被取消，
    # 可能会在 Psycopg 完成消费命令前尝试回滚。显式归还连接可以让连接池
    # 丢弃或替换异常连接，避免再次触发隐式提交或回滚边界。
        connection = await self.pool.getconn(
            timeout=self.settings.database_pool_timeout_seconds
        )
        try:
            yield connection
        finally:
            with anyio.CancelScope(shield=True):
                await self.pool.putconn(connection)

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[AsyncConnection[dict[str, Any]]]:
    # 不要在 Psycopg 的显式事务上下文外再嵌套 ``pool.connection()`` 的隐式
    # 连接事务。任务取消时，两个上下文管理器可能同时尝试回滚同一个连接，
    # 从而把仍处于 ACTIVE 状态的连接归还给连接池。
        connection = await self.pool.getconn(
            timeout=self.settings.database_pool_timeout_seconds
        )
        transaction = connection.transaction()
        try:
            await transaction.__aenter__()
            try:
                yield connection
            except BaseException:
                exception = sys.exc_info()
                with anyio.CancelScope(shield=True):
                    suppressed = await transaction.__aexit__(*exception)
                if not suppressed:
                    raise
            else:
                with anyio.CancelScope(shield=True):
                    await transaction.__aexit__(None, None, None)
        finally:
            with anyio.CancelScope(shield=True):
                await self.pool.putconn(connection)

    async def ping(self) -> dict[str, Any]:
        async with self.connection() as connection:
            row = await (
                await connection.execute(
                    "SELECT current_database() AS database_name, "
                    "current_setting('server_version_num')::integer AS server_version_num"
                )
            ).fetchone()
        return dict(row or {})

    def pool_stats(self) -> dict[str, int]:
        return {key: int(value) for key, value in self.pool.get_stats().items()}


class SchemaMigrator:
    def __init__(
        self,
        database: Database,
        migration_dsn: str | None = None,
        migration_root: Path = MIGRATION_ROOT,
    ):
        self.database = database
        self.migration_dsn = migration_dsn
        self.migration_root = migration_root

    @property
    def migrations(self) -> tuple[MigrationFile, ...]:
        values: list[MigrationFile] = []
        for path in sorted(self.migration_root.glob("*.sql")):
            prefix, separator, name = path.stem.partition("_")
            if not separator or not prefix.isdigit():
                raise SchemaMigrationError(f"无效的迁移文件名：{path.name}")
            content = path.read_bytes()
            values.append(
                MigrationFile(
                    version=int(prefix),
                    name=name,
                    path=path,
                    checksum=hashlib.sha256(content).hexdigest(),
                )
            )
        if not values:
            raise SchemaMigrationError("未找到数据库迁移文件")
        versions = [item.version for item in values]
        if versions != list(range(1, len(values) + 1)):
            raise SchemaMigrationError("数据库迁移版本必须连续")
        return tuple(values)

    @property
    def latest_version(self) -> int:
        return self.migrations[-1].version

    @asynccontextmanager
    async def _connection(self) -> AsyncIterator[AsyncConnection[dict[str, Any]]]:
        if self.migration_dsn:
            connection = await AsyncConnection.connect(
                self.migration_dsn,
                autocommit=True,
                prepare_threshold=0,
                row_factory=dict_row,
            )
            try:
                yield connection
            finally:
                await connection.close()
            return
        async with self.database.connection() as connection:
            yield connection

    async def migrate(self) -> int:
        async with self._connection() as connection:
            async with connection.transaction():
                await connection.execute(
                    "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                    (SCHEMA_LOCK_KEY,),
                )
                await self._ensure_control_table(connection)
                applied = await self._applied(connection)
                for migration in self.migrations:
                    previous = applied.get(migration.version)
                    if previous is not None:
                        if previous[1] != migration.checksum:
                            raise SchemaMigrationError(
                                f"迁移文件校验和不匹配：{migration.version}"
                            )
                        continue
                    await connection.execute(
                        migration.path.read_text(encoding="utf-8"),
                        prepare=False,
                    )
                    await connection.execute(
                        """
                        INSERT INTO schema_migration(version, name, checksum)
                        VALUES (%s, %s, %s)
                        """,
                        (migration.version, migration.name, migration.checksum),
                    )
        await self.validate()
        return self.latest_version

    async def validate(self) -> int:
        async with self.database.connection() as connection:
            exists = await (
                await connection.execute(
                    "SELECT to_regclass('schema_migration') AS relation"
                )
            ).fetchone()
            if not exists or exists["relation"] is None:
                raise SchemaOutOfDateError("数据库结构尚未初始化")
            applied = await self._applied(connection)
        expected = {item.version: (item.name, item.checksum) for item in self.migrations}
        if applied != expected:
            raise SchemaOutOfDateError(
                f"数据库结构版本不是当前版本（期望值：{self.latest_version})"
            )
        return self.latest_version

    async def current_version(self) -> int:
        async with self.database.connection() as connection:
            exists = await (
                await connection.execute(
                    "SELECT to_regclass('schema_migration') AS relation"
                )
            ).fetchone()
            if not exists or exists["relation"] is None:
                return 0
            row = await (
                await connection.execute(
                    "SELECT COALESCE(MAX(version), 0) AS version FROM schema_migration"
                )
            ).fetchone()
        return int((row or {}).get("version") or 0)

    @staticmethod
    async def _ensure_control_table(
        connection: AsyncConnection[dict[str, Any]],
    ) -> None:
        await connection.execute(
            """
            CREATE TABLE IF NOT EXISTS schema_migration (
                version BIGINT PRIMARY KEY,
                name TEXT NOT NULL,
                checksum TEXT NOT NULL,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        )

    @staticmethod
    async def _applied(
        connection: AsyncConnection[dict[str, Any]],
    ) -> dict[int, tuple[str, str]]:
        rows = await (
            await connection.execute(
                "SELECT version, name, checksum FROM schema_migration ORDER BY version"
            )
        ).fetchall()
        return {
            int(row["version"]): (str(row["name"]), str(row["checksum"]))
            for row in rows
        }
