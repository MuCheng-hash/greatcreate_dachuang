from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass(frozen=True, slots=True)
class PromptSelection:
    prompt_key: str
    version: str
    content: str
    experiment_key: str | None = None
    variant: str | None = None


class PromptManager:
    def __init__(self, database_path: Path | str, prompt_root: Path | str):
        self.database_path = Path(database_path)
        self.prompt_root = Path(prompt_root)
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._initialize()
        self._seed_file_prompt("teaching-plan", "v1", self.prompt_root / "teaching-plan" / "v1" / "system.md")

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode = WAL")
        return connection

    def _initialize(self) -> None:
        with self._lock, self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS prompt_version (
                    prompt_key TEXT NOT NULL,
                    version TEXT NOT NULL,
                    content TEXT NOT NULL,
                    active INTEGER NOT NULL DEFAULT 0,
                    created_by TEXT NOT NULL DEFAULT 'system',
                    notes TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (prompt_key, version)
                );
                CREATE INDEX IF NOT EXISTS idx_prompt_version_active
                    ON prompt_version(prompt_key, active);
                CREATE TABLE IF NOT EXISTS prompt_experiment (
                    prompt_key TEXT PRIMARY KEY,
                    experiment_key TEXT NOT NULL,
                    variants_json TEXT NOT NULL,
                    active INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS prompt_run (
                    run_id TEXT PRIMARY KEY,
                    prompt_key TEXT NOT NULL,
                    version TEXT NOT NULL,
                    experiment_key TEXT,
                    variant TEXT,
                    subject_key TEXT NOT NULL,
                    model TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'started',
                    latency_ms INTEGER,
                    input_characters INTEGER NOT NULL DEFAULT 0,
                    output_characters INTEGER NOT NULL DEFAULT 0,
                    quality_score REAL,
                    feedback TEXT NOT NULL DEFAULT '',
                    error_message TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    completed_at TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_prompt_run_metrics
                    ON prompt_run(prompt_key, version, created_at DESC);
                """
            )

    def _seed_file_prompt(self, prompt_key: str, version: str, path: Path) -> None:
        if not path.is_file():
            raise RuntimeError(f"prompt seed file not found: {path}")
        content = path.read_text(encoding="utf-8").strip()
        with self._lock, self._connect() as connection:
            exists = connection.execute(
                "SELECT 1 FROM prompt_version WHERE prompt_key = ? LIMIT 1", (prompt_key,)
            ).fetchone()
            connection.execute(
                """INSERT OR IGNORE INTO prompt_version(
                       prompt_key, version, content, active, created_by, notes, created_at
                   ) VALUES (?, ?, ?, ?, 'system', 'seeded from repository', ?)""",
                (prompt_key, version, content, 0 if exists else 1, utc_now()),
            )

    def create_version(
        self, prompt_key: str, version: str, content: str, created_by: str = "admin", notes: str = ""
    ) -> dict[str, Any]:
        prompt_key = prompt_key.strip()
        version = version.strip()
        content = content.strip()
        if not prompt_key or not version or not content:
            raise ValueError("promptKey, version and content are required")
        if "{{context_json}}" not in content:
            raise ValueError("prompt content must contain {{context_json}}")
        with self._lock, self._connect() as connection:
            connection.execute(
                """INSERT INTO prompt_version(
                       prompt_key, version, content, active, created_by, notes, created_at
                   ) VALUES (?, ?, ?, 0, ?, ?, ?)""",
                (prompt_key, version, content, created_by.strip() or "admin", notes.strip(), utc_now()),
            )
        return self.get_version(prompt_key, version)

    def activate_version(self, prompt_key: str, version: str) -> dict[str, Any]:
        with self._lock, self._connect() as connection:
            exists = connection.execute(
                "SELECT 1 FROM prompt_version WHERE prompt_key = ? AND version = ?", (prompt_key, version)
            ).fetchone()
            if exists is None:
                raise LookupError("prompt version not found")
            connection.execute("UPDATE prompt_version SET active = 0 WHERE prompt_key = ?", (prompt_key,))
            connection.execute(
                "UPDATE prompt_version SET active = 1 WHERE prompt_key = ? AND version = ?",
                (prompt_key, version),
            )
        return self.get_version(prompt_key, version)

    def list_versions(self, prompt_key: str) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                """SELECT prompt_key, version, active, created_by, notes, created_at
                   FROM prompt_version WHERE prompt_key = ? ORDER BY created_at DESC""",
                (prompt_key,),
            ).fetchall()
        return [dict(row) for row in rows]

    def get_version(self, prompt_key: str, version: str) -> dict[str, Any]:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM prompt_version WHERE prompt_key = ? AND version = ?", (prompt_key, version)
            ).fetchone()
        if row is None:
            raise LookupError("prompt version not found")
        return dict(row)

    def configure_experiment(
        self, prompt_key: str, experiment_key: str, variants: list[dict[str, Any]], active: bool
    ) -> dict[str, Any]:
        if not experiment_key.strip():
            raise ValueError("experimentKey is required")
        normalized: list[dict[str, Any]] = []
        total_weight = 0
        for item in variants:
            version = str(item.get("version") or "").strip()
            weight = int(item.get("weight") or 0)
            if not version or weight <= 0:
                raise ValueError("each experiment variant requires a version and positive weight")
            self.get_version(prompt_key, version)
            normalized.append({"version": version, "weight": weight})
            total_weight += weight
        if len(normalized) < 2 or total_weight <= 0:
            raise ValueError("an experiment requires at least two weighted variants")
        with self._lock, self._connect() as connection:
            connection.execute(
                """INSERT INTO prompt_experiment(prompt_key, experiment_key, variants_json, active, updated_at)
                   VALUES (?, ?, ?, ?, ?)
                   ON CONFLICT(prompt_key) DO UPDATE SET experiment_key = excluded.experiment_key,
                     variants_json = excluded.variants_json, active = excluded.active, updated_at = excluded.updated_at""",
                (prompt_key, experiment_key.strip(), json.dumps(normalized), int(active), utc_now()),
            )
        return self.get_experiment(prompt_key)

    def get_experiment(self, prompt_key: str) -> dict[str, Any]:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM prompt_experiment WHERE prompt_key = ?", (prompt_key,)
            ).fetchone()
        if row is None:
            return {"prompt_key": prompt_key, "active": 0, "variants": []}
        result = dict(row)
        result["variants"] = json.loads(result.pop("variants_json"))
        return result

    def resolve(self, prompt_key: str, subject_key: str, context: dict[str, Any]) -> PromptSelection:
        experiment = self.get_experiment(prompt_key)
        version: str | None = None
        experiment_key: str | None = None
        variant: str | None = None
        if experiment.get("active") and experiment.get("variants"):
            experiment_key = str(experiment["experiment_key"])
            variants = experiment["variants"]
            total = sum(int(item["weight"]) for item in variants)
            bucket = int(hashlib.sha256(f"{experiment_key}:{subject_key}".encode()).hexdigest(), 16) % total
            cursor = 0
            for item in variants:
                cursor += int(item["weight"])
                if bucket < cursor:
                    version = str(item["version"])
                    variant = version
                    break
        if version is None:
            with self._connect() as connection:
                row = connection.execute(
                    """SELECT version FROM prompt_version
                       WHERE prompt_key = ? AND active = 1 ORDER BY created_at DESC LIMIT 1""",
                    (prompt_key,),
                ).fetchone()
            if row is None:
                raise LookupError("active prompt version not found")
            version = str(row["version"])
        record = self.get_version(prompt_key, version)
        rendered = str(record["content"]).replace(
            "{{context_json}}", json.dumps(context, ensure_ascii=False, separators=(",", ":"))
        )
        return PromptSelection(prompt_key, version, rendered, experiment_key, variant)

    def start_run(self, selection: PromptSelection, subject_key: str, model: str, input_characters: int) -> str:
        run_id = str(uuid.uuid4())
        with self._lock, self._connect() as connection:
            connection.execute(
                """INSERT INTO prompt_run(
                       run_id, prompt_key, version, experiment_key, variant, subject_key, model,
                       input_characters, created_at
                   ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (run_id, selection.prompt_key, selection.version, selection.experiment_key,
                 selection.variant, subject_key, model, input_characters, utc_now()),
            )
        return run_id

    def finish_run(
        self, run_id: str, status: str, latency_ms: int, output_characters: int, error_message: str = ""
    ) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """UPDATE prompt_run SET status = ?, latency_ms = ?, output_characters = ?,
                       error_message = ?, completed_at = ? WHERE run_id = ?""",
                (status, latency_ms, output_characters, error_message[:1000], utc_now(), run_id),
            )

    def add_feedback(self, run_id: str, quality_score: float, feedback: str = "") -> dict[str, Any]:
        if quality_score < 0 or quality_score > 5:
            raise ValueError("qualityScore must be between 0 and 5")
        with self._lock, self._connect() as connection:
            cursor = connection.execute(
                "UPDATE prompt_run SET quality_score = ?, feedback = ? WHERE run_id = ?",
                (quality_score, feedback.strip(), run_id),
            )
            if cursor.rowcount == 0:
                raise LookupError("prompt run not found")
        return self.get_run(run_id)

    def get_run(self, run_id: str) -> dict[str, Any]:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM prompt_run WHERE run_id = ?", (run_id,)).fetchone()
        if row is None:
            raise LookupError("prompt run not found")
        return dict(row)

    def metrics(self, prompt_key: str) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                """SELECT version, COUNT(*) AS runs,
                          ROUND(AVG(CASE WHEN status = 'completed' THEN 1.0 ELSE 0.0 END), 4) AS success_rate,
                          ROUND(AVG(latency_ms), 1) AS average_latency_ms,
                          ROUND(AVG(quality_score), 2) AS average_quality_score
                   FROM prompt_run WHERE prompt_key = ? GROUP BY version ORDER BY version""",
                (prompt_key,),
            ).fetchall()
        return [dict(row) for row in rows]
