from __future__ import annotations

import inspect
import hashlib
import json
import time
from contextvars import ContextVar, Token
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from langchain_core.tools import tool

from .business_tool_client import BusinessToolClient, BusinessToolError
from .repository import ConversationRepository
from .schemas import ToolExecution, TrustedContext


def _text(value: Any) -> str:
    return " ".join(str(value or "").lower().split())


def _matches(item: dict[str, Any], query: str) -> bool:
    if not query.strip():
        return True
    haystack = json.dumps(item, ensure_ascii=False).lower()
    terms = [term for term in _text(query).split(" ") if term]
    return all(term in haystack for term in terms) or _text(query) in haystack


@dataclass(slots=True)
class ToolRuntimeContext:
    thread_id: str
    trusted_context: TrustedContext
    repository: ConversationRepository
    output_character_limit: int
    turn_id: str | None = None
    action_id: str | None = None
    call_namespace: str = "graph"
    executions: list[ToolExecution] = field(default_factory=list)
    event_sink: Callable[[str, dict[str, Any]], None] | None = None
    business_tool_client: BusinessToolClient | None = None
    grade: str | None = None
    theme: str | None = None
    degraded_reasons: list[str] = field(default_factory=list)
    _call_counts: dict[str, int] = field(default_factory=dict)

    def _emit(self, event_name: str, data: dict[str, Any]) -> None:
        if self.event_sink is None:
            return
        try:
            self.event_sink(event_name, data)
        except Exception:
            return

    async def run(
        self,
        name: str,
        arguments: dict[str, Any],
        callback: Callable[[], Any | Awaitable[Any]],
    ) -> str:
        started = time.perf_counter()
        safe_arguments = _sanitize(arguments)
        tool_call_id = self._tool_call_id(name, safe_arguments)
        self._emit(
            "tool.started",
            {"toolName": name, "name": name, "arguments": safe_arguments},
        )
        if self.turn_id and tool_call_id:
            existing = await self.repository.find_tool_audit(
                self.turn_id, tool_call_id
            )
            if existing is not None and str(existing["status"]) != "failed":
                status = str(existing["status"])
                duration_ms = int(existing["duration_ms"])
                bounded = str(existing["result_preview"])
                self.executions.append(
                    ToolExecution(name=name, status=status, durationMs=duration_ms)
                )
                self._emit(
                    "tool.completed",
                    {
                        "toolName": name,
                        "name": name,
                        "status": "ok" if status == "completed" else status,
                        "durationMs": duration_ms,
                        "resumed": True,
                        "outputSummary": bounded[:160] or "未返回结果",
                    },
                )
                return bounded
        status = "completed"
        result: Any = None
        try:
            result = callback()
            if inspect.isawaitable(result):
                result = await result
            if _is_degraded_result(result):
                status = "degraded"
            output = json.dumps(result, ensure_ascii=False, default=str)
        except Exception as exc:
            status = "failed"
            output = json.dumps(
                {"error": type(exc).__name__}, ensure_ascii=False
            )
        duration_ms = int((time.perf_counter() - started) * 1000)
        bounded = output[: self.output_character_limit]
        audit = await self.repository.add_tool_audit(
            self.thread_id,
            name,
            safe_arguments,
            status,
            duration_ms,
            bounded,
            turn_id=self.turn_id,
            tool_call_id=tool_call_id,
        )
        status = str(audit["status"])
        duration_ms = int(audit["duration_ms"])
        bounded = str(audit["result_preview"])
        self.executions.append(
            ToolExecution(name=name, status=status, durationMs=duration_ms)
        )
        self._emit(
            "tool.completed",
            {
                "toolName": name,
                "name": name,
                "status": "ok" if status == "completed" else status,
                "durationMs": duration_ms,
                "outputSummary": _output_summary(
                    result if status == "completed" else None, bounded
                ),
            },
        )
        return bounded

    async def run_write(
        self,
        name: str,
        arguments: dict[str, Any],
        *,
        path: str,
        payload: dict[str, Any],
    ) -> str:
        """写工具的唯一运行入口：风险注册、确认动作和下游幂等键缺一不可。"""
        policy = TOOL_POLICIES.get(name)
        if policy is None or policy.effect != "WRITE":
            raise BusinessToolError("write_tool_policy_required")
        if not self.action_id or not self.turn_id:
            raise BusinessToolError("write_tool_confirmation_required")
        if self.business_tool_client is None:
            raise BusinessToolError("business_tool_unconfigured")
        return await self.run(
            name,
            arguments,
            lambda: self.business_tool_client.execute_write(
                path,
                payload,
                action_id=self.action_id or "",
                turn_id=self.turn_id or "",
            ),
        )

    def _tool_call_id(
        self, name: str, arguments: dict[str, Any]
    ) -> str | None:
        if not self.turn_id:
            return None
        canonical = json.dumps(
            arguments,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        identity = f"{self.call_namespace}:{name}:{canonical}"
        sequence = self._call_counts.get(identity, 0)
        self._call_counts[identity] = sequence + 1
        digest = hashlib.sha256(
            f"{self.turn_id}:{identity}:{sequence}".encode("utf-8")
        ).hexdigest()
        return f"tool-{digest}"


@dataclass(frozen=True, slots=True)
class ToolPolicy:
    effect: str
    risk_level: str
    requires_confirmation: bool


# 风险级别只允许由服务端代码定义；模型参数不能覆盖该注册表。
TOOL_POLICIES: dict[str, ToolPolicy] = {
    "get_scope_context": ToolPolicy("READ", "LOW", False),
    "search_approved_resources": ToolPolicy("READ", "LOW", False),
    "retrieve_knowledge": ToolPolicy("READ", "LOW", False),
    "query_graph_relations": ToolPolicy("READ", "LOW", False),
}


def write_tool_interrupts(enabled: bool) -> dict[str, dict[str, Any]]:
    if not enabled:
        return {}
    return {
        name: {"allowed_decisions": ["approve", "reject"]}
        for name, policy in TOOL_POLICIES.items()
        if policy.effect == "WRITE" and policy.requires_confirmation
    }


def validate_tool_policies() -> None:
    registered = {str(item.name) for item in AGENT_TOOLS}
    missing = registered - TOOL_POLICIES.keys()
    if missing:
        raise RuntimeError(
            "tools without an explicit server-side policy: " + ", ".join(sorted(missing))
        )
    unsafe = [
        name
        for name, policy in TOOL_POLICIES.items()
        if policy.effect == "WRITE"
        and policy.risk_level == "HIGH"
        and not policy.requires_confirmation
    ]
    if unsafe:
        raise RuntimeError("high-risk write tools must require confirmation")


def _sanitize(arguments: dict[str, Any]) -> dict[str, Any]:
    return {
        key: str(value)[:500]
        for key, value in arguments.items()
        if "key" not in key.lower() and "token" not in key.lower()
    }


def _output_summary(result: Any, bounded: str) -> str:
    if isinstance(result, list):
        return f"返回 {len(result)} 条结果"
    if isinstance(result, dict):
        counts = [
            f"{key}: {len(value)}"
            for key, value in result.items()
            if isinstance(value, list)
        ]
        if counts:
            return "，".join(counts)
    if not bounded:
        return "未返回结果"
    return bounded[:160]


def _is_degraded_result(result: Any) -> bool:
    return (
        isinstance(result, dict)
        and str(result.get("retrievalStatus", "")).lower() == "degraded"
    )


def _tool_payload(
    runtime: ToolRuntimeContext, query: str, limit: int
) -> dict[str, Any]:
    return {
        "actor": runtime.trusted_context.actor,
        "scope": runtime.trusted_context.scope,
        "query": query.strip(),
        "grade": runtime.grade,
        "theme": runtime.theme,
        "topK": limit,
    }


def _merge_items(
    existing: list[dict[str, Any]], incoming: Any, limit: int
) -> list[dict[str, Any]]:
    values: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in [*(incoming if isinstance(incoming, list) else []), *existing]:
        if not isinstance(item, dict):
            continue
        identity = str(
            item.get("citationId")
            or item.get("id")
            or json.dumps(
                item, ensure_ascii=False, sort_keys=True, default=str
            )
        )
        if identity in seen:
            continue
        seen.add(identity)
        values.append(item)
        if len(values) >= limit:
            break
    return values


def _merge_retrieval(
    runtime: ToolRuntimeContext, result: dict[str, Any]
) -> None:
    retrieval = dict(runtime.trusted_context.retrieval or {})
    for key in ("chunks", "graphFacts"):
        retrieval[key] = _merge_items(
            retrieval.get(key, []), result.get(key), 8
        )
    if result.get("retrievalStatus") is not None:
        retrieval["retrievalStatus"] = result["retrievalStatus"]
    if result.get("degradedReason"):
        retrieval["degradedReason"] = result["degradedReason"]
        reason = str(result["degradedReason"])
        if reason not in runtime.degraded_reasons:
            runtime.degraded_reasons.append(reason)
    runtime.trusted_context.retrieval = retrieval
    runtime.trusted_context.citation_candidates = _merge_items(
        runtime.trusted_context.citation_candidates,
        result.get("citationCandidates"),
        8,
    )


def _fallback_retrieval(
    runtime: ToolRuntimeContext,
    query: str,
    limit: int,
    error: BusinessToolError,
) -> dict[str, Any]:
    retrieval = runtime.trusted_context.retrieval or {}
    result = {
        "retrievalStatus": "degraded",
        "degradedReason": error.reason,
        "chunks": [
            item
            for item in retrieval.get("chunks", [])
            if _matches(item, query)
        ][:limit],
        "graphFacts": [
            item
            for item in retrieval.get("graphFacts", [])
            if _matches(item, query)
        ][:limit],
        "citationCandidates": [
            item
            for item in runtime.trusted_context.citation_candidates
            if _matches(item, query)
        ][:limit],
    }
    _merge_retrieval(runtime, result)
    return result


_runtime: ContextVar[ToolRuntimeContext | None] = ContextVar(
    "agent_tool_runtime", default=None
)


def bind_tool_runtime(runtime: ToolRuntimeContext) -> Token:
    return _runtime.set(runtime)


def reset_tool_runtime(token: Token) -> None:
    _runtime.reset(token)


def require_runtime() -> ToolRuntimeContext:
    runtime = _runtime.get()
    if runtime is None:
        raise RuntimeError("tool runtime is not bound")
    return runtime


@tool
async def get_scope_context() -> str:
    """Return the authenticated school, region, or resource context for this conversation."""
    runtime = require_runtime()
    return await runtime.run(
        "get_scope_context",
        {},
        lambda: {
            "school": runtime.trusted_context.school,
            "region": runtime.trusted_context.region,
            "resource": runtime.trusted_context.resource,
        },
    )


@tool
async def search_approved_resources(query: str = "", limit: int = 5) -> str:
    """Search only the approved resources supplied by the authenticated business service."""
    runtime = require_runtime()
    safe_limit = max(1, min(limit, 8))
    return await runtime.run(
        "search_approved_resources",
        {"query": query, "limit": safe_limit},
        lambda: [
            item
            for item in runtime.trusted_context.resources
            if _matches(item, query)
        ][:safe_limit],
    )


@tool
async def retrieve_knowledge(query: str = "", limit: int = 5) -> str:
    """Retrieve trusted RAG chunks and citation candidates through the business service."""
    runtime = require_runtime()
    safe_limit = max(1, min(limit, 8))

    async def retrieve() -> dict[str, Any]:
        try:
            if runtime.business_tool_client is None:
                raise BusinessToolError("business_tool_unconfigured")
            result = await runtime.business_tool_client.query_knowledge(
                _tool_payload(runtime, query, safe_limit)
            )
            _merge_retrieval(runtime, result)
            return result
        except BusinessToolError as error:
            return _fallback_retrieval(runtime, query, safe_limit, error)

    return await runtime.run(
        "retrieve_knowledge",
        {"query": query, "limit": safe_limit},
        retrieve,
    )


@tool
async def query_graph_relations(query: str = "", limit: int = 5) -> str:
    """Retrieve graph facts through the authenticated business service."""
    runtime = require_runtime()
    safe_limit = max(1, min(limit, 8))

    async def retrieve() -> dict[str, Any]:
        try:
            if runtime.business_tool_client is None:
                raise BusinessToolError("business_tool_unconfigured")
            result = await runtime.business_tool_client.query_graph_relations(
                _tool_payload(runtime, query, safe_limit)
            )
            _merge_retrieval(runtime, result)
            return result
        except BusinessToolError as error:
            return _fallback_retrieval(runtime, query, safe_limit, error)

    return await runtime.run(
        "query_graph_relations",
        {"query": query, "limit": safe_limit},
        retrieve,
    )


AGENT_TOOLS = [
    get_scope_context,
    search_approved_resources,
    retrieve_knowledge,
    query_graph_relations,
]

validate_tool_policies()
