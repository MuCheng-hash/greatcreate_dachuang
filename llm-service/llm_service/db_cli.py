from __future__ import annotations

import argparse
import asyncio
import json
import os
import secrets
import sys
from pathlib import Path
from urllib.parse import quote

from pydantic import ValidationError

from .database import Database, SchemaMigrationError, SchemaMigrator
from .settings import Settings, load_settings
from .sqlite_import import SqliteImportError, SqliteImporter


SERVICE_ROOT = Path(__file__).resolve().parent.parent


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m llm_service.db_cli",
        description="PostgreSQL schema and one-time SQLite migration tools",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("migrate", help="apply and verify schema migrations")
    importer = commands.add_parser(
        "import-sqlite", help="inspect or import the legacy SQLite database"
    )
    importer.add_argument("--source", required=True, type=Path)
    mode = importer.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--apply", action="store_true")
    local = commands.add_parser(
        "init-local-env", help="create ignored local PostgreSQL credentials"
    )
    local.add_argument("--port", type=int, default=5433)
    return parser


async def _migrate(settings: Settings) -> dict[str, object]:
    database = Database(settings)
    migrator = SchemaMigrator(database, settings.migration_dsn)
    await database.open()
    try:
        version = await migrator.migrate()
        return {"status": "current", "schemaVersion": version}
    finally:
        await database.close()


async def _import(
    settings: Settings, source: Path, apply: bool
) -> dict[str, object]:
    database = Database(settings)
    migrator = SchemaMigrator(database, settings.migration_dsn)
    await database.open()
    try:
        importer = SqliteImporter(database, migrator)
        return (
            await importer.apply(source)
            if apply
            else await importer.dry_run(source)
        )
    finally:
        await database.close()


def _init_local_env(port: int) -> dict[str, object]:
    if not 1 <= port <= 65535:
        raise ValueError("port must be between 1 and 65535")
    path = SERVICE_ROOT / ".env.local"
    if path.exists():
        return {"status": "unchanged", "path": str(path)}
    user = "red_culture_agent"
    database = "red_culture_agent"
    password = secrets.token_urlsafe(32)
    dsn = (
        f"postgresql://{quote(user)}:{quote(password)}@127.0.0.1:{port}/"
        f"{quote(database)}"
    )
    content = "\n".join(
        (
            f"POSTGRES_USER={user}",
            f"POSTGRES_PASSWORD={password}",
            f"POSTGRES_DB={database}",
            f"POSTGRES_PORT={port}",
            f"DATABASE_URL={dsn}",
            "",
        )
    )
    temporary = path.with_name(f".{path.name}.{secrets.token_hex(6)}.tmp")
    try:
        temporary.write_text(content, encoding="utf-8", newline="\n")
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()
    return {"status": "created", "path": str(path)}


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "init-local-env":
            result = _init_local_env(args.port)
        else:
            settings = load_settings()
            if args.command == "migrate":
                result = asyncio.run(_migrate(settings))
            else:
                result = asyncio.run(
                    _import(settings, args.source, bool(args.apply))
                )
        print(json.dumps(result, ensure_ascii=False, indent=2, default=str))
        return 0
    except ValidationError:
        print(
            json.dumps(
                {"status": "failed", "error": "configuration validation failed"},
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 2
    except (SqliteImportError, SchemaMigrationError, ValueError) as exc:
        print(
            json.dumps(
                {"status": "failed", "error": str(exc)}, ensure_ascii=False
            ),
            file=sys.stderr,
        )
        return 2
    except Exception as exc:
        print(
            json.dumps(
                {
                    "status": "failed",
                    "error": f"{type(exc).__name__}: database operation failed",
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
