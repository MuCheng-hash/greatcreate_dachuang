from __future__ import annotations

import json
import time
from contextvars import ContextVar, Token
from dataclasses import dataclass, field
from typing import Any, Callable

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
    business_tool_client: BusinessToolClient | None = None
    grade: str | None = None
    theme: str | None = None
    executions: list[ToolExecution] = field(default_factory=list)
    event_sink: Callable[[str, dict[str, Any]], None] | None = None
    degraded_reasons: list[str] = field(default_factory=list)

    def _emit(self, event_name: str, data: dict[str, Any]) -> None:
        if self.event_sink is None:
            return
        try:
            self.event_sink(event_name, data)
        except Exception:
            # Streaming telemetry must never make a valid tool call fail.
            return

    def run(self, name: str, arguments: dict[str, Any], callback: Callable[[], Any]) -> str:
        started = time.perf_counter()
        self._emit("tool.started", {"toolName": name, "name": name})
        status = "completed"
        try:
            result = callback()
            output = json.dumps(result, ensure_ascii=False, default=str)
            if isinstance(result, dict) and str(result.get("retrievalStatus", "")).upper() == "DEGRADED":
                status = "degraded"
                reason = str(result.get("degradedReason") or "tool_degraded")
                if reason not in self.degraded_reasons:
                    self.degraded_reasons.append(reason)
        except Exception as exc:
            status = "failed"
            output = json.dumps({"error": type(exc).__name__}, ensure_ascii=False)
        duration_ms = int((time.perf_counter() - started) * 1000)
        bounded = output[: self.output_character_limit]
        self.repository.add_tool_audit(
            self.thread_id, name, _sanitize(arguments), status, duration_ms, bounded
        )
        self.executions.append(ToolExecution(name=name, status=status, durationMs=duration_ms))
        self._emit(
            "tool.completed",
            {
                "toolName": name,
                "name": name,
                "status": "ok" if status == "completed" else status,
                "durationMs": duration_ms,
            },
        )
        return bounded


def _sanitize(arguments: dict[str, Any]) -> dict[str, Any]:
    return {key: str(value)[:500] for key, value in arguments.items() if "key" not in key.lower() and "token" not in key.lower()}


def _merge_evidence(existing: list[Any], incoming: list[Any]) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in [*existing, *incoming]:
        if not isinstance(item, dict):
            continue
        citation_id = str(item.get("citationId") or "")
        identity = f"citation:{citation_id}" if citation_id else json.dumps(item, ensure_ascii=False, sort_keys=True)
        if identity in seen:
            continue
        seen.add(identity)
        merged.append(dict(item))
    return merged


def _merge_remote_evidence(runtime: ToolRuntimeContext, result: dict[str, Any]) -> None:
    retrieval = dict(runtime.trusted_context.retrieval or {})
    graph_facts = _merge_evidence(
        list(retrieval.get("graphFacts") or []),
        list(result.get("graphFacts") or []),
    )
    citation_candidates = _merge_evidence(
        list(runtime.trusted_context.citation_candidates or []),
        list(result.get("citationCandidates") or []),
    )
    retrieval["retrievalStatus"] = result.get("retrievalStatus", retrieval.get("retrievalStatus", "empty"))
    retrieval["graphFacts"] = graph_facts
    retrieval["citationCandidates"] = citation_candidates
    runtime.trusted_context.retrieval = retrieval
    runtime.trusted_context.citation_candidates = citation_candidates


_runtime: ContextVar[ToolRuntimeContext | None] = ContextVar("agent_tool_runtime", default=None)


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
def get_scope_context() -> str:
    """Return the authenticated school, region, or resource context for this conversation."""
    runtime = require_runtime()
    return runtime.run(
        "get_scope_context", {},
        lambda: {
            "school": runtime.trusted_context.school,
            "region": runtime.trusted_context.region,
            "resource": runtime.trusted_context.resource,
        },
    )


@tool
def search_approved_resources(query: str = "", limit: int = 5) -> str:
    """Search only the approved resources supplied by the authenticated business service."""
    runtime = require_runtime()
    safe_limit = max(1, min(limit, 8))
    return runtime.run(
        "search_approved_resources", {"query": query, "limit": safe_limit},
        lambda: [item for item in runtime.trusted_context.resources if _matches(item, query)][:safe_limit],
    )


@tool
def retrieve_knowledge(query: str = "", limit: int = 5) -> str:
    """Read trusted RAG chunks and citation candidates already retrieved for this scope."""
    runtime = require_runtime()
    safe_limit = max(1, min(limit, 8))

    def retrieve() -> dict[str, Any]:
        retrieval = runtime.trusted_context.retrieval or {}
        chunks = [item for item in retrieval.get("chunks", []) if _matches(item, query)][:safe_limit]
        candidates = [
            item for item in runtime.trusted_context.citation_candidates if _matches(item, query)
        ][:safe_limit]
        return {
            "retrievalStatus": retrieval.get("retrievalStatus", "empty"),
            "chunks": chunks,
            "citationCandidates": candidates,
        }

    return runtime.run("retrieve_knowledge", {"query": query, "limit": safe_limit}, retrieve)


@tool
def query_graph_relations(query: str = "", limit: int = 5) -> str:
    """Query graph relations through the authenticated Java business tool boundary."""
    runtime = require_runtime()
    safe_limit = max(1, min(limit, 8))

    def local_fallback(reason: str) -> dict[str, Any]:
        retrieval = runtime.trusted_context.retrieval or {}
        return {
            "source": "trusted-context-fallback",
            "retrievalStatus": "DEGRADED",
            "degradedReason": reason,
            "graphFacts": [
                item for item in retrieval.get("graphFacts", []) if _matches(item, query)
            ][:safe_limit],
            "citationCandidates": [
                item for item in runtime.trusted_context.citation_candidates if _matches(item, query)
            ][:safe_limit],
        }

    def retrieve() -> dict[str, Any]:
        if runtime.business_tool_client is None:
            return local_fallback("business_tool_client_unavailable")
        actor = runtime.trusted_context.actor
        scope = runtime.trusted_context.scope
        if not actor or not scope:
            return local_fallback("agent_actor_or_scope_missing")
        payload = {
            "actor": actor,
            "scope": scope,
            "query": query,
            "grade": runtime.grade,
            "theme": runtime.theme,
            "topK": safe_limit,
        }
        try:
            result = runtime.business_tool_client.query_graph_relations(payload)
        except BusinessToolError as exc:
            return local_fallback(exc.reason)
        result.setdefault("retrievalStatus", "EMPTY")
        result["graphFacts"] = list(result.get("graphFacts") or [])[:safe_limit]
        result["citationCandidates"] = list(result.get("citationCandidates") or [])[:safe_limit]
        _merge_remote_evidence(runtime, result)
        return result

    return runtime.run(
        "query_graph_relations", {"query": query, "limit": safe_limit},
        retrieve,
    )


AGENT_TOOLS = [get_scope_context, search_approved_resources, retrieve_knowledge, query_graph_relations]
