from __future__ import annotations

import asyncio
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from typing import TypedDict
from unittest.mock import AsyncMock

from fastapi.testclient import TestClient
from psycopg import OperationalError
from psycopg.types.json import Jsonb
from psycopg_pool import PoolTimeout
import pytest
from langgraph.graph import END, START, StateGraph
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer

from llm_service.api import create_app
from llm_service.actions import AgentActionRepository, ActionConflictError
from llm_service.checkpointing import CheckpointManager, NamespaceCheckpointSaver
from llm_service.database import SchemaMigrator
from llm_service.repository import ConversationRepository
from llm_service.runtime import ActionConfirmationRequired, AgentRuntime
from llm_service.schemas import AgentMessageRequest, TrustedContext
from llm_service.sqlite_import import (
    SqliteImporter,
    SqliteImportError,
    TABLE_SPECS,
)
from llm_service.user_memory import MemoryRepository
from llm_service.turns import AgentTurnRepository, TurnConflictError, TurnRegistration
from llm_service.tools import ToolRuntimeContext
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
        assert await migrator.migrate() == 3
        assert await migrator.migrate() == 3
        assert await migrator.validate() == 3
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
        async with database.connection() as connection:
            imported_thread = await (
                await connection.execute(
                    """
                    SELECT summary, summary_through_message_id
                    FROM agent_thread
                    WHERE thread_id = 'thread-legacy-1'
                    """
                )
            ).fetchone()
        assert imported_thread == {
            "summary": "",
            "summary_through_message_id": 0,
        }

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


def test_cancelled_database_transaction_returns_an_idle_connection(
    tmp_path: Path, caplog,
) -> None:
    settings = settings_for_database(
        tmp_path,
        database_pool_min_size=1,
        database_pool_max_size=1,
    )
    database = open_database(settings)

    async def exercise() -> None:
        async def slow_transaction() -> None:
            async with database.transaction() as connection:
                await connection.execute("SELECT pg_sleep(10)")

        task = asyncio.create_task(slow_transaction())
        await asyncio.sleep(0.1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        async with database.connection() as connection:
            row = await (
                await connection.execute("SELECT 1 AS value")
            ).fetchone()
        assert row["value"] == 1

    with caplog.at_level("WARNING"):
        run_async(exercise())
    messages = [record.getMessage() for record in caplog.records]
    assert not any("error ignored in rollback" in value for value in messages)
    assert not any("closing returned connection" in value for value in messages)


def test_cancelled_checkpoint_write_drains_before_connection_is_released() -> None:
    started = asyncio.Event()
    release = asyncio.Event()
    finished = asyncio.Event()

    class SlowCheckpointSaver:
        serde = JsonPlusSerializer(pickle_fallback=False)
        config_specs: list = []

        async def aput(self, config, _checkpoint, _metadata, _new_versions):
            started.set()
            await release.wait()
            finished.set()
            return config

    async def exercise() -> None:
        saver = NamespaceCheckpointSaver(SlowCheckpointSaver(), "chat-v1")
        task = asyncio.create_task(saver.aput(
            {
                "configurable": {
                    "thread_id": "turn-cancelled-during-checkpoint",
                    "checkpoint_ns": "",
                }
            },
            {"id": "checkpoint-1"},
            {},
            {},
        ))
        await asyncio.wait_for(started.wait(), timeout=1)
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done()

        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert finished.is_set()

    run_async(exercise())


def test_summary_cursor_is_monotonic_and_never_repeats_content(
    tmp_path: Path,
) -> None:
    settings = settings_for_database(
        tmp_path,
        agent_context_token_budget=25,
        agent_recent_message_count=2,
        agent_summary_character_limit=4000,
    )
    database = open_database(settings)
    repository = ConversationRepository(database)

    async def exercise() -> None:
        thread = await repository.create_thread("account:1", "SCHOOL", "1")
        contents = [f"唯一消息-{index}-" + "甲" * 12 for index in range(1, 7)]
        for index, content in enumerate(contents):
            await repository.append_message(
                thread.thread_id,
                "user" if index % 2 == 0 else "assistant",
                content,
            )

        cursors: list[int] = []
        runtime = AgentRuntime(settings, repository)
        for iteration in range(6):
            current = await repository.get_thread(thread.thread_id, "account:1")
            if iteration == 2:
                runtime = AgentRuntime(settings, repository)
            await runtime._context_window(current)
            persisted = await repository.get_thread(thread.thread_id, "account:1")
            cursors.append(persisted.summary_through_message_id)

        final = await repository.get_thread(thread.thread_id, "account:1")
        assert cursors == sorted(cursors)
        assert final.summary_through_message_id > 0
        assert all(final.summary.count(content) <= 1 for content in contents)

        stable_window = await AgentRuntime(settings, repository)._context_window(final)
        stable = await repository.get_thread(thread.thread_id, "account:1")
        assert stable_window.compacted is False
        assert stable.summary == final.summary
        assert stable.summary_through_message_id == final.summary_through_message_id

    run_async(exercise())


def test_turn_registration_is_idempotent_and_thread_scoped(
    tmp_path: Path,
) -> None:
    settings = settings_for_database(
        tmp_path,
        database_pool_min_size=1,
        database_pool_max_size=4,
    )
    database = open_database(settings)
    conversations = ConversationRepository(database)
    turns = AgentTurnRepository(database)

    async def register(client_turn_id: str, request_hash: str, lease_owner: str):
        return await turns.register(
            client_turn_id=client_turn_id,
            requested_thread_id=thread.thread_id,
            owner_id="account:1",
            scope_type="SCHOOL",
            scope_id="1",
            task_type="CHAT",
            request_hash=request_hash,
            request_summary={"message": "同一问题", "taskType": "CHAT"},
            lease_owner=lease_owner,
            lease_seconds=30,
        )

    async def exercise() -> None:
        nonlocal thread
        thread = await conversations.create_thread("account:1", "SCHOOL", "1")
        results = await asyncio.gather(
            register("same-client-turn", "a" * 64, "instance-a"),
            register("same-client-turn", "a" * 64, "instance-b"),
            return_exceptions=True,
        )
        registrations = [item for item in results if isinstance(item, TurnRegistration)]
        conflicts = [item for item in results if isinstance(item, TurnConflictError)]
        assert len(registrations) == 1
        assert [item.code for item in conflicts] == ["turn_in_progress"]
        winner = registrations[0].turn

        with pytest.raises(TurnConflictError) as busy:
            await register("different-client-turn", "b" * 64, "instance-c")
        assert busy.value.code == "thread_busy"

        await turns.complete(
            turn_id=winner.turn_id,
            lease_owner=winner.lease_owner or "",
            user_content="同一问题",
            user_metadata={"clientTurnId": winner.client_turn_id},
            assistant_content="唯一完整答案",
            assistant_metadata={"clientTurnId": winner.client_turn_id},
            response={
                "threadId": thread.thread_id,
                "clientTurnId": winner.client_turn_id,
                "taskType": "CHAT",
                "answer": "唯一完整答案",
                "status": "completed",
            },
        )
        replay = await register(
            "same-client-turn", "a" * 64, "instance-after-restart"
        )
        assert replay.resumed is True
        assert replay.turn.status == "completed"
        assert replay.turn.attempt_count == 1

        with pytest.raises(TurnConflictError) as payload_conflict:
            await register(
                "same-client-turn", "c" * 64, "instance-after-restart"
            )
        assert payload_conflict.value.code == "client_turn_conflict"

        visible = await conversations.list_messages(thread.thread_id)
        assert [item["content"] for item in visible] == [
            "同一问题",
            "唯一完整答案",
        ]

    thread = None
    run_async(exercise())


def test_agent_action_confirmation_is_scoped_idempotent_and_expiring(
    tmp_path: Path,
) -> None:
    settings = settings_for_database(tmp_path)
    database = open_database(settings)
    conversations = ConversationRepository(database)
    turns = AgentTurnRepository(database)
    actions = AgentActionRepository(database, confirmation_minutes=15)

    async def exercise() -> None:
        thread = await conversations.create_thread("account:1", "SCHOOL", "1")
        registration = await turns.register(
            client_turn_id="action-client-turn",
            requested_thread_id=thread.thread_id,
            owner_id="account:1",
            scope_type="SCHOOL",
            scope_id="1",
            task_type="CHAT",
            request_hash="d" * 64,
            request_summary={"message": "执行高风险操作", "taskType": "CHAT"},
            lease_owner="instance-a",
            lease_seconds=30,
        )
        created = await actions.create_or_get(
            turn_id=registration.turn.turn_id,
            logical_call_id="logical-write-1",
            tool_name="future_write_tool",
            arguments={"resourceId": 7, "enabled": True},
            sanitized_arguments={"resourceId": "7", "enabled": "True"},
        )
        duplicate = await actions.create_or_get(
            turn_id=registration.turn.turn_id,
            logical_call_id="logical-write-1",
            tool_name="future_write_tool",
            arguments={"enabled": True, "resourceId": 7},
            sanitized_arguments={"resourceId": "7", "enabled": "True"},
        )
        assert duplicate.action_id == created.action_id
        assert created.status == "pending_confirmation"

        waiting = await turns.get("action-client-turn")
        assert waiting is not None and waiting.status == "awaiting_confirmation"
        with pytest.raises(TurnConflictError) as confirmation_required:
            await turns.register(
                client_turn_id="action-client-turn",
                requested_thread_id=thread.thread_id,
                owner_id="account:1",
                scope_type="SCHOOL",
                scope_id="1",
                task_type="CHAT",
                request_hash="d" * 64,
                request_summary={"message": "执行高风险操作", "taskType": "CHAT"},
                lease_owner="instance-b",
                lease_seconds=30,
            )
        assert confirmation_required.value.code == "action_confirmation_required"

        with pytest.raises(PermissionError):
            await actions.get_for_scope(created.action_id, "account:2", "SCHOOL", "1")
        approved = await actions.decide(
            action_id=created.action_id,
            decision="approve",
            owner_id="account:1",
            scope_type="SCHOOL",
            scope_id="1",
        )
        approved_again = await actions.decide(
            action_id=created.action_id,
            decision="approve",
            owner_id="account:1",
            scope_type="SCHOOL",
            scope_id="1",
        )
        assert approved.status == approved_again.status == "approved"

        resumed = await turns.register(
            client_turn_id="action-client-turn",
            requested_thread_id=thread.thread_id,
            owner_id="account:1",
            scope_type="SCHOOL",
            scope_id="1",
            task_type="CHAT",
            request_hash="d" * 64,
            request_summary={"message": "执行高风险操作", "taskType": "CHAT"},
            lease_owner="instance-c",
            lease_seconds=30,
        )
        assert resumed.resumed is True and resumed.turn.status == "running"
        executing = await actions.mark_executing(created.action_id)
        succeeded = await actions.mark_succeeded(
            executing.action_id,
            result={"resourceId": 7},
            result_summary="已更新",
            resource_reference="resource:7",
        )
        assert succeeded.status == "succeeded"
        assert succeeded.resource_reference == "resource:7"

        expiring = await actions.create_or_get(
            turn_id=registration.turn.turn_id,
            logical_call_id="logical-write-2",
            tool_name="future_write_tool",
            arguments={"resourceId": 8},
            sanitized_arguments={"resourceId": "8"},
        )
        async with database.transaction() as connection:
            await connection.execute(
                "UPDATE agent_action SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE action_id = %s",
                (expiring.action_id,),
            )
        expired = await actions.get_for_scope(
            expiring.action_id, "account:1", "SCHOOL", "1"
        )
        assert expired is not None and expired.status == "expired"

    run_async(exercise())


def test_hitl_interrupt_persists_stable_action_before_any_write(
    tmp_path: Path,
) -> None:
    settings = settings_for_database(tmp_path)
    database = open_database(settings)
    conversations = ConversationRepository(database)
    turns = AgentTurnRepository(database)
    actions = AgentActionRepository(database)
    runtime = AgentRuntime(
        settings,
        conversations,
        turn_repository=turns,
        action_repository=actions,
    )
    request = AgentMessageRequest.model_validate(
        {
            "ownerId": "account:1",
            "scopeType": "SCHOOL",
            "scopeId": 1,
            "clientTurnId": "hitl-client-turn",
            "message": "执行未来写工具",
            "context": {},
        }
    )

    class InterruptedAgent:
        async def aget_state(self, _config):
            interrupt = SimpleNamespace(
                id="stable-graph-interrupt-1",
                value={
                    "action_requests": [
                        {
                            "name": "future_write_tool",
                            "args": {"resourceId": 7, "secretToken": "hidden"},
                        }
                    ]
                },
            )
            return SimpleNamespace(
                tasks=(SimpleNamespace(interrupts=(interrupt,)),)
            )

    async def exercise() -> None:
        thread = await conversations.create_thread("account:1", "SCHOOL", "1")
        registration = await turns.register(
            client_turn_id=request.client_turn_id,
            requested_thread_id=thread.thread_id,
            owner_id=request.owner_id,
            scope_type=request.scope_type,
            scope_id=request.scope_id,
            task_type="CHAT",
            request_hash="f" * 64,
            request_summary={"message": request.message, "taskType": "CHAT"},
            lease_owner=runtime.instance_id,
            lease_seconds=30,
        )
        plan = runtime.planner.plan(request.message)
        with pytest.raises(ActionConfirmationRequired) as interrupted:
            await runtime._raise_for_pending_action(
                InterruptedAgent(),
                registration.turn,
                request,
                thread,
                None,
                plan,
                "chat-v1/model-attempt-1",
            )
        action = interrupted.value.action
        assert action.logical_call_id == "stable-graph-interrupt-1"
        assert action.status == "pending_confirmation"
        assert action.sanitized_arguments == {"resourceId": "7"}
        assert await conversations.list_tool_audits(limit=10) == []
        waiting = await turns.get(request.client_turn_id)
        assert waiting is not None and waiting.status == "awaiting_confirmation"

        with pytest.raises(ActionConfirmationRequired) as repeated:
            await runtime._raise_for_pending_action(
                InterruptedAgent(),
                registration.turn,
                request,
                thread,
                None,
                plan,
                "chat-v1/model-attempt-1",
            )
        assert repeated.value.action.action_id == action.action_id

    run_async(exercise())


def test_action_api_enforces_scope_and_idempotent_decision(tmp_path: Path) -> None:
    settings = settings_for_database(
        tmp_path,
        internal_service_token="postgres-test-token",
        internal_business_base_url="",
        business_health_required=False,
    )
    database = open_database(settings)
    conversations = ConversationRepository(database)
    turns = AgentTurnRepository(database)
    actions = AgentActionRepository(database)

    async def seed() -> str:
        thread = await conversations.create_thread("account:1", "SCHOOL", "1")
        registration = await turns.register(
            client_turn_id="action-api-turn",
            requested_thread_id=thread.thread_id,
            owner_id="account:1",
            scope_type="SCHOOL",
            scope_id="1",
            task_type="CHAT",
            request_hash="a" * 64,
            request_summary={"message": "待确认", "taskType": "CHAT"},
            lease_owner="seed-instance",
            lease_seconds=30,
        )
        action = await actions.create_or_get(
            turn_id=registration.turn.turn_id,
            logical_call_id="api-write-1",
            tool_name="future_write_tool",
            arguments={"resourceId": 9},
            sanitized_arguments={"resourceId": "9"},
        )
        return action.action_id

    action_id = run_async(seed())
    application = create_app(settings)
    headers = {"X-Agent-Service-Token": settings.internal_service_token}
    with TestClient(application, headers=headers) as client:
        hidden = client.get(
            f"/agent/actions/{action_id}",
            params={"ownerId": "account:2", "scopeType": "SCHOOL", "scopeId": 1},
        )
        assert hidden.status_code == 404
        visible = client.get(
            f"/agent/actions/{action_id}",
            params={"ownerId": "account:1", "scopeType": "SCHOOL", "scopeId": 1},
        )
        assert visible.status_code == 200
        assert visible.json()["status"] == "pending_confirmation"

        payload = {
            "ownerId": "account:1",
            "scopeType": "SCHOOL",
            "scopeId": 1,
            "decision": "approve",
        }
        first = client.post(f"/agent/actions/{action_id}/decision", json=payload)
        repeated = client.post(f"/agent/actions/{action_id}/decision", json=payload)
        assert first.status_code == repeated.status_code == 200
        assert first.json()["status"] == repeated.json()["status"] == "approved"


def test_expired_cancel_preserves_partial_but_excludes_it_from_context(
    tmp_path: Path,
) -> None:
    settings = settings_for_database(tmp_path)
    database = open_database(settings)
    conversations = ConversationRepository(database)
    turns = AgentTurnRepository(database)

    async def exercise() -> None:
        thread = await conversations.create_thread("account:1", "SCHOOL", "1")
        registration = await turns.register(
            client_turn_id="cancelled-client-turn",
            requested_thread_id=thread.thread_id,
            owner_id="account:1",
            scope_type="SCHOOL",
            scope_id="1",
            task_type="CHAT",
            request_hash="d" * 64,
            request_summary={
                "message": "请生成一个很长的回答",
                "taskType": "CHAT",
                "intent": "RESOURCE_EXPLANATION",
            },
            lease_owner="crashed-instance",
            lease_seconds=0,
        )
        await turns.update_partial(
            registration.turn.turn_id,
            "crashed-instance",
            "这是已经持久化的部分回答",
        )
        cancelled = await turns.request_cancel(
            "cancelled-client-turn", "account:1", "SCHOOL", "1"
        )
        assert cancelled.status == "cancelled"
        assert cancelled.retryable is False

        visible = await conversations.list_messages(thread.thread_id)
        assert [item["content"] for item in visible] == [
            "请生成一个很长的回答",
            "这是已经持久化的部分回答",
        ]
        assert all(item["metadata"]["incomplete"] for item in visible)
        assert await conversations.list_context_messages(thread.thread_id) == []

        recovered = await turns.get(
            "cancelled-client-turn",
            owner_id="account:1",
            scope_type="SCHOOL",
            scope_id="1",
        )
        assert recovered is not None
        assert recovered.partial_answer == "这是已经持久化的部分回答"

    run_async(exercise())


def test_active_turn_cancellation_stops_task_and_persists_incomplete_question(
    tmp_path: Path,
) -> None:
    settings = settings_for_database(
        tmp_path,
        agent_turn_heartbeat_seconds=1,
        agent_turn_lease_seconds=15,
    )
    database = open_database(settings)
    conversations = ConversationRepository(database)
    turns = AgentTurnRepository(database)
    runtime = AgentRuntime(
        settings,
        conversations,
        turn_repository=turns,
    )
    entered_model = asyncio.Event()

    class SlowAgent:
        async def ainvoke(self, _input, config=None):
            entered_model.set()
            await asyncio.Future()

    runtime._agent = SlowAgent()
    request = AgentMessageRequest.model_validate(
        {
            "ownerId": "account:1",
            "scopeType": "SCHOOL",
            "scopeId": 1,
            "clientTurnId": "active-cancel-turn",
            "message": "请生成一个可以主动停止的回答",
            "context": {},
        }
    )

    async def exercise() -> None:
        task = asyncio.create_task(runtime.handle(request))
        await asyncio.wait_for(entered_model.wait(), timeout=5)
        requested = await runtime.cancel_turn(
            request.client_turn_id,
            request.owner_id,
            request.scope_type,
            request.scope_id,
        )
        assert requested.status == "running"
        assert requested.cancel_requested_at is not None

        with pytest.raises(asyncio.CancelledError):
            await task

        persisted = await turns.get(
            request.client_turn_id,
            owner_id=request.owner_id,
            scope_type=request.scope_type,
            scope_id=request.scope_id,
        )
        assert persisted is not None
        assert persisted.status == "cancelled"
        assert persisted.retryable is False
        messages = await conversations.list_messages(persisted.thread_id)
        assert [item["content"] for item in messages] == [request.message]
        assert messages[0]["metadata"]["incomplete"] is True
        assert await conversations.list_context_messages(persisted.thread_id) == []

    run_async(exercise())


def test_persisted_tool_result_is_reused_without_duplicate_execution(
    tmp_path: Path,
) -> None:
    settings = settings_for_database(tmp_path)
    database = open_database(settings)
    conversations = ConversationRepository(database)
    turns = AgentTurnRepository(database)

    async def exercise() -> None:
        thread = await conversations.create_thread("account:1", "SCHOOL", "1")
        registration = await turns.register(
            client_turn_id="tool-idempotency-turn",
            requested_thread_id=thread.thread_id,
            owner_id="account:1",
            scope_type="SCHOOL",
            scope_id="1",
            task_type="CHAT",
            request_hash="f" * 64,
            request_summary={"message": "执行只读工具", "taskType": "CHAT"},
            lease_owner="tool-instance",
            lease_seconds=30,
        )
        calls = 0

        async def callback() -> dict[str, str]:
            nonlocal calls
            calls += 1
            return {"result": "只执行一次"}

        def runtime() -> ToolRuntimeContext:
            return ToolRuntimeContext(
                thread_id=thread.thread_id,
                turn_id=registration.turn.turn_id,
                call_namespace="chat-v1/model-attempt-1",
                trusted_context=TrustedContext(),
                repository=conversations,
                output_character_limit=1000,
            )

        first = await runtime().run(
            "search_approved_resources", {"query": "纪念馆"}, callback
        )
        resumed = await runtime().run(
            "search_approved_resources", {"query": "纪念馆"}, callback
        )
        audits = await conversations.list_tool_audits(limit=10)

        assert first == resumed
        assert calls == 1
        assert len(audits) == 1
        assert audits[0]["turnId"] == registration.turn.turn_id
        assert audits[0]["toolCallId"].startswith("tool-")

        retry_calls = 0

        async def fail_then_succeed() -> dict[str, str]:
            nonlocal retry_calls
            retry_calls += 1
            if retry_calls == 1:
                raise RuntimeError("simulated transient tool failure")
            return {"result": "重试成功"}

        failed = await runtime().run(
            "query_graph_relations", {"query": "人物关系"}, fail_then_succeed
        )
        succeeded = await runtime().run(
            "query_graph_relations", {"query": "人物关系"}, fail_then_succeed
        )
        refreshed_audits = await conversations.list_tool_audits(limit=10)

        assert "RuntimeError" in failed
        assert "重试成功" in succeeded
        assert retry_calls == 2
        assert len(refreshed_audits) == 2
        retried_audit = next(
            item
            for item in refreshed_audits
            if item["toolName"] == "query_graph_relations"
        )
        assert retried_audit["status"] == "completed"

    run_async(exercise())


def test_checkpointer_setup_resume_completed_state_and_cleanup(
    tmp_path: Path,
) -> None:
    settings = settings_for_database(tmp_path)
    database = open_database(settings)
    checkpoints = CheckpointManager(database)
    conversations = ConversationRepository(database)
    turns = AgentTurnRepository(database)

    class GraphState(TypedDict):
        value: int

    async def exercise() -> None:
        assert await checkpoints.setup() == checkpoints.latest_version
        assert await checkpoints.setup() == checkpoints.latest_version

        thread = await conversations.create_thread("account:1", "SCHOOL", "1")
        registration = await turns.register(
            client_turn_id="checkpoint-client-turn",
            requested_thread_id=thread.thread_id,
            owner_id="account:1",
            scope_type="SCHOOL",
            scope_id="1",
            task_type="CHAT",
            request_hash="e" * 64,
            request_summary={"message": "执行可恢复图", "taskType": "CHAT"},
            lease_owner="checkpoint-instance",
            lease_seconds=30,
        )
        calls = {"tool": 0, "model": 0}

        async def tool_node(state: GraphState) -> GraphState:
            calls["tool"] += 1
            return {"value": state["value"] + 1}

        async def model_node(state: GraphState) -> GraphState:
            calls["model"] += 1
            if calls["model"] == 1:
                raise RuntimeError("simulated model interruption")
            return {"value": state["value"] + 10}

        builder = StateGraph(GraphState)
        builder.add_node("tool", tool_node)
        builder.add_node("model", model_node)
        builder.add_edge(START, "tool")
        builder.add_edge("tool", "model")
        builder.add_edge("model", END)
        graph = builder.compile(
            checkpointer=checkpoints.scoped_saver(
                "chat-v1/model-attempt-1"
            )
        )
        config = checkpoints.graph_config(registration.turn.turn_id)

        with pytest.raises(RuntimeError, match="simulated model interruption"):
            await graph.ainvoke({"value": 1}, config=config)
        resumed = await graph.ainvoke(None, config=config)
        assert resumed == {"value": 12}
        assert calls == {"tool": 1, "model": 2}

        completed_again = await graph.ainvoke(None, config=config)
        assert completed_again == {"value": 12}
        assert calls == {"tool": 1, "model": 2}
        assert await checkpoints.has_checkpoint(
            registration.turn.turn_id, "chat-v1/model-attempt-1"
        )

        await turns.complete(
            turn_id=registration.turn.turn_id,
            lease_owner="checkpoint-instance",
            user_content="执行可恢复图",
            user_metadata={"clientTurnId": "checkpoint-client-turn"},
            assistant_content="完成",
            assistant_metadata={"clientTurnId": "checkpoint-client-turn"},
            response={
                "threadId": thread.thread_id,
                "clientTurnId": "checkpoint-client-turn",
                "taskType": "CHAT",
                "answer": "完成",
                "status": "completed",
            },
        )
        async with database.transaction() as connection:
            await connection.execute(
                """
                UPDATE agent_turn
                SET finished_at = CURRENT_TIMESTAMP - INTERVAL '8 days'
                WHERE turn_id = %s
                """,
                (registration.turn.turn_id,),
            )
        claimed = await turns.claim_checkpoint_cleanup(7, 10)
        assert claimed == [registration.turn.turn_id]
        await checkpoints.delete_thread(registration.turn.turn_id)
        await turns.finish_checkpoint_cleanup(
            registration.turn.turn_id, deleted=True
        )
        assert not await checkpoints.has_checkpoint(
            registration.turn.turn_id, "chat-v1/model-attempt-1"
        )

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
