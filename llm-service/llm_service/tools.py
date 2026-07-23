from __future__ import annotations

import json
import time
from contextvars import ContextVar, Token
from dataclasses import dataclass, field
from typing import Any, Callable

from langchain_core.tools import tool

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
    executions: list[ToolExecution] = field(default_factory=list)
    event_sink: Callable[[str, dict[str, Any]], None] | None = None

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
                "status": "ok" if status == "completed" else "failed",
                "durationMs": duration_ms,
            },
        )
        return bounded


def _sanitize(arguments: dict[str, Any]) -> dict[str, Any]:
    return {key: str(value)[:500] for key, value in arguments.items() if "key" not in key.lower() and "token" not in key.lower()}


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
    """Read trusted graph facts already retrieved for the authenticated scope."""
    runtime = require_runtime()
    safe_limit = max(1, min(limit, 8))
    return runtime.run(
        "query_graph_relations", {"query": query, "limit": safe_limit},
        lambda: [
            item for item in (runtime.trusted_context.retrieval or {}).get("graphFacts", [])
            if _matches(item, query)
        ][:safe_limit],
    )


AGENT_TOOLS = [get_scope_context, search_approved_resources, retrieve_knowledge, query_graph_relations]
