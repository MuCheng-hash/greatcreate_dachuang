from __future__ import annotations

import asyncio
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import AsyncMock

from fastapi.testclient import TestClient
from psycopg import OperationalError
from psycopg.types.json import Jsonb
from psycopg_pool import PoolTimeout
import pytest

from llm_service.api import create_app
from llm_service.database import SchemaMigrator
from llm_service.sqlite_import import (
    SqliteImporter,
    SqliteImportError,
    TABLE_SPECS,
)
from llm_service.user_memory import MemoryRepository
from postgres_test_support import (
    open_database,
    open_unmigrated_database,
    run_async,
    settings_for_database,
)


NOW = "2026-08-10T00:00:00+00:00"


def _sqlite_type(table: str, column: str) -> str:
    integers = {
        "id",
        "duration_ms",
        "latency_ms",
        "input_characters",
        "output_characters",
        "input_tokens",
        "output_tokens",
        "total_tokens",
        "first_token_latency_ms",
    }
    reals = {"confidence", "quality_score", "cost_usd"}
    spec = next(item for item in TABLE_SPECS if item.name == table)
    if column in spec.boolean_columns or column in integers:
        return "INTEGER"
    if column in reals:
        return "REAL"
    return "TEXT"


def _create_legacy_sqlite(path: Path) -> None:
    rows = {
        "agent_thread": {
            "thread_id": "thread-legacy-1",
            "owner_id": "account:1",
            "scope_type": "SCHOOL",
            "scope_id": "1",
            "status": "active",
            "summary": "旧会话摘要",
            "created_at": NOW,
            "updated_at": NOW,
        },
        "agent_message": {
            "id": 11,
            "thread_id": "thread-legacy-1",
            "role": "user",
            "content": "旧会话问题",
            "metadata_json": '{"taskType":"CHAT"}',
            "created_at": NOW,
        },
        "agent_tool_audit": {
            "id": 21,
            "thread_id": "thread-legacy-1",
            "tool_name": "search_approved_resources",
            "arguments_json": '{"query":"纪念馆"}',
            "status": "ok",
            "duration_ms": 7,
            "result_preview": "完成",
            "created_at": NOW,
        },
        "agent_memory_setting": {
            "owner_id": "account:1",
            "scope_type": "SCHOOL",
            "scope_id": "1",
            "enabled": 1,
            "created_at": NOW,
            "updated_at": NOW,
        },
        "agent_memory": {
            "id": "memory-legacy-1",
            "owner_id": "account:1",
            "scope_type": "SCHOOL",
            "scope_id": "1",
            "memory_type": "PROFILE",
            "field_key": "grade",
            "content": "常教四年级",
            "status": "active",
            "source": "profile_ui",
            "source_thread_id": "thread-legacy-1",
            "confidence": 0.9,
            "expires_at": None,
            "deleted_at": None,
            "purge_after": None,
            "created_at": NOW,
            "updated_at": NOW,
        },
        "agent_memory_audit": {
            "id": 31,
            "memory_id": "memory-legacy-1",
            "owner_id": "account:1",
            "scope_type": "SCHOOL",
            "scope_id": "1",
            "event_type": "created",
            "metadata_json": '{"fieldKey":"grade"}',
            "created_at": NOW,
        },
        "prompt_version": {
            "prompt_key": "agent",
            "version": "v1",
            "content": "旧 Agent Prompt",
            "active": 1,
            "created_by": "system",
            "notes": "legacy",
            "created_at": NOW,
        },
        "prompt_experiment": {
            "prompt_key": "agent",
            "experiment_key": "legacy-exp",
            "variants_json": '[{"version":"v1","weight":100}]',
            "active": 1,
            "updated_at": NOW,
        },
        "prompt_run": {
            "run_id": "run-legacy-1",
            "prompt_key": "agent",
            "version": "v1",
            "experiment_key": "legacy-exp",
            "variant": "v1",
            "subject_key": "account:1",
            "model": "test-model",
            "status": "completed",
            "latency_ms": 100,
            "input_characters": 10,
            "output_characters": 20,
            "quality_score": 4.5,
            "feedback": "准确",
            "error_message": "",
            "created_at": NOW,
            "completed_at": NOW,
        },
        "llm_trace": {
            "call_id": "call-legacy-1",
            "trace_id": "trace-legacy-1",
            "span_id": "span-legacy-1",
            "parent_span_id": None,
            "user_id": "account:1",
            "session_id": "thread-legacy-1",
            "feature": "agent-runtime",
            "provider": "test",
            "model": "test-model",
            "status": "completed",
            "error_type": None,
            "error_message": "",
            "valid_json": 1,
            "input_tokens": 10,
            "output_tokens": 20,
            "total_tokens": 30,
            "token_source": "provider",
            "cost_usd": 0.1,
            "latency_ms": 100,
            "first_token_latency_ms": 20,
            "metadata_json": '{"fallbackLevel":0}',
            "started_at": NOW,
            "completed_at": NOW,
        },
    }
    with sqlite3.connect(path) as connection:
        for spec in TABLE_SPECS:
            definitions = ", ".join(
                f'"{column}" {_sqlite_type(spec.name, column)}'
                for column in spec.columns
            )
            connection.execute(f'CREATE TABLE "{spec.name}" ({definitions})')
            values = rows[spec.name]
            columns = tuple(values)
            placeholders = ", ".join("?" for _ in columns)
            selected = ", ".join(f'"{column}"' for column in columns)
            connection.execute(
                f'INSERT INTO "{spec.name}" ({selected}) VALUES ({placeholders})',
                tuple(values[column] for column in columns),
            )
        connection.commit()


def test_empty_schema_migration_is_repeatable_and_uses_postgresql_types(
    tmp_path: Path,
) -> None:
    settings = settings_for_database(tmp_path)
    database = open_unmigrated_database(settings)
    migrator = SchemaMigrator(database)

    async def exercise() -> None:
        assert await migrator.current_version() == 0
        assert await migrator.migrate() == 1
        assert await migrator.migrate() == 1
        assert await migrator.validate() == 1
        async with database.connection() as connection:
            rows = await (
                await connection.execute(
                    """
                    SELECT table_name, column_name, data_type, is_identity
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND (table_name, column_name) IN (
                          ('agent_message', 'id'),
                          ('agent_message', 'metadata_json'),
                          ('agent_memory_setting', 'enabled'),
                          ('agent_thread', 'created_at')
                      )
                    """
                )
            ).fetchall()
        types = {
            (row["table_name"], row["column_name"]): (
                row["data_type"], row["is_identity"]
            )
            for row in rows
        }
        assert types[("agent_message", "id")][1] == "YES"
        assert types[("agent_message", "metadata_json")][0] == "jsonb"
        assert types[("agent_memory_setting", "enabled")][0] == "boolean"
        assert (
            types[("agent_thread", "created_at")][0]
            == "timestamp with time zone"
        )

    run_async(exercise())


def test_sqlite_import_rolls_back_then_imports_and_repeats_safely(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "legacy.sqlite3"
    _create_legacy_sqlite(source)
    settings = settings_for_database(tmp_path)
    database = open_database(settings)
    importer = SqliteImporter(database, SchemaMigrator(database))

    async def exercise() -> None:
        dry_run = await importer.dry_run(source)
        assert dry_run["readyToApply"] is True
        assert set(dry_run["sourceRowCounts"].values()) == {1}

        reset_sequences = importer._reset_sequences

        async def fail_after_insert(_connection) -> None:
            raise SqliteImportError("forced rollback")

        monkeypatch.setattr(importer, "_reset_sequences", fail_after_insert)
        with pytest.raises(SqliteImportError, match="forced rollback"):
            await importer.apply(source)
        assert set((await importer._target_counts()).values()) == {0}

        async with database.connection() as connection:
            migration_count = await (
                await connection.execute(
                    "SELECT COUNT(*) AS count FROM data_migration"
                )
            ).fetchone()
        assert migration_count["count"] == 0

        monkeypatch.setattr(importer, "_reset_sequences", reset_sequences)
        applied = await importer.apply(source)
        assert applied["status"] == "completed"
        assert set(applied["rowCounts"].values()) == {1}
        assert applied["verification"]["foreignKeyOrphans"] == 0
        assert applied["verification"]["activePromptUnique"] is True
        assert applied["verification"]["jsonbValidatedByDatabase"] is True
        assert Path(applied["backup"]).is_file()

        async with database.transaction() as connection:
            await connection.execute(
                """
                INSERT INTO agent_thread(
                    thread_id, owner_id, scope_type, scope_id, status,
                    summary, created_at, updated_at
                ) VALUES (%s, %s, %s, %s, 'active', '', %s, %s)
                """,
                (
                    "thread-after-cutover",
                    "account:2",
                    "SCHOOL",
                    "2",
                    datetime.now(timezone.utc),
                    datetime.now(timezone.utc),
                ),
            )
            await connection.execute(
                """
                INSERT INTO agent_message(
                    thread_id, role, content, metadata_json, created_at
                ) VALUES (%s, 'user', %s, %s, %s)
                """,
                (
                    "thread-after-cutover",
                    "PostgreSQL 新消息",
                    Jsonb({"taskType": "CHAT"}),
                    datetime.now(timezone.utc),
                ),
            )

        repeated = await importer.apply(source)
        assert repeated["status"] == "already-imported"
        assert repeated["verification"]["sourceRowCounts"] == applied["rowCounts"]
        assert repeated["verification"]["rowCounts"]["agent_thread"] == 2
        assert repeated["verification"]["rowCounts"]["agent_message"] == 2
        assert repeated["verification"]["sourcePrimaryKeysPresent"] is True
        assert len(list(tmp_path.glob("legacy.backup-*.sqlite3"))) == 2

    run_async(exercise())


def test_concurrent_memory_cleanup_and_conflict_replacement_are_atomic(
    tmp_path: Path,
) -> None:
    settings = settings_for_database(
        tmp_path,
        database_pool_min_size=1,
        database_pool_max_size=4,
    )
    database = open_database(settings)
    clock = [datetime(2026, 8, 1, tzinfo=timezone.utc)]
    first = MemoryRepository(database, now_provider=lambda: clock[0])
    second = MemoryRepository(database, now_provider=lambda: clock[0])

    async def exercise() -> None:
        for index in range(4):
            await first.create_memory(
                "account:1",
                "SCHOOL",
                "1",
                memory_type="PROFILE",
                content=f"待确认偏好 {index}",
                status="pending",
                source="inferred_chat",
            )
        clock[0] += timedelta(days=8)
        cleanup_results = await asyncio.gather(
            first.cleanup_expired(batch_size=1),
            second.cleanup_expired(batch_size=1),
        )
        assert sum(item["pending"] for item in cleanup_results) == 4
        assert await first.list_memories("account:1", "SCHOOL", "1") == []

        created = await asyncio.gather(
            first.create_memory(
                "account:1",
                "SCHOOL",
                "1",
                memory_type="PROFILE",
                field_key="grade",
                content="常教四年级",
                source="profile_ui",
                replace_conflicts=True,
            ),
            second.create_memory(
                "account:1",
                "SCHOOL",
                "1",
                memory_type="PROFILE",
                field_key="grade",
                content="常教五年级",
                source="profile_ui",
                replace_conflicts=True,
            ),
        )
        active = await first.list_memories(
            "account:1", "SCHOOL", "1", status="active"
        )
        deleted = await first.list_memories(
            "account:1", "SCHOOL", "1", status="deleted"
        )
        assert len(created) == 2
        assert len(active) == 1
        assert len(deleted) == 1
        assert {active[0].content, deleted[0].content} == {
            "常教四年级",
            "常教五年级",
        }

    run_async(exercise())


def test_pool_exhaustion_is_bounded(tmp_path: Path) -> None:
    settings = settings_for_database(
        tmp_path,
        database_pool_min_size=1,
        database_pool_max_size=1,
        database_pool_timeout_seconds=0.05,
    )
    database = open_database(settings)

    async def exercise() -> None:
        async with database.connection():
            with pytest.raises(PoolTimeout):
                async with database.connection():
                    pass

    run_async(exercise())


def test_cancelled_database_request_releases_connection(tmp_path: Path) -> None:
    settings = settings_for_database(
        tmp_path,
        database_pool_min_size=1,
        database_pool_max_size=1,
    )
    database = open_database(settings)

    async def exercise() -> None:
        async def slow_query() -> None:
            async with database.connection() as connection:
                await connection.execute("SELECT pg_sleep(10)")

        task = asyncio.create_task(slow_query())
        await asyncio.sleep(0.1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        async with database.connection() as connection:
            row = await (
                await connection.execute("SELECT 1 AS value")
            ).fetchone()
        assert row["value"] == 1

    run_async(exercise())


def test_database_failure_returns_sanitized_503(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    settings = settings_for_database(
        tmp_path,
        internal_service_token="postgres-test-token",
        internal_business_base_url="",
        business_health_required=False,
        llm_api_url="",
        llm_api_key="",
    )
    application = create_app(settings)
    leaked_dsn = "postgresql://agent:super-secret@db.internal/agent"

    with TestClient(
        application,
        headers={"X-Agent-Service-Token": settings.internal_service_token},
    ) as client:
        monkeypatch.setattr(
            application.state.container.repository,
            "list_threads",
            AsyncMock(side_effect=OperationalError(leaked_dsn)),
        )
        response = client.get(
            "/agent/threads", params={"ownerId": "account:1"}
        )

    assert response.status_code == 503
    assert response.json() == {"detail": "database temporarily unavailable"}
    assert "super-secret" not in response.text
    assert "postgresql://" not in response.text
