from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable

from langchain.agents import AgentState as LangChainAgentState
from langchain.tools import ToolRuntime, tool

LOGGER = logging.getLogger("llm.agent.tools")


class AgentToolError(RuntimeError):
    def __init__(self, error_type: str, message: str):
        super().__init__(message)
        self.error_type = error_type


class AgentState(LangChainAgentState):
    agent_context: dict[str, Any]
    tool_trace: list[dict[str, Any]]
    conversationId: str
    scope: dict[str, Any]
    intent: str
    lastToolResults: list[dict[str, Any]]
    citationCandidates: list[dict[str, Any]]


@dataclass
class JavaToolClient:
    base_url: str
    service_token: str
    timeout_seconds: float = 20.0

    def call(self, path: str, payload: dict[str, Any], run_id: str) -> dict[str, Any]:
        if not self.base_url:
            raise AgentToolError("tool_unconfigured", "业务服务地址未配置")
        request = urllib.request.Request(
            f"{self.base_url.rstrip('/')}{path}",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "X-Agent-Service-Token": self.service_token,
                "X-Agent-Run-Id": run_id,
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                raw = response.read().decode("utf-8")
                data = json.loads(raw) if raw else {}
            if not isinstance(data, dict):
                raise AgentToolError("invalid_tool_response", "业务工具返回格式错误")
            # Java public/internal controllers use ApiResponse. Unwrap it here
            # so the model sees the tool payload and token failures remain tool
            # errors instead of looking like successful business data.
            if "code" in data:
                if data.get("code") != 200:
                    raise AgentToolError(
                        "tool_http_error",
                        str(data.get("message") or "业务工具调用被拒绝"),
                    )
                inner = data.get("data")
                return inner if isinstance(inner, dict) else {"value": inner}
            return data
        except AgentToolError:
            raise
        except urllib.error.HTTPError as exc:
            raise AgentToolError("tool_http_error", f"业务工具 HTTP {exc.code}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise AgentToolError("tool_unavailable", "业务工具暂不可用") from exc
        except (TypeError, ValueError, json.JSONDecodeError) as exc:
            raise AgentToolError("invalid_tool_response", "业务工具返回 JSON 无法解析") from exc


def _context(runtime: ToolRuntime) -> dict[str, Any]:
    return runtime.state.get("agent_context") or {}


def _call_tool(
    name: str,
    runtime: ToolRuntime,
    client: JavaToolClient,
    payload: dict[str, Any],
    run_id: str,
    emit: Callable[[str, dict[str, Any]], None],
) -> str:
    started = time.perf_counter()
    emit("tool.started", {"toolName": name})
    try:
        result = client.call(name, payload, run_id)
        duration = round((time.perf_counter() - started) * 1000)
        LOGGER.info(
            "agent_tool_completed",
            extra={
                "runId": run_id,
                "toolName": name,
                "latencyMs": duration,
                "errorType": None,
            },
        )
        emit("tool.completed", {"toolName": name, "latencyMs": duration, "status": "ok"})
        trace = runtime.state.get("tool_trace") or []
        trace_entry = {"toolName": name, "status": "ok", "result": result}
        trace.append(trace_entry)
        runtime.state["tool_trace"] = trace
        last_results = runtime.state.get("lastToolResults") or []
        last_results.append(trace_entry)
        runtime.state["lastToolResults"] = last_results[-8:]
        if isinstance(result, dict):
            candidates = result.get("citationCandidates") or []
            if isinstance(candidates, list):
                existing = runtime.state.get("citationCandidates") or []
                existing.extend(item for item in candidates if isinstance(item, dict))
                runtime.state["citationCandidates"] = existing[-8:]
        return json.dumps({"status": "ok", "data": result}, ensure_ascii=False)
    except AgentToolError as exc:
        duration = round((time.perf_counter() - started) * 1000)
        tool_error_type = exc.error_type
        LOGGER.warning(
            "agent_tool_failed",
            extra={
                "runId": run_id,
                "toolName": name,
                "latencyMs": duration,
                "errorType": "tool_error",
                "toolErrorType": tool_error_type,
            },
        )
        emit(
            "tool.completed",
            {
                "toolName": name,
                "latencyMs": duration,
                "status": "error",
                "errorType": "tool_error",
                "toolErrorType": tool_error_type,
            },
        )
        trace = runtime.state.get("tool_trace") or []
        trace_entry = {
            "toolName": name,
            "status": "error",
            "errorType": "tool_error",
            "toolErrorType": tool_error_type,
        }
        trace.append(trace_entry)
        runtime.state["tool_trace"] = trace
        last_results = runtime.state.get("lastToolResults") or []
        last_results.append(trace_entry)
        runtime.state["lastToolResults"] = last_results[-8:]
        return json.dumps(
            {
                "status": "error",
                "errorType": "tool_error",
                "toolErrorType": tool_error_type,
                "message": str(exc),
            },
            ensure_ascii=False,
        )


def create_tools(
    client: JavaToolClient,
    run_id: str,
    emit: Callable[[str, dict[str, Any]], None],
) -> list[Any]:
    @tool
    def get_school_context(
        grade: str = "",
        theme: str = "",
        runtime: ToolRuntime = None,
    ) -> str:
        """查询当前授权学校的已审核资源和教学活动方案。"""
        context = _context(runtime)
        return _call_tool(
            "/internal/agent/tools/school-context",
            runtime,
            client,
            {
                "actor": context.get("actor", {}),
                "scope": context.get("scope", {}),
                "grade": grade or context.get("grade"),
                "theme": theme or context.get("theme"),
            },
            run_id,
            emit,
        )

    @tool
    def retrieve_knowledge(
        query: str = "",
        grade: str = "",
        theme: str = "",
        runtime: ToolRuntime = None,
    ) -> str:
        """检索当前范围内的内容分块、图谱事实和可追溯引用。"""
        context = _context(runtime)
        return _call_tool(
            "/internal/agent/tools/knowledge-retrieve",
            runtime,
            client,
            {
                "actor": context.get("actor", {}),
                "scope": context.get("scope", {}),
                "query": query or context.get("question", ""),
                "grade": grade or context.get("grade"),
                "theme": theme or context.get("theme"),
                "topK": context.get("topK", 5),
            },
            run_id,
            emit,
        )

    @tool
    def get_resource_detail(resource_id: int, runtime: ToolRuntime = None) -> str:
        """查询一个已审核本土教育资源的详细信息。"""
        context = _context(runtime)
        return _call_tool(
            "/internal/agent/tools/resource-detail",
            runtime,
            client,
            {
                "actor": context.get("actor", {}),
                "scope": context.get("scope", {}),
                "resourceId": resource_id,
            },
            run_id,
            emit,
        )

    @tool
    def query_relations(query: str = "", runtime: ToolRuntime = None) -> str:
        """查询学校、人物、事件和教育资源之间的受控关系事实。"""
        context = _context(runtime)
        return _call_tool(
            "/internal/agent/tools/relation-query",
            runtime,
            client,
            {
                "actor": context.get("actor", {}),
                "scope": context.get("scope", {}),
                "query": query or context.get("question", ""),
                "topK": context.get("topK", 5),
            },
            run_id,
            emit,
        )

    return [get_school_context, retrieve_knowledge, get_resource_detail, query_relations]
