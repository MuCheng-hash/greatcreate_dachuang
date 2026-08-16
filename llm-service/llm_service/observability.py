from __future__ import annotations

import asyncio
import json
import logging
import math
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable

import httpx
from langchain_core.callbacks import AsyncCallbackHandler
from psycopg.types.json import Jsonb

from .database import Database


LOGGER = logging.getLogger("llm.observability")


def configure_json_logging(level: int = logging.INFO) -> None:
    if not LOGGER.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(message)s"))
        LOGGER.addHandler(handler)
    LOGGER.setLevel(level)
    LOGGER.propagate = False


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc).isoformat()
    return str(value)


def _integer(value: Any) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _message_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            str(item.get("text", "")) if isinstance(item, dict) else str(item)
            for item in content
        )
    return str(content or "")


def _error_type(error: BaseException) -> str:
    name = type(error).__name__.lower()
    message = str(error).lower()
    if "cancel" in name or "cancel" in message:
        return "cancelled"
    if "timeout" in name or "timeout" in message or "timed out" in message:
        return "timeout"
    if any(
        value in message
        for value in ("401", "403", "unauthorized", "forbidden", "api key")
    ):
        return "authentication"
    if "429" in message or "rate limit" in message or "ratelimit" in name:
        return "rate_limit"
    if isinstance(error, (ConnectionError, OSError)):
        return "network"
    if any(value in name for value in ("connection", "network", "transport")) or any(
        value in message for value in ("connection", "network", "dns", "socket")
    ):
        return "network"
    if "json" in name or "json" in message:
        return "json_parse"
    return "provider_error"


def classify_llm_error(error: BaseException) -> str:
    return _error_type(error)


def _error_level(error_type: str) -> int:
    if error_type in {"json_parse", "invalid_response", "cancelled"}:
        return logging.WARNING
    return logging.ERROR


@dataclass(frozen=True, slots=True)
class LlmTraceContext:
    feature: str
    user_id: str = "anonymous"
    session_id: str = ""
    trace_id: str = field(default_factory=lambda: uuid.uuid4().hex)
    expected_json: bool = True
    metadata: dict[str, Any] = field(default_factory=dict)


class FallbackAlertManager:
    """由 lifespan 管理的有界异步告警投递器。"""

    def __init__(
        self,
        webhook_url: str = "",
        timeout_seconds: float = 3.0,
        queue_size: int = 64,
        client: httpx.AsyncClient | None = None,
    ):
        self.webhook_url = webhook_url.strip()
        self.timeout_seconds = max(0.5, timeout_seconds)
        self._queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(
            maxsize=max(1, queue_size)
        )
        self._client = client or httpx.AsyncClient(
            timeout=self.timeout_seconds, trust_env=False
        )
        self._owns_client = client is None
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        if self.webhook_url and (self._task is None or self._task.done()):
            self._task = asyncio.create_task(
                self._worker(), name="llm-fallback-alerts"
            )

    async def close(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        if self._owns_client:
            await self._client.aclose()

    async def exhausted(
        self, context: LlmTraceContext, attempts: list[dict[str, Any]]
    ) -> None:
        payload = {
            "event": "llm_fallback_exhausted",
            "severity": "ERROR",
            "traceId": context.trace_id,
            "userId": context.user_id,
            "sessionId": context.session_id,
            "feature": context.feature,
            "attempts": attempts,
        }
        LOGGER.error(json.dumps(payload, ensure_ascii=True, default=str))
        if not self.webhook_url:
            return
        try:
            self._queue.put_nowait(payload)
        except asyncio.QueueFull:
            LOGGER.error(
                json.dumps(
                    {
                        "event": "llm_alert_queue_full",
                        "severity": "ERROR",
                        "traceId": context.trace_id,
                    },
                    ensure_ascii=True,
                )
            )

    async def fallback(
        self,
        context: LlmTraceContext,
        failed_model: str,
        next_model: str,
        error_type: str,
        fallback_level: int,
    ) -> None:
        LOGGER.warning(
            json.dumps(
                {
                    "event": "llm_model_fallback",
                    "severity": "WARNING",
                    "traceId": context.trace_id,
                    "userId": context.user_id,
                    "sessionId": context.session_id,
                    "feature": context.feature,
                    "failedModel": failed_model,
                    "nextModel": next_model,
                    "errorType": error_type,
                    "fallbackLevel": fallback_level,
                },
                ensure_ascii=True,
                default=str,
            )
        )

    async def _worker(self) -> None:
        while True:
            payload = await self._queue.get()
            try:
                await self._deliver(payload)
            finally:
                self._queue.task_done()

    async def _deliver(self, payload: dict[str, Any]) -> None:
        try:
            response = await self._client.post(self.webhook_url, json=payload)
            response.raise_for_status()
        except Exception as exc:
            LOGGER.error(
                json.dumps(
                    {
                        "event": "llm_alert_delivery_failed",
                        "severity": "ERROR",
                        "traceId": payload.get("traceId"),
                        "errorType": _error_type(exc),
                        "errorMessage": str(exc)[:500],
                    },
                    ensure_ascii=True,
                    default=str,
                )
            )


class LlmObservability:
    def __init__(
        self,
        database: Database,
        model_pricing: dict[str, dict[str, float]] | None = None,
    ):
        self.database = database
        self.model_pricing = model_pricing or {}

    async def start_call(
        self,
        context: LlmTraceContext,
        provider: str,
        model: str,
        span_id: str | None = None,
        parent_span_id: str | None = None,
    ) -> str:
        call_id = str(uuid.uuid4())
        span_id = span_id or uuid.uuid4().hex[:16]
        async with self.database.transaction() as connection:
            await connection.execute(
                """
                INSERT INTO llm_trace(
                    call_id, trace_id, span_id, parent_span_id, user_id,
                    session_id, feature, provider, model,
                    metadata_json, started_at
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (
                    call_id,
                    context.trace_id,
                    span_id,
                    parent_span_id,
                    context.user_id or "anonymous",
                    context.session_id or "",
                    context.feature,
                    provider or "openai-compatible",
                    model or "unknown",
                    Jsonb(context.metadata),
                    utc_now(),
                ),
            )
        self._log(
            "llm_call_started",
            logging.INFO,
            callId=call_id,
            traceId=context.trace_id,
            spanId=span_id,
            userId=context.user_id,
            sessionId=context.session_id,
            feature=context.feature,
            provider=provider,
            model=model,
        )
        return call_id

    async def record_first_token(self, call_id: str, latency_ms: int) -> None:
        async with self.database.transaction() as connection:
            await connection.execute(
                """
                UPDATE llm_trace SET first_token_latency_ms = %s
                WHERE call_id = %s AND first_token_latency_ms IS NULL
                """,
                (max(0, latency_ms), call_id),
            )

    async def finish_call(
        self,
        call_id: str,
        latency_ms: int,
        input_tokens: int | None,
        output_tokens: int | None,
        valid_json: bool | None,
        status: str = "completed",
        error_type: str | None = None,
        error_message: str = "",
    ) -> None:
        total_tokens = None
        if input_tokens is not None or output_tokens is not None:
            total_tokens = (input_tokens or 0) + (output_tokens or 0)
        token_source = "provider" if total_tokens is not None else "unavailable"
        async with self.database.transaction() as connection:
            trace = await (
                await connection.execute(
                    """
                    SELECT model, trace_id, span_id, feature, user_id, session_id
                    FROM llm_trace WHERE call_id = %s FOR UPDATE
                    """,
                    (call_id,),
                )
            ).fetchone()
            if trace is None:
                return
            cost_usd = self._cost(
                str(trace["model"]), input_tokens, output_tokens
            )
            await connection.execute(
                """
                UPDATE llm_trace SET status = %s, error_type = %s,
                    error_message = %s, valid_json = %s, input_tokens = %s,
                    output_tokens = %s, total_tokens = %s, token_source = %s,
                    cost_usd = %s, latency_ms = %s, completed_at = %s
                WHERE call_id = %s
                """,
                (
                    status,
                    error_type,
                    error_message[:2000],
                    valid_json,
                    input_tokens,
                    output_tokens,
                    total_tokens,
                    token_source,
                    cost_usd,
                    max(0, latency_ms),
                    utc_now(),
                    call_id,
                ),
            )
        level = _error_level(error_type or "") if error_type else logging.INFO
        self._log(
            "llm_call_completed" if status == "completed" else "llm_call_failed",
            level,
            callId=call_id,
            traceId=trace["trace_id"],
            spanId=trace["span_id"],
            userId=trace["user_id"],
            sessionId=trace["session_id"],
            feature=trace["feature"],
            model=trace["model"],
            status=status,
            errorType=error_type,
            latencyMs=latency_ms,
            inputTokens=input_tokens,
            outputTokens=output_tokens,
            totalTokens=total_tokens,
            tokenSource=token_source,
            costUsd=cost_usd,
            validJson=valid_json,
        )

    async def fail_call(
        self, call_id: str, latency_ms: int, error: BaseException
    ) -> None:
        error_type = _error_type(error)
        status = "cancelled" if error_type == "cancelled" else "failed"
        await self.finish_call(
            call_id,
            latency_ms,
            None,
            None,
            False,
            status,
            error_type,
            str(error),
        )

    def callback(
        self,
        context: LlmTraceContext,
        provider: str,
        model: str,
        response_validator: Callable[[dict[str, Any]], bool] | None = None,
    ) -> "LlmTraceCallbackHandler":
        return LlmTraceCallbackHandler(
            self, context, provider, model, response_validator
        )

    async def traces(
        self, filters: dict[str, Any], limit: int = 100, offset: int = 0
    ) -> list[dict[str, Any]]:
        where, values = self._where(filters)
        async with self.database.connection() as connection:
            rows = await (
                await connection.execute(
                    f"""
                    SELECT * FROM llm_trace {where}
                    ORDER BY started_at DESC LIMIT %s OFFSET %s
                    """,
                    (*values, max(1, min(limit, 500)), max(0, offset)),
                )
            ).fetchall()
        return [self._trace_dict(row) for row in rows]

    async def summary(self, filters: dict[str, Any]) -> dict[str, Any]:
        where, values = self._where(filters)
        async with self.database.connection() as connection:
            rows = await (
                await connection.execute(
                    f"SELECT * FROM llm_trace {where} ORDER BY started_at",
                    values,
                )
            ).fetchall()
        completed = [row for row in rows if row["status"] != "started"]
        latencies = sorted(
            int(row["latency_ms"])
            for row in completed
            if row["latency_ms"] is not None
        )
        successful = [row for row in completed if row["status"] == "completed"]
        json_rows = [row for row in completed if row["valid_json"] is not None]
        costs = [
            float(row["cost_usd"])
            for row in completed
            if row["cost_usd"] is not None
        ]
        errors: dict[str, int] = {}
        fallback_by_level: dict[str, int] = {}
        fallback_reasons: dict[str, int] = {}
        fallback_calls = 0
        for row in completed:
            metadata = dict(row["metadata_json"] or {})
            fallback_level = metadata.get("fallbackLevel")
            level_key = str(fallback_level) if fallback_level is not None else ""
            is_fallback = level_key.lower() == "local"
            if not is_fallback:
                try:
                    is_fallback = int(fallback_level) > 0
                except (TypeError, ValueError):
                    is_fallback = False
            if is_fallback:
                fallback_calls += 1
                fallback_by_level[level_key] = fallback_by_level.get(level_key, 0) + 1
            if row["error_type"]:
                key = str(row["error_type"])
                errors[key] = errors.get(key, 0) + 1
                fallback_reasons[key] = fallback_reasons.get(key, 0) + 1
        return {
            "calls": len(completed),
            "inFlightCalls": len(rows) - len(completed),
            "successfulCalls": len(successful),
            "successRate": self._rate(len(successful), len(completed)),
            "validJsonRate": self._rate(
                sum(1 for row in json_rows if row["valid_json"]), len(json_rows)
            ),
            "latencyMs": {
                "average": round(sum(latencies) / len(latencies), 1)
                if latencies
                else None,
                "p50": self._percentile(latencies, 0.50),
                "p95": self._percentile(latencies, 0.95),
                "p99": self._percentile(latencies, 0.99),
            },
            "tokens": {
                "input": sum(int(row["input_tokens"] or 0) for row in completed),
                "output": sum(int(row["output_tokens"] or 0) for row in completed),
                "total": sum(int(row["total_tokens"] or 0) for row in completed),
                "callsWithUsage": sum(
                    1 for row in completed if row["token_source"] == "provider"
                ),
                "callsWithoutUsage": sum(
                    1 for row in completed if row["token_source"] != "provider"
                ),
            },
            "costUsd": round(sum(costs), 8),
            "pricedCalls": len(costs),
            "unpricedCalls": len(completed) - len(costs),
            "errors": errors,
            "fallbackCalls": fallback_calls,
            "fallbackByLevel": fallback_by_level,
            "fallbackReasons": fallback_reasons,
            "breakdown": {
                "byUser": self._breakdown(completed, "user_id", "userId"),
                "bySession": self._breakdown(
                    completed, "session_id", "sessionId"
                ),
                "byFeature": self._breakdown(completed, "feature", "feature"),
            },
            "groups": self._groups(completed),
        }

    async def prometheus_metrics(self) -> str:
        async with self.database.connection() as connection:
            rows = await (
                await connection.execute(
                    """
                    SELECT feature, model, status, error_type, COUNT(*) AS calls,
                           COALESCE(SUM(input_tokens), 0) AS input_tokens,
                           COALESCE(SUM(output_tokens), 0) AS output_tokens,
                           COALESCE(SUM(cost_usd), 0) AS cost_usd,
                           COALESCE(SUM(latency_ms), 0) AS latency_ms
                    FROM llm_trace WHERE status != 'started'
                    GROUP BY feature, model, status, error_type
                    """
                )
            ).fetchall()
        lines = [
            "# HELP llm_calls_total Completed LLM calls.",
            "# TYPE llm_calls_total counter",
            "# HELP llm_tokens_total Provider-reported LLM tokens.",
            "# TYPE llm_tokens_total counter",
            "# HELP llm_cost_usd_total Calculated LLM cost in USD.",
            "# TYPE llm_cost_usd_total counter",
            "# HELP llm_latency_ms_sum Sum of LLM call latency in milliseconds.",
            "# TYPE llm_latency_ms_sum counter",
        ]
        for row in rows:
            labels = self._labels(
                feature=row["feature"],
                model=row["model"],
                status=row["status"],
                error_type=row["error_type"] or "none",
            )
            lines.append(f"llm_calls_total{{{labels}}} {row['calls']}")
            for token_type in ("input", "output"):
                token_labels = labels + f',type="{token_type}"'
                lines.append(
                    f"llm_tokens_total{{{token_labels}}} "
                    f"{row[token_type + '_tokens']}"
                )
            lines.append(
                f"llm_cost_usd_total{{{labels}}} {float(row['cost_usd']):.8f}"
            )
            lines.append(f"llm_latency_ms_sum{{{labels}}} {row['latency_ms']}")
        return "\n".join(lines) + "\n"

    def _cost(
        self,
        model: str,
        input_tokens: int | None,
        output_tokens: int | None,
    ) -> float | None:
        pricing = self.model_pricing.get(model) or self.model_pricing.get("*")
        if not pricing or (input_tokens is None and output_tokens is None):
            return None
        input_rate = float(pricing.get("input", 0))
        output_rate = float(pricing.get("output", 0))
        return round(
            ((input_tokens or 0) * input_rate + (output_tokens or 0) * output_rate)
            / 1_000_000,
            10,
        )

    @staticmethod
    def _where(filters: dict[str, Any]) -> tuple[str, list[Any]]:
        clauses: list[str] = []
        values: list[Any] = []
        columns = {
            "user_id": "user_id",
            "session_id": "session_id",
            "feature": "feature",
            "model": "model",
            "status": "status",
            "trace_id": "trace_id",
        }
        for key, column in columns.items():
            if filters.get(key):
                clauses.append(f"{column} = %s")
                values.append(str(filters[key]))
        if filters.get("started_after"):
            clauses.append("started_at >= %s::timestamptz")
            values.append(str(filters["started_after"]))
        if filters.get("started_before"):
            clauses.append("started_at <= %s::timestamptz")
            values.append(str(filters["started_before"]))
        return ("WHERE " + " AND ".join(clauses) if clauses else ""), values

    def _groups(self, rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
        grouped: dict[tuple[str, str, str], list[dict[str, Any]]] = {}
        for row in rows:
            key = (
                str(row["user_id"]),
                str(row["session_id"]),
                str(row["feature"]),
            )
            grouped.setdefault(key, []).append(row)
        result = []
        for (user_id, session_id, feature), items in grouped.items():
            costs = [
                float(item["cost_usd"])
                for item in items
                if item["cost_usd"] is not None
            ]
            success_count = sum(
                1 for item in items if item["status"] == "completed"
            )
            result.append(
                {
                    "userId": user_id,
                    "sessionId": session_id,
                    "feature": feature,
                    "calls": len(items),
                    "successRate": self._rate(success_count, len(items)),
                    "inputTokens": sum(
                        int(item["input_tokens"] or 0) for item in items
                    ),
                    "outputTokens": sum(
                        int(item["output_tokens"] or 0) for item in items
                    ),
                    "costUsd": round(sum(costs), 8),
                    "unpricedCalls": len(items) - len(costs),
                }
            )
        return sorted(result, key=lambda item: (-item["calls"], item["feature"]))

    def _breakdown(
        self, rows: list[dict[str, Any]], column: str, output_key: str
    ) -> list[dict[str, Any]]:
        grouped: dict[str, list[dict[str, Any]]] = {}
        for row in rows:
            grouped.setdefault(str(row[column] or "none"), []).append(row)
        result = []
        for value, items in grouped.items():
            costs = [
                float(item["cost_usd"])
                for item in items
                if item["cost_usd"] is not None
            ]
            result.append(
                {
                    output_key: value,
                    "calls": len(items),
                    "successRate": self._rate(
                        sum(
                            1
                            for item in items
                            if item["status"] == "completed"
                        ),
                        len(items),
                    ),
                    "inputTokens": sum(
                        int(item["input_tokens"] or 0) for item in items
                    ),
                    "outputTokens": sum(
                        int(item["output_tokens"] or 0) for item in items
                    ),
                    "costUsd": round(sum(costs), 8),
                    "unpricedCalls": len(items) - len(costs),
                }
            )
        return sorted(
            result, key=lambda item: (-item["calls"], str(item[output_key]))
        )

    @staticmethod
    def _trace_dict(row: dict[str, Any]) -> dict[str, Any]:
        return {
            "callId": row["call_id"],
            "traceId": row["trace_id"],
            "spanId": row["span_id"],
            "parentSpanId": row["parent_span_id"],
            "userId": row["user_id"],
            "sessionId": row["session_id"],
            "feature": row["feature"],
            "provider": row["provider"],
            "model": row["model"],
            "status": row["status"],
            "errorType": row["error_type"],
            "errorMessage": row["error_message"],
            "validJson": row["valid_json"],
            "inputTokens": row["input_tokens"],
            "outputTokens": row["output_tokens"],
            "totalTokens": row["total_tokens"],
            "tokenSource": row["token_source"],
            "costUsd": row["cost_usd"],
            "latencyMs": row["latency_ms"],
            "firstTokenLatencyMs": row["first_token_latency_ms"],
            "metadata": dict(row["metadata_json"] or {}),
            "startedAt": _iso(row["started_at"]),
            "completedAt": _iso(row["completed_at"]),
        }

    @staticmethod
    def _rate(numerator: int, denominator: int) -> float | None:
        return round(numerator / denominator, 4) if denominator else None

    @staticmethod
    def _percentile(values: list[int], quantile: float) -> int | None:
        if not values:
            return None
        return values[max(0, math.ceil(len(values) * quantile) - 1)]

    @staticmethod
    def _labels(**values: Any) -> str:
        def escape(value: Any) -> str:
            return (
                str(value)
                .replace("\\", "\\\\")
                .replace('"', '\\"')
                .replace("\n", "\\n")
            )

        return ",".join(
            f'{key}="{escape(value)}"' for key, value in values.items()
        )

    @staticmethod
    def _log(event: str, level: int, **fields: Any) -> None:
        LOGGER.log(
            level,
            json.dumps(
                {
                    "event": event,
                    "severity": logging.getLevelName(level),
                    **fields,
                },
                ensure_ascii=True,
                default=str,
            ),
        )


class LlmTraceCallbackHandler(AsyncCallbackHandler):
    def __init__(
        self,
        store: LlmObservability,
        context: LlmTraceContext,
        provider: str,
        model: str,
        response_validator: Callable[[dict[str, Any]], bool] | None = None,
    ):
        self.store = store
        self.context = context
        self.provider = provider
        self.model = model
        self.response_validator = response_validator
        self._calls: dict[str, tuple[str, float]] = {}
        self._lock = asyncio.Lock()

    async def on_chat_model_start(
        self,
        serialized: dict[str, Any],
        messages: list[list[Any]],
        *,
        run_id: Any,
        parent_run_id: Any = None,
        **kwargs: Any,
    ) -> None:
        await self._start(run_id, parent_run_id)

    async def on_llm_start(
        self,
        serialized: dict[str, Any],
        prompts: list[str],
        *,
        run_id: Any,
        parent_run_id: Any = None,
        **kwargs: Any,
    ) -> None:
        await self._start(run_id, parent_run_id)

    async def on_llm_new_token(
        self, token: str, *, run_id: Any, **kwargs: Any
    ) -> None:
        async with self._lock:
            current = self._calls.get(str(run_id))
        if current:
            await self.store.record_first_token(
                current[0], round((time.perf_counter() - current[1]) * 1000)
            )

    async def on_llm_end(
        self, response: Any, *, run_id: Any, **kwargs: Any
    ) -> None:
        current = await self._pop(run_id)
        if current is None:
            return
        call_id, started = current
        input_tokens, output_tokens = self._usage(response)
        content = self._content(response)
        latency = round((time.perf_counter() - started) * 1000)
        if self._has_tool_calls(response):
            await self.store.finish_call(
                call_id, latency, input_tokens, output_tokens, None
            )
            return
        parsed = self._parse_json(content) if self.context.expected_json else None
        valid_json = parsed is not None if self.context.expected_json else None
        if parsed is not None and self.response_validator is not None:
            try:
                schema_valid = bool(self.response_validator(parsed))
            except Exception:
                schema_valid = False
            if not schema_valid:
                await self.store.finish_call(
                    call_id,
                    latency,
                    input_tokens,
                    output_tokens,
                    False,
                    "invalid_response",
                    "schema_validation",
                    "model response failed feature schema validation",
                )
                return
        if self.context.expected_json and not valid_json:
            await self.store.finish_call(
                call_id,
                latency,
                input_tokens,
                output_tokens,
                False,
                "invalid_response",
                "json_parse",
                "model response is not a valid JSON object",
            )
            return
        await self.store.finish_call(
            call_id,
            latency,
            input_tokens,
            output_tokens,
            valid_json,
        )

    async def on_llm_error(
        self, error: BaseException, *, run_id: Any, **kwargs: Any
    ) -> None:
        current = await self._pop(run_id)
        if current:
            await self.store.fail_call(
                current[0],
                round((time.perf_counter() - current[1]) * 1000),
                error,
            )

    async def _start(self, run_id: Any, parent_run_id: Any) -> None:
        key = str(run_id)
        async with self._lock:
            if key in self._calls:
                return
            call_id = await self.store.start_call(
                self.context,
                self.provider,
                self.model,
                span_id=key.replace("-", "")[:16],
                parent_span_id=(
                    str(parent_run_id).replace("-", "")[:16]
                    if parent_run_id
                    else None
                ),
            )
            self._calls[key] = (call_id, time.perf_counter())

    async def _pop(self, run_id: Any) -> tuple[str, float] | None:
        async with self._lock:
            return self._calls.pop(str(run_id), None)

    @staticmethod
    def _usage(response: Any) -> tuple[int | None, int | None]:
        llm_output = getattr(response, "llm_output", None) or {}
        candidates = [
            llm_output.get("token_usage"),
            llm_output.get("usage"),
            llm_output,
        ]
        for candidate in candidates:
            if isinstance(candidate, dict):
                input_tokens = _integer(
                    candidate.get("input_tokens", candidate.get("prompt_tokens"))
                )
                output_tokens = _integer(
                    candidate.get(
                        "output_tokens", candidate.get("completion_tokens")
                    )
                )
                if input_tokens is not None or output_tokens is not None:
                    return input_tokens, output_tokens
        for generation_group in getattr(response, "generations", []) or []:
            for generation in generation_group:
                message = getattr(generation, "message", None)
                usage = getattr(message, "usage_metadata", None) or {}
                if isinstance(usage, dict):
                    input_tokens = _integer(
                        usage.get("input_tokens", usage.get("prompt_tokens"))
                    )
                    output_tokens = _integer(
                        usage.get(
                            "output_tokens", usage.get("completion_tokens")
                        )
                    )
                    if input_tokens is not None or output_tokens is not None:
                        return input_tokens, output_tokens
        return None, None

    @staticmethod
    def _content(response: Any) -> str:
        parts: list[str] = []
        for generation_group in getattr(response, "generations", []) or []:
            for generation in generation_group:
                message = getattr(generation, "message", None)
                parts.append(
                    _message_text(
                        getattr(message, "content", None)
                        or getattr(generation, "text", "")
                    )
                )
        return "".join(parts)

    @staticmethod
    def _has_tool_calls(response: Any) -> bool:
        for generation_group in getattr(response, "generations", []) or []:
            for generation in generation_group:
                message = getattr(generation, "message", None)
                if getattr(message, "tool_calls", None):
                    return True
                additional = getattr(message, "additional_kwargs", None) or {}
                if isinstance(additional, dict) and additional.get("tool_calls"):
                    return True
        return False

    @staticmethod
    def _parse_json(content: str) -> dict[str, Any] | None:
        normalized = content.strip()
        try:
            parsed = json.loads(normalized)
            return parsed if isinstance(parsed, dict) else None
        except (TypeError, ValueError, json.JSONDecodeError):
            return None
