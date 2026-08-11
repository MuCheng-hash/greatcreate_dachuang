from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from psycopg.errors import UniqueViolation
from psycopg.types.json import Jsonb

from .database import Database


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _serialized_row(row: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value.astimezone(timezone.utc).isoformat()
        if isinstance(value, datetime)
        else value
        for key, value in row.items()
    }


class PromptVersionExistsError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class PromptSelection:
    prompt_key: str
    version: str
    content: str
    experiment_key: str | None = None
    variant: str | None = None


class PromptManager:
    def __init__(
        self,
        database: Database,
        prompt_root: Path | str,
        agent_prompt_version: str = "v1",
    ):
        self.database = database
        self.prompt_root = Path(prompt_root)
        self.agent_prompt_version = agent_prompt_version

    async def initialize(self) -> None:
        await self._seed_file_prompt(
            "agent",
            self.agent_prompt_version,
            self.prompt_root
            / "agent"
            / self.agent_prompt_version
            / "system.md",
        )
        await self._seed_file_prompt(
            "teaching-plan",
            "v1",
            self.prompt_root / "teaching-plan" / "v1" / "system.md",
        )
        await self._seed_file_prompt(
            "resource-discovery",
            "v1",
            self.prompt_root / "resource-discovery" / "v1" / "system.md",
        )

    async def _seed_file_prompt(
        self, prompt_key: str, version: str, path: Path
    ) -> None:
        if not path.is_file():
            raise RuntimeError(f"prompt seed file not found: {path}")
        content = path.read_text(encoding="utf-8").strip()
        async with self.database.transaction() as connection:
            await connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                (f"prompt:{prompt_key}",),
            )
            exists = await (
                await connection.execute(
                    "SELECT 1 FROM prompt_version WHERE prompt_key = %s LIMIT 1",
                    (prompt_key,),
                )
            ).fetchone()
            await connection.execute(
                """
                INSERT INTO prompt_version(
                    prompt_key, version, content, active,
                    created_by, notes, created_at
                ) VALUES (%s, %s, %s, %s, 'system',
                          'seeded from repository', %s)
                ON CONFLICT(prompt_key, version) DO NOTHING
                """,
                (prompt_key, version, content, exists is None, utc_now()),
            )
            await connection.execute(
                """
                UPDATE prompt_version SET content = %s
                WHERE prompt_key = %s AND version = %s
                  AND created_by = 'system'
                  AND notes = 'seeded from repository'
                """,
                (content, prompt_key, version),
            )

    async def create_version(
        self,
        prompt_key: str,
        version: str,
        content: str,
        created_by: str = "admin",
        notes: str = "",
    ) -> dict[str, Any]:
        prompt_key = prompt_key.strip()
        version = version.strip()
        content = content.strip()
        if not prompt_key or not version or not content:
            raise ValueError("promptKey, version and content are required")
        if prompt_key != "agent" and "{{context_json}}" not in content:
            raise ValueError("prompt content must contain {{context_json}}")
        try:
            async with self.database.transaction() as connection:
                await connection.execute(
                    """
                    INSERT INTO prompt_version(
                        prompt_key, version, content, active,
                        created_by, notes, created_at
                    ) VALUES (%s, %s, %s, FALSE, %s, %s, %s)
                    """,
                    (
                        prompt_key,
                        version,
                        content,
                        created_by.strip() or "admin",
                        notes.strip(),
                        utc_now(),
                    ),
                )
        except UniqueViolation as exc:
            raise PromptVersionExistsError("prompt version already exists") from exc
        return await self.get_version(prompt_key, version)

    async def activate_version(
        self, prompt_key: str, version: str
    ) -> dict[str, Any]:
        async with self.database.transaction() as connection:
            await connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                (f"prompt:{prompt_key}",),
            )
            exists = await (
                await connection.execute(
                    """
                    SELECT 1 FROM prompt_version
                    WHERE prompt_key = %s AND version = %s
                    FOR UPDATE
                    """,
                    (prompt_key, version),
                )
            ).fetchone()
            if exists is None:
                raise LookupError("prompt version not found")
            await connection.execute(
                "UPDATE prompt_version SET active = FALSE WHERE prompt_key = %s",
                (prompt_key,),
            )
            await connection.execute(
                """
                UPDATE prompt_version SET active = TRUE
                WHERE prompt_key = %s AND version = %s
                """,
                (prompt_key, version),
            )
        return await self.get_version(prompt_key, version)

    async def list_versions(self, prompt_key: str) -> list[dict[str, Any]]:
        async with self.database.connection() as connection:
            rows = await (
                await connection.execute(
                    """
                    SELECT prompt_key, version, active, created_by, notes, created_at
                    FROM prompt_version WHERE prompt_key = %s
                    ORDER BY created_at DESC
                    """,
                    (prompt_key,),
                )
            ).fetchall()
        return [_serialized_row(dict(row)) for row in rows]

    async def get_version(
        self, prompt_key: str, version: str
    ) -> dict[str, Any]:
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    """
                    SELECT * FROM prompt_version
                    WHERE prompt_key = %s AND version = %s
                    """,
                    (prompt_key, version),
                )
            ).fetchone()
        if row is None:
            raise LookupError("prompt version not found")
        return _serialized_row(dict(row))

    async def active_content(self, prompt_key: str) -> str:
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    """
                    SELECT content FROM prompt_version
                    WHERE prompt_key = %s AND active = TRUE
                    ORDER BY created_at DESC LIMIT 1
                    """,
                    (prompt_key,),
                )
            ).fetchone()
        if row is None:
            raise LookupError("active prompt version not found")
        return str(row["content"])

    async def configure_experiment(
        self,
        prompt_key: str,
        experiment_key: str,
        variants: list[dict[str, Any]],
        active: bool,
    ) -> dict[str, Any]:
        if not experiment_key.strip():
            raise ValueError("experimentKey is required")
        normalized: list[dict[str, Any]] = []
        total_weight = 0
        for item in variants:
            version = str(item.get("version") or "").strip()
            weight = int(item.get("weight") or 0)
            if not version or weight <= 0:
                raise ValueError(
                    "each experiment variant requires a version and positive weight"
                )
            normalized.append({"version": version, "weight": weight})
            total_weight += weight
        if len(normalized) < 2 or total_weight <= 0:
            raise ValueError("an experiment requires at least two weighted variants")
        async with self.database.transaction() as connection:
            rows = await (
                await connection.execute(
                    """
                    SELECT version FROM prompt_version
                    WHERE prompt_key = %s AND version = ANY(%s)
                    """,
                    (prompt_key, [item["version"] for item in normalized]),
                )
            ).fetchall()
            existing = {str(row["version"]) for row in rows}
            if existing != {item["version"] for item in normalized}:
                raise LookupError("prompt version not found")
            await connection.execute(
                """
                INSERT INTO prompt_experiment(
                    prompt_key, experiment_key, variants_json, active, updated_at
                ) VALUES (%s, %s, %s, %s, %s)
                ON CONFLICT(prompt_key) DO UPDATE SET
                    experiment_key = excluded.experiment_key,
                    variants_json = excluded.variants_json,
                    active = excluded.active,
                    updated_at = excluded.updated_at
                """,
                (
                    prompt_key,
                    experiment_key.strip(),
                    Jsonb(normalized),
                    bool(active),
                    utc_now(),
                ),
            )
        return await self.get_experiment(prompt_key)

    async def get_experiment(self, prompt_key: str) -> dict[str, Any]:
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    "SELECT * FROM prompt_experiment WHERE prompt_key = %s",
                    (prompt_key,),
                )
            ).fetchone()
        if row is None:
            return {"prompt_key": prompt_key, "active": False, "variants": []}
        result = _serialized_row(dict(row))
        result["variants"] = list(result.pop("variants_json") or [])
        return result

    async def resolve(
        self, prompt_key: str, subject_key: str, context: dict[str, Any]
    ) -> PromptSelection:
        experiment = await self.get_experiment(prompt_key)
        version: str | None = None
        experiment_key: str | None = None
        variant: str | None = None
        if experiment.get("active") and experiment.get("variants"):
            experiment_key = str(experiment["experiment_key"])
            variants = experiment["variants"]
            total = sum(int(item["weight"]) for item in variants)
            bucket = (
                int(
                    hashlib.sha256(
                        f"{experiment_key}:{subject_key}".encode()
                    ).hexdigest(),
                    16,
                )
                % total
            )
            cursor = 0
            for item in variants:
                cursor += int(item["weight"])
                if bucket < cursor:
                    version = str(item["version"])
                    variant = version
                    break
        if version is None:
            async with self.database.connection() as connection:
                row = await (
                    await connection.execute(
                        """
                        SELECT version FROM prompt_version
                        WHERE prompt_key = %s AND active = TRUE
                        ORDER BY created_at DESC LIMIT 1
                        """,
                        (prompt_key,),
                    )
                ).fetchone()
            if row is None:
                raise LookupError("active prompt version not found")
            version = str(row["version"])
        record = await self.get_version(prompt_key, version)
        rendered = str(record["content"]).replace(
            "{{context_json}}",
            json.dumps(context, ensure_ascii=False, separators=(",", ":")),
        )
        return PromptSelection(
            prompt_key, version, rendered, experiment_key, variant
        )

    async def start_run(
        self,
        selection: PromptSelection,
        subject_key: str,
        model: str,
        input_characters: int,
    ) -> str:
        run_id = str(uuid.uuid4())
        async with self.database.transaction() as connection:
            await connection.execute(
                """
                INSERT INTO prompt_run(
                    run_id, prompt_key, version, experiment_key, variant,
                    subject_key, model, input_characters, created_at
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (
                    run_id,
                    selection.prompt_key,
                    selection.version,
                    selection.experiment_key,
                    selection.variant,
                    subject_key,
                    model,
                    input_characters,
                    utc_now(),
                ),
            )
        return run_id

    async def finish_run(
        self,
        run_id: str,
        status: str,
        latency_ms: int,
        output_characters: int,
        error_message: str = "",
    ) -> None:
        async with self.database.transaction() as connection:
            await connection.execute(
                """
                UPDATE prompt_run SET status = %s, latency_ms = %s,
                    output_characters = %s, error_message = %s,
                    completed_at = %s WHERE run_id = %s
                """,
                (
                    status,
                    latency_ms,
                    output_characters,
                    error_message[:1000],
                    utc_now(),
                    run_id,
                ),
            )

    async def add_feedback(
        self, run_id: str, quality_score: float, feedback: str = ""
    ) -> dict[str, Any]:
        if quality_score < 0 or quality_score > 5:
            raise ValueError("qualityScore must be between 0 and 5")
        async with self.database.transaction() as connection:
            row = await (
                await connection.execute(
                    """
                    UPDATE prompt_run SET quality_score = %s, feedback = %s
                    WHERE run_id = %s RETURNING run_id
                    """,
                    (quality_score, feedback.strip(), run_id),
                )
            ).fetchone()
            if row is None:
                raise LookupError("prompt run not found")
        return await self.get_run(run_id)

    async def get_run(self, run_id: str) -> dict[str, Any]:
        async with self.database.connection() as connection:
            row = await (
                await connection.execute(
                    "SELECT * FROM prompt_run WHERE run_id = %s", (run_id,)
                )
            ).fetchone()
        if row is None:
            raise LookupError("prompt run not found")
        return _serialized_row(dict(row))

    async def metrics(self, prompt_key: str) -> list[dict[str, Any]]:
        async with self.database.connection() as connection:
            rows = await (
                await connection.execute(
                    """
                    SELECT version, COUNT(*) AS runs,
                           ROUND(AVG(CASE WHEN status = 'completed'
                                          THEN 1.0 ELSE 0.0 END), 4)
                               AS success_rate,
                           ROUND(AVG(latency_ms), 1) AS average_latency_ms,
                           ROUND(AVG(quality_score)::numeric, 2)
                               AS average_quality_score
                    FROM prompt_run WHERE prompt_key = %s
                    GROUP BY version ORDER BY version
                    """,
                    (prompt_key,),
                )
            ).fetchall()
        return [dict(row) for row in rows]
