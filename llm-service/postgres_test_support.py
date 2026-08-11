from __future__ import annotations

import asyncio
import hashlib
import inspect
import threading
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import psycopg
from psycopg import sql

from llm_service.database import Database, SchemaMigrator
from llm_service.container import AppContainer, build_container
from llm_service.observability import LlmObservability
from llm_service.prompt_manager import PromptManager
from llm_service.repository import ConversationRepository
from llm_service.settings import Settings, load_settings
from llm_service.user_memory import MemoryRepository


TEST_DATABASE_NAME = "red_culture_agent_test"
_schemas: set[str] = set()
_databases: list[Database] = []
_containers: list[AppContainer] = []
_database_lock = threading.Lock()


class AsyncTestRunner:
    def __init__(self) -> None:
        self.loop: asyncio.AbstractEventLoop | None = None
        self._ready = threading.Event()
        self._thread = threading.Thread(
            target=self._serve, name="postgres-test-loop", daemon=True
        )
        self._thread.start()
        self._ready.wait(timeout=10)
        if self.loop is None:
            raise RuntimeError("test event loop failed to start")

    def _serve(self) -> None:
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        self._ready.set()
        self.loop.run_forever()
        self.loop.close()

    def run(self, awaitable):
        if self.loop is None:
            raise RuntimeError("test event loop is closed")
        return asyncio.run_coroutine_threadsafe(awaitable, self.loop).result(
            timeout=60
        )

    def close(self) -> None:
        if self.loop is not None:
            self.loop.call_soon_threadsafe(self.loop.stop)
            self._thread.join(timeout=10)
            self.loop = None


RUNNER = AsyncTestRunner()


def run_async(awaitable):
    return RUNNER.run(awaitable)


class SyncAsyncProxy:
    def __init__(self, target: Any, database: Database):
        self.async_target = target
        self.database = database

    def __getattr__(self, name: str) -> Any:
        value = getattr(self.async_target, name)
        if inspect.iscoroutinefunction(value):
            return lambda *args, **kwargs: RUNNER.run(value(*args, **kwargs))
        return value


def _replace_database(url: str, database_name: str) -> str:
    parts = urlsplit(url)
    return urlunsplit(
        (parts.scheme, parts.netloc, f"/{database_name}", parts.query, parts.fragment)
    )


def _schema_url(url: str, schema: str) -> str:
    parts = urlsplit(url)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    query["options"] = f"-csearch_path={schema}"
    return urlunsplit(
        (parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment)
    )


def database_url_for_test(tmp_path: Path) -> str:
    base_url = load_settings().database_dsn
    admin_url = _replace_database(base_url, "postgres")
    test_url = _replace_database(base_url, TEST_DATABASE_NAME)
    with _database_lock:
        with psycopg.connect(admin_url, autocommit=True) as connection:
            exists = connection.execute(
                "SELECT 1 FROM pg_database WHERE datname = %s",
                (TEST_DATABASE_NAME,),
            ).fetchone()
            if exists is None:
                connection.execute(
                    sql.SQL("CREATE DATABASE {}").format(
                        sql.Identifier(TEST_DATABASE_NAME)
                    )
                )
        schema = "t_" + hashlib.sha256(
            str(tmp_path.resolve()).encode("utf-8")
        ).hexdigest()[:20]
        with psycopg.connect(test_url, autocommit=True) as connection:
            connection.execute(
                sql.SQL("CREATE SCHEMA IF NOT EXISTS {}").format(
                    sql.Identifier(schema)
                )
            )
        _schemas.add(schema)
    return _schema_url(test_url, schema)


def settings_for_database(tmp_path: Path, **values: Any) -> Settings:
    return Settings(
        _env_file=None,
        database_url=database_url_for_test(tmp_path),
        **values,
    )


def open_database(settings: Settings) -> Database:
    database = open_unmigrated_database(settings)
    RUNNER.run(SchemaMigrator(database).migrate())
    return database


def open_unmigrated_database(settings: Settings) -> Database:
    database = Database(settings)
    RUNNER.run(database.open())
    _databases.append(database)
    return database


def conversation_repository(settings: Settings) -> SyncAsyncProxy:
    database = open_database(settings)
    return SyncAsyncProxy(ConversationRepository(database), database)


def memory_repository(
    settings: Settings, **kwargs: Any
) -> SyncAsyncProxy:
    database = open_database(settings)
    return SyncAsyncProxy(MemoryRepository(database, **kwargs), database)


def prompt_manager(
    settings: Settings, prompt_root: Path, agent_prompt_version: str = "v1"
) -> SyncAsyncProxy:
    database = open_database(settings)
    manager = PromptManager(database, prompt_root, agent_prompt_version)
    RUNNER.run(manager.initialize())
    return SyncAsyncProxy(manager, database)


def observability_store(
    settings: Settings,
    model_pricing: dict[str, dict[str, float]] | None = None,
) -> SyncAsyncProxy:
    database = open_database(settings)
    return SyncAsyncProxy(LlmObservability(database, model_pricing), database)


def started_runtime(settings: Settings):
    container = build_container(settings)
    RUNNER.run(container.database.open())
    RUNNER.run(container.migrator.migrate())
    RUNNER.run(container.prompts.initialize())
    RUNNER.run(container.alerts.start())
    _databases.append(container.database)
    _containers.append(container)
    return container.runtime


def cleanup_test_resources() -> None:
    for container in reversed(_containers):
        try:
            RUNNER.run(container.business_tool_client.aclose())
            RUNNER.run(container.alerts.close())
        except Exception:
            pass
    _containers.clear()
    for database in reversed(_databases):
        try:
            RUNNER.run(database.close())
        except Exception:
            pass
    _databases.clear()
    try:
        base_url = load_settings().database_dsn
        test_url = _replace_database(base_url, TEST_DATABASE_NAME)
        with psycopg.connect(test_url, autocommit=True) as connection:
            for schema in sorted(_schemas):
                connection.execute(
                    sql.SQL("DROP SCHEMA IF EXISTS {} CASCADE").format(
                        sql.Identifier(schema)
                    )
                )
    finally:
        _schemas.clear()
        RUNNER.close()
