from __future__ import annotations

import hashlib
import json
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from psycopg import AsyncConnection, sql
from psycopg.types.json import Jsonb

from .database import Database, SchemaMigrator


class SqliteImportError(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class TableSpec:
    name: str
    columns: tuple[str, ...]
    primary_key: tuple[str, ...]
    json_columns: tuple[str, ...] = ()
    boolean_columns: tuple[str, ...] = ()
    timestamp_columns: tuple[str, ...] = ()
    identity_column: str | None = None


TABLE_SPECS: tuple[TableSpec, ...] = (
    TableSpec(
        "agent_thread",
        (
            "thread_id",
            "owner_id",
            "scope_type",
            "scope_id",
            "status",
            "summary",
            "created_at",
            "updated_at",
        ),
        ("thread_id",),
        timestamp_columns=("created_at", "updated_at"),
    ),
    TableSpec(
        "agent_message",
        ("id", "thread_id", "role", "content", "metadata_json", "created_at"),
        ("id",),
        json_columns=("metadata_json",),
        timestamp_columns=("created_at",),
        identity_column="id",
    ),
    TableSpec(
        "agent_tool_audit",
        (
            "id",
            "thread_id",
            "tool_name",
            "arguments_json",
            "status",
            "duration_ms",
            "result_preview",
            "created_at",
        ),
        ("id",),
        json_columns=("arguments_json",),
        timestamp_columns=("created_at",),
        identity_column="id",
    ),
    TableSpec(
        "agent_memory_setting",
        (
            "owner_id",
            "scope_type",
            "scope_id",
            "enabled",
            "created_at",
            "updated_at",
        ),
        ("owner_id", "scope_type", "scope_id"),
        boolean_columns=("enabled",),
        timestamp_columns=("created_at", "updated_at"),
    ),
    TableSpec(
        "agent_memory",
        (
            "id",
            "owner_id",
            "scope_type",
            "scope_id",
            "memory_type",
            "field_key",
            "content",
            "status",
            "source",
            "source_thread_id",
            "confidence",
            "expires_at",
            "deleted_at",
            "purge_after",
            "created_at",
            "updated_at",
        ),
        ("id",),
        timestamp_columns=(
            "expires_at",
            "deleted_at",
            "purge_after",
            "created_at",
            "updated_at",
        ),
    ),
    TableSpec(
        "agent_memory_audit",
        (
            "id",
            "memory_id",
            "owner_id",
            "scope_type",
            "scope_id",
            "event_type",
            "metadata_json",
            "created_at",
        ),
        ("id",),
        json_columns=("metadata_json",),
        timestamp_columns=("created_at",),
        identity_column="id",
    ),
    TableSpec(
        "prompt_version",
        (
            "prompt_key",
            "version",
            "content",
            "active",
            "created_by",
            "notes",
            "created_at",
        ),
        ("prompt_key", "version"),
        boolean_columns=("active",),
        timestamp_columns=("created_at",),
    ),
    TableSpec(
        "prompt_experiment",
        ("prompt_key", "experiment_key", "variants_json", "active", "updated_at"),
        ("prompt_key",),
        json_columns=("variants_json",),
        boolean_columns=("active",),
        timestamp_columns=("updated_at",),
    ),
    TableSpec(
        "prompt_run",
        (
            "run_id",
            "prompt_key",
            "version",
            "experiment_key",
            "variant",
            "subject_key",
            "model",
            "status",
            "latency_ms",
            "input_characters",
            "output_characters",
            "quality_score",
            "feedback",
            "error_message",
            "created_at",
            "completed_at",
        ),
        ("run_id",),
        timestamp_columns=("created_at", "completed_at"),
    ),
    TableSpec(
        "llm_trace",
        (
            "call_id",
            "trace_id",
            "span_id",
            "parent_span_id",
            "user_id",
            "session_id",
            "feature",
            "provider",
            "model",
            "status",
            "error_type",
            "error_message",
            "valid_json",
            "input_tokens",
            "output_tokens",
            "total_tokens",
            "token_source",
            "cost_usd",
            "latency_ms",
            "first_token_latency_ms",
            "metadata_json",
            "started_at",
            "completed_at",
        ),
        ("call_id",),
        json_columns=("metadata_json",),
        boolean_columns=("valid_json",),
        timestamp_columns=("started_at", "completed_at"),
    ),
)


@dataclass(slots=True)
class SqliteSnapshot:
    source_path: Path
    source_sha256: str
    rows: dict[str, list[dict[str, Any]]]
    row_counts: dict[str, int]
    primary_keys: dict[str, set[tuple[Any, ...]]]


class SqliteImporter:
    def __init__(self, database: Database, migrator: SchemaMigrator):
        self.database = database
        self.migrator = migrator

    async def dry_run(self, source: Path | str) -> dict[str, Any]:
        snapshot = self._inspect(Path(source))
        await self.migrator.validate()
        target_counts = await self._target_counts()
        migration = await self._migration_record(snapshot.source_sha256)
        return {
            "mode": "dry-run",
            "source": str(snapshot.source_path),
            "sourceSha256": snapshot.source_sha256,
            "schemaVersion": self.migrator.latest_version,
            "tables": list(snapshot.row_counts),
            "sourceRowCounts": snapshot.row_counts,
            "targetRowCounts": target_counts,
            "alreadyImported": migration is not None,
            "readyToApply": migration is not None
            or all(count == 0 for count in target_counts.values()),
            "conversions": {
                "timestamps": "TEXT -> TIMESTAMPTZ",
                "booleans": "INTEGER -> BOOLEAN",
                "json": "TEXT -> JSONB",
                "identity": "preserve explicit BIGINT ids and reset sequences",
            },
        }

    async def apply(self, source: Path | str) -> dict[str, Any]:
        source_path = Path(source).resolve()
        snapshot = self._inspect(source_path)
        await self.migrator.validate()
        async with self.database.transaction() as connection:
            await connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                ("red-culture-agent-sqlite-import-v1",),
            )
            existing = await self._migration_record(
                snapshot.source_sha256, connection=connection
            )
            if existing is not None:
                verification = await self._verify_target(
                    connection, snapshot, allow_additional_rows=True
                )
                return {
                    "mode": "apply",
                    "status": "already-imported",
                    "source": str(source_path),
                    "sourceSha256": snapshot.source_sha256,
                    "rowCounts": snapshot.row_counts,
                    "verification": verification,
                }
        backup_path = self._backup(source_path)
        backup_snapshot = self._inspect(backup_path)
        if backup_snapshot.row_counts != snapshot.row_counts:
            raise SqliteImportError("SQLite backup verification failed")
        verification: dict[str, Any]
        async with self.database.transaction() as connection:
            await connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                ("red-culture-agent-sqlite-import-v1",),
            )
            existing = await self._migration_record(
                snapshot.source_sha256, connection=connection
            )
            if existing is not None:
                verification = await self._verify_target(
                    connection, snapshot, allow_additional_rows=True
                )
                return {
                    "mode": "apply",
                    "status": "already-imported",
                    "source": str(source_path),
                    "sourceSha256": snapshot.source_sha256,
                    "rowCounts": snapshot.row_counts,
                    "verification": verification,
                }
            target_counts = await self._target_counts(connection)
            nonempty = {
                table: count for table, count in target_counts.items() if count
            }
            if nonempty:
                raise SqliteImportError(
                    "PostgreSQL target tables must be empty before first import"
                )
            await self._insert_all(connection, backup_snapshot)
            await self._reset_sequences(connection)
            verification = await self._verify_target(connection, snapshot)
            await connection.execute(
                """
                INSERT INTO data_migration(
                    source_type, source_sha256, source_path, backup_path,
                    status, row_counts_json, verification_json
                ) VALUES ('sqlite', %s, %s, %s, 'completed', %s, %s)
                """,
                (
                    snapshot.source_sha256,
                    str(source_path),
                    str(backup_path),
                    Jsonb(snapshot.row_counts),
                    Jsonb(verification),
                ),
            )
        return {
            "mode": "apply",
            "status": "completed",
            "source": str(source_path),
            "backup": str(backup_path),
            "sourceSha256": snapshot.source_sha256,
            "rowCounts": snapshot.row_counts,
            "verification": verification,
        }

    @staticmethod
    def _readonly_connection(path: Path) -> sqlite3.Connection:
        if not path.is_file():
            raise SqliteImportError(f"SQLite source not found: {path}")
        connection = sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only = ON")
        return connection

    def _inspect(self, path: Path) -> SqliteSnapshot:
        resolved = path.resolve()
        rows_by_table: dict[str, list[dict[str, Any]]] = {}
        primary_keys: dict[str, set[tuple[Any, ...]]] = {}
        with self._readonly_connection(resolved) as connection:
            available = {
                str(row["name"])
                for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table'"
                ).fetchall()
            }
            missing = [spec.name for spec in TABLE_SPECS if spec.name not in available]
            if missing:
                raise SqliteImportError(
                    "SQLite source is missing required tables: " + ", ".join(missing)
                )
            foreign_key_errors = connection.execute(
                "PRAGMA foreign_key_check"
            ).fetchall()
            if foreign_key_errors:
                raise SqliteImportError("SQLite source contains foreign-key orphans")
            for spec in TABLE_SPECS:
                actual_columns = {
                    str(row["name"])
                    for row in connection.execute(
                        f'PRAGMA table_info("{spec.name}")'
                    ).fetchall()
                }
                missing_columns = [
                    column for column in spec.columns if column not in actual_columns
                ]
                if missing_columns:
                    raise SqliteImportError(
                        f"SQLite table {spec.name} is missing columns: "
                        + ", ".join(missing_columns)
                    )
                selected = ", ".join(f'"{column}"' for column in spec.columns)
                rows = [
                    dict(row)
                    for row in connection.execute(
                        f'SELECT {selected} FROM "{spec.name}"'
                    ).fetchall()
                ]
                self._validate_rows(spec, rows)
                rows_by_table[spec.name] = rows
                primary_keys[spec.name] = {
                    tuple(row[column] for column in spec.primary_key) for row in rows
                }
            active_duplicates = connection.execute(
                """
                SELECT prompt_key FROM prompt_version
                WHERE active = 1 GROUP BY prompt_key HAVING COUNT(*) > 1
                """
            ).fetchall()
            if active_duplicates:
                raise SqliteImportError(
                    "SQLite source contains multiple active Prompt versions"
                )
        return SqliteSnapshot(
            source_path=resolved,
            source_sha256=self._sha256(resolved),
            rows=rows_by_table,
            row_counts={name: len(rows) for name, rows in rows_by_table.items()},
            primary_keys=primary_keys,
        )

    @classmethod
    def _validate_rows(
        cls, spec: TableSpec, rows: list[dict[str, Any]]
    ) -> None:
        for index, row in enumerate(rows, start=1):
            for column in spec.json_columns:
                value = row[column]
                try:
                    parsed = json.loads(value or "{}")
                except (TypeError, ValueError, json.JSONDecodeError) as exc:
                    raise SqliteImportError(
                        f"invalid JSON in {spec.name}.{column} row {index}"
                    ) from exc
                if not isinstance(parsed, (dict, list)):
                    raise SqliteImportError(
                        f"invalid JSON shape in {spec.name}.{column} row {index}"
                    )
            for column in spec.boolean_columns:
                value = row[column]
                if value is not None and value not in (0, 1, False, True):
                    raise SqliteImportError(
                        f"invalid boolean in {spec.name}.{column} row {index}"
                    )
            for column in spec.timestamp_columns:
                value = row[column]
                if value is not None:
                    cls._timestamp(value, spec.name, column, index)

    @staticmethod
    def _timestamp(
        value: Any, table: str, column: str, row_index: int
    ) -> datetime:
        try:
            parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        except ValueError as exc:
            raise SqliteImportError(
                f"invalid timestamp in {table}.{column} row {row_index}"
            ) from exc
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)

    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    def _backup(self, source: Path) -> Path:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        backup = source.with_name(f"{source.stem}.backup-{timestamp}{source.suffix}")
        source_connection = self._readonly_connection(source)
        destination = sqlite3.connect(backup)
        try:
            source_connection.backup(destination)
            destination.commit()
        finally:
            destination.close()
            source_connection.close()
        return backup

    async def _insert_all(
        self,
        connection: AsyncConnection[dict[str, Any]],
        snapshot: SqliteSnapshot,
    ) -> None:
        for spec in TABLE_SPECS:
            rows = snapshot.rows[spec.name]
            if not rows:
                continue
            query = sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
                sql.Identifier(spec.name),
                sql.SQL(", ").join(sql.Identifier(column) for column in spec.columns),
                sql.SQL(", ").join(sql.Placeholder() for _ in spec.columns),
            )
            values = [self._converted_values(spec, row, index) for index, row in enumerate(rows, 1)]
            async with connection.cursor() as cursor:
                await cursor.executemany(query, values)

    def _converted_values(
        self, spec: TableSpec, row: dict[str, Any], row_index: int
    ) -> tuple[Any, ...]:
        converted: list[Any] = []
        for column in spec.columns:
            value = row[column]
            if column in spec.json_columns:
                value = Jsonb(json.loads(value or "{}"))
            elif column in spec.boolean_columns:
                value = None if value is None else bool(value)
            elif column in spec.timestamp_columns and value is not None:
                value = self._timestamp(value, spec.name, column, row_index)
            converted.append(value)
        return tuple(converted)

    async def _reset_sequences(
        self, connection: AsyncConnection[dict[str, Any]]
    ) -> None:
        for spec in TABLE_SPECS:
            if spec.identity_column is None:
                continue
            sequence = await (
                await connection.execute(
                    "SELECT pg_get_serial_sequence(%s, %s) AS sequence_name",
                    (spec.name, spec.identity_column),
                )
            ).fetchone()
            sequence_name = str((sequence or {}).get("sequence_name") or "")
            if not sequence_name:
                raise SqliteImportError(
                    f"identity sequence not found for {spec.name}"
                )
            row = await (
                await connection.execute(
                    sql.SQL("SELECT COALESCE(MAX({}), 0) + 1 AS next_id FROM {}").format(
                        sql.Identifier(spec.identity_column), sql.Identifier(spec.name)
                    )
                )
            ).fetchone()
            await connection.execute(
                "SELECT setval(%s::regclass, %s, FALSE)",
                (sequence_name, max(1, int((row or {}).get("next_id") or 1))),
            )

    async def _verify_target(
        self,
        connection: AsyncConnection[dict[str, Any]],
        snapshot: SqliteSnapshot,
        *,
        allow_additional_rows: bool = False,
    ) -> dict[str, Any]:
        counts = await self._target_counts(connection)
        counts_match = (
            all(
                counts[table] >= source_count
                for table, source_count in snapshot.row_counts.items()
            )
            if allow_additional_rows
            else counts == snapshot.row_counts
        )
        if not counts_match:
            raise SqliteImportError("PostgreSQL row-count verification failed")
        primary_key_digests: dict[str, str] = {}
        for spec in TABLE_SPECS:
            columns = sql.SQL(", ").join(
                sql.Identifier(column) for column in spec.primary_key
            )
            rows = await (
                await connection.execute(
                    sql.SQL("SELECT {} FROM {}").format(
                        columns, sql.Identifier(spec.name)
                    )
                )
            ).fetchall()
            target_keys = {
                tuple(row[column] for column in spec.primary_key) for row in rows
            }
            source_keys = snapshot.primary_keys[spec.name]
            keys_match = (
                source_keys.issubset(target_keys)
                if allow_additional_rows
                else target_keys == source_keys
            )
            if not keys_match:
                raise SqliteImportError(
                    f"PostgreSQL primary-key verification failed for {spec.name}"
                )
            primary_key_digests[spec.name] = self._key_digest(source_keys)
        orphan_row = await (
            await connection.execute(
                """
                SELECT
                    (SELECT COUNT(*) FROM agent_message m
                     LEFT JOIN agent_thread t ON t.thread_id = m.thread_id
                     WHERE t.thread_id IS NULL)
                  + (SELECT COUNT(*) FROM agent_tool_audit a
                     LEFT JOIN agent_thread t ON t.thread_id = a.thread_id
                     WHERE t.thread_id IS NULL) AS orphan_count
                """
            )
        ).fetchone()
        orphan_count = int((orphan_row or {}).get("orphan_count") or 0)
        if orphan_count:
            raise SqliteImportError("PostgreSQL foreign-key verification failed")
        active_duplicates = await (
            await connection.execute(
                """
                SELECT prompt_key FROM prompt_version WHERE active
                GROUP BY prompt_key HAVING COUNT(*) > 1
                """
            )
        ).fetchall()
        if active_duplicates:
            raise SqliteImportError(
                "PostgreSQL active Prompt uniqueness verification failed"
            )
        sequences: dict[str, int] = {}
        for spec in TABLE_SPECS:
            if spec.identity_column is None:
                continue
            sequence = await (
                await connection.execute(
                    "SELECT pg_get_serial_sequence(%s, %s) AS sequence_name",
                    (spec.name, spec.identity_column),
                )
            ).fetchone()
            sequence_name = str((sequence or {}).get("sequence_name") or "")
            sequence_parts = sequence_name.split(".", 1)
            sequence_row = await (
                await connection.execute(
                    sql.SQL("SELECT last_value, is_called FROM {}").format(
                        sql.Identifier(*sequence_parts)
                    )
                )
            ).fetchone()
            max_row = await (
                await connection.execute(
                    sql.SQL("SELECT COALESCE(MAX({}), 0) + 1 AS next_id FROM {}").format(
                        sql.Identifier(spec.identity_column), sql.Identifier(spec.name)
                    )
                )
            ).fetchone()
            expected_next = max(1, int((max_row or {}).get("next_id") or 1))
            last_value = int((sequence_row or {}).get("last_value") or 0)
            is_called = bool((sequence_row or {}).get("is_called"))
            actual_next = last_value + 1 if is_called else last_value
            sequence_valid = (
                actual_next >= expected_next
                if allow_additional_rows
                else actual_next == expected_next and not is_called
            )
            if not sequence_valid:
                raise SqliteImportError(
                    f"PostgreSQL identity sequence verification failed for {spec.name}"
                )
            sequences[spec.name] = actual_next
        return {
            "rowCounts": counts,
            "sourceRowCounts": snapshot.row_counts,
            "primaryKeyDigests": primary_key_digests,
            "sourcePrimaryKeysPresent": True,
            "foreignKeyOrphans": orphan_count,
            "activePromptUnique": True,
            "jsonbValidatedByDatabase": True,
            "nextIdentityValues": sequences,
        }

    async def _target_counts(
        self, connection: AsyncConnection[dict[str, Any]] | None = None
    ) -> dict[str, int]:
        if connection is None:
            async with self.database.connection() as acquired:
                return await self._target_counts(acquired)
        result: dict[str, int] = {}
        for spec in TABLE_SPECS:
            row = await (
                await connection.execute(
                    sql.SQL("SELECT COUNT(*) AS count FROM {}").format(
                        sql.Identifier(spec.name)
                    )
                )
            ).fetchone()
            result[spec.name] = int((row or {}).get("count") or 0)
        return result

    async def _migration_record(
        self,
        source_sha256: str,
        *,
        connection: AsyncConnection[dict[str, Any]] | None = None,
    ) -> dict[str, Any] | None:
        if connection is None:
            async with self.database.connection() as acquired:
                return await self._migration_record(
                    source_sha256, connection=acquired
                )
        return await (
            await connection.execute(
                """
                SELECT source_sha256, row_counts_json, verification_json
                FROM data_migration WHERE source_sha256 = %s
                """,
                (source_sha256,),
            )
        ).fetchone()

    @staticmethod
    def _key_digest(values: set[tuple[Any, ...]]) -> str:
        serialized = json.dumps(
            sorted(
                ([str(item) for item in value] for value in values),
                key=lambda item: tuple(item),
            ),
            ensure_ascii=False,
            separators=(",", ":"),
        )
        return hashlib.sha256(serialized.encode("utf-8")).hexdigest()
