from __future__ import annotations

import asyncio
import sqlite3
import time
from dataclasses import asdict, dataclass
from typing import Any, Awaitable, Callable

import httpx

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
        prompts: PromptManager,
        http_client_factory: Callable[..., httpx.AsyncClient] = httpx.AsyncClient,
    ):
        self.settings = settings
        self.prompts = prompts
        self.http_client_factory = http_client_factory

    def live(self) -> dict[str, Any]:
        return {
            "status": "ok",
            "service": self.settings.service_name,
            "environment": self.settings.app_env,
        }

    async def ready(self) -> tuple[bool, dict[str, Any]]:
        database, prompt, business = await asyncio.gather(
            asyncio.to_thread(self._check_database),
            asyncio.to_thread(self._check_prompt),
            self._check_business_service(),
        )
        model = self._check_model_chain()
        dependencies = {
            "database": database.as_dict(),
            "prompt": prompt.as_dict(),
            "businessService": business.as_dict(),
            "modelChain": model.as_dict(),
        }
        is_ready = all(
            dependency.status == "up"
            for dependency in (database, prompt, business, model)
            if dependency.required
        )
        return is_ready, {
            "status": "ready" if is_ready else "not_ready",
            "service": self.settings.service_name,
            "environment": self.settings.app_env,
            "dependencies": dependencies,
        }

    def _check_database(self) -> DependencyHealth:
        started = time.perf_counter()
        try:
            with sqlite3.connect(self.settings.database_path, timeout=2) as connection:
                connection.execute("BEGIN IMMEDIATE")
                connection.execute("SELECT 1").fetchone()
                connection.rollback()
            return self._result("up", True, started, "SQLite read/write lock available")
        except (OSError, sqlite3.Error) as exc:
            return self._result("down", True, started, self._safe_error(exc))

    def _check_prompt(self) -> DependencyHealth:
        started = time.perf_counter()
        try:
            selection = self.prompts.resolve("teaching-plan", "health-check", {})
            return self._result("up", True, started, f"active version {selection.version}")
        except (LookupError, OSError, sqlite3.Error, RuntimeError) as exc:
            return self._result("down", True, started, self._safe_error(exc))

    def _check_model_chain(self) -> DependencyHealth:
        started = time.perf_counter()
        configured = len(self.settings.model_chain)
        required = self.settings.require_llm_model
        if configured:
            return self._result("up", required, started, f"{configured} model target(s) configured")
        return self._result("down", required, started, "no model target configured")

    async def _check_business_service(self) -> DependencyHealth:
        started = time.perf_counter()
        required = self.settings.business_health_required
        if not self.settings.internal_business_base_url:
            return self._result("down", required, started, "business service URL not configured")
        headers = {"Accept": "application/json"}
        if self.settings.internal_service_token:
            headers["X-Agent-Service-Token"] = self.settings.internal_service_token
        url = f"{self.settings.internal_business_base_url}{self.settings.business_health_path}"
        try:
            async with self.http_client_factory(
                timeout=self.settings.health_check_timeout_seconds,
                trust_env=False,
            ) as client:
                response = await client.get(url, headers=headers)
            if response.status_code >= 400:
                return self._result(
                    "down", required, started, f"business service returned HTTP {response.status_code}"
                )
            payload = response.json()
            if isinstance(payload, dict) and "code" in payload and payload.get("code") != 200:
                return self._result(
                    "down", required, started, "business service rejected health check"
                )
            return self._result("up", required, started, "business service reachable")
        except (httpx.HTTPError, ValueError, TypeError) as exc:
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

