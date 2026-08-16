from __future__ import annotations

import asyncio
import time
from dataclasses import asdict, dataclass
from typing import Any

from .business_tool_client import BusinessToolClient
from .checkpointing import CheckpointManager
from .database import Database, SchemaMigrator
from .prompt_manager import PromptManager
from .settings import Settings


@dataclass(frozen=True, slots=True)
class DependencyHealth:
    status: str
    required: bool
    latency_ms: int
    detail: str

    def as_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["latencyMs"] = value.pop("latency_ms")
        return value


class HealthService:
    def __init__(
        self,
        settings: Settings,
        database: Database,
        migrator: SchemaMigrator,
        prompts: PromptManager,
        business_tool_client: BusinessToolClient,
        checkpoints: CheckpointManager,
    ):
        self.settings = settings
        self.database = database
        self.migrator = migrator
        self.prompts = prompts
        self.business_tool_client = business_tool_client
        self.checkpoints = checkpoints

    def live(self) -> dict[str, Any]:
        return {
            "status": "ok",
            "service": self.settings.service_name,
            "environment": self.settings.app_env,
        }

    async def ready(self) -> tuple[bool, dict[str, Any]]:
        database, schema, checkpointer, prompt, business = await asyncio.gather(
            self._check_database(),
            self._check_schema(),
            self._check_checkpointer(),
            self._check_prompt(),
            self._check_business_service(),
        )
        model = self._check_model_chain()
        dependencies = {
            "database": database.as_dict(),
            "schema": schema.as_dict(),
            "checkpointer": checkpointer.as_dict(),
            "prompt": prompt.as_dict(),
            "businessService": business.as_dict(),
            "modelChain": model.as_dict(),
        }
        is_ready = all(
            dependency.status == "up"
            for dependency in (
                database,
                schema,
                checkpointer,
                prompt,
                business,
                model,
            )
            if dependency.required
        )
        return is_ready, {
            "status": "ready" if is_ready else "not_ready",
            "service": self.settings.service_name,
            "environment": self.settings.app_env,
            "dependencies": dependencies,
        }

    async def _check_database(self) -> DependencyHealth:
        started = time.perf_counter()
        try:
            info = await self.database.ping()
            server_version = int(info.get("server_version_num") or 0) // 10000
            detail = "PostgreSQL connection pool available"
            if server_version:
                detail += f" (server {server_version})"
            return self._result("up", True, started, detail)
        except Exception as exc:
            return self._result("down", True, started, self._safe_error(exc))

    async def _check_schema(self) -> DependencyHealth:
        started = time.perf_counter()
        try:
            version = await self.migrator.validate()
            return self._result(
                "up", True, started, f"schema version {version} is current"
            )
        except Exception as exc:
            return self._result("down", True, started, self._safe_error(exc))

    async def _check_prompt(self) -> DependencyHealth:
        started = time.perf_counter()
        try:
            selection = await self.prompts.resolve(
                "teaching-plan", "health-check", {}
            )
            return self._result(
                "up", True, started, f"active version {selection.version} readable"
            )
        except Exception as exc:
            return self._result("down", True, started, self._safe_error(exc))

    async def _check_checkpointer(self) -> DependencyHealth:
        started = time.perf_counter()
        try:
            version = await self.checkpoints.validate()
            return self._result(
                "up",
                True,
                started,
                f"checkpointer schema version {version} is current",
            )
        except Exception as exc:
            return self._result("down", True, started, self._safe_error(exc))

    def _check_model_chain(self) -> DependencyHealth:
        started = time.perf_counter()
        targets = self.settings.model_chain
        configured = len(targets)
        structured = sum(
            1
            for target in targets
            if target.supports_json_object or target.supports_json_schema
        )
        required = self.settings.require_llm_model
        if configured and (structured or not required):
            return self._result(
                "up",
                required,
                started,
                f"{configured} model target(s) configured; {structured} declared for structured output",
            )
        if configured:
            return self._result(
                "down",
                required,
                started,
                "no configured model declares JSON object or JSON schema support",
            )
        return self._result(
            "down", required, started, "no model target configured"
        )

    async def _check_business_service(self) -> DependencyHealth:
        started = time.perf_counter()
        required = self.settings.business_health_required
        try:
            await self.business_tool_client.health(
                self.settings.business_health_path
            )
            return self._result(
                "up", required, started, "business service reachable"
            )
        except Exception as exc:
            return self._result("down", required, started, self._safe_error(exc))

    @staticmethod
    def _result(
        status: str, required: bool, started: float, detail: str
    ) -> DependencyHealth:
        latency_ms = max(0, round((time.perf_counter() - started) * 1000))
        return DependencyHealth(status, required, latency_ms, detail)

    @staticmethod
    def _safe_error(exc: Exception) -> str:
        return f"{type(exc).__name__}: dependency check failed"
