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
    """将用于本地匹配的值规整为不区分大小写的单行文本。"""
    return " ".join(str(value or "").lower().split())


def _matches(item: dict[str, Any], query: str) -> bool:
    """判断注入的可信资源是否包含查询中的全部词项或完整查询串。"""
    if not query.strip():
        # 空查询代表调用方请求当前范围内的资源，不施加额外筛选条件。
        return True
    # 保留中文字符本身，避免序列化转义破坏本地中文检索。
    haystack = json.dumps(item, ensure_ascii=False).lower()
    terms = [term for term in _text(query).split(" ") if term]
    return all(term in haystack for term in terms) or _text(query) in haystack


@dataclass(slots=True)
class ToolRuntimeContext:
    """单个 Agent 轮次的工具执行上下文与审计边界。

    ``trusted_context`` 只能来自已认证的请求准备阶段；工具可以读取其中的降级
    证据，却不能以模型参数替换范围。所有工具结果均通过本对象登记审计记录，
    以支持断线恢复时的幂等回放。
    """
    thread_id: str
    trusted_context: TrustedContext
    repository: ConversationRepository
    output_character_limit: int
    turn_id: str | None = None
    action_id: str | None = None
    call_namespace: str = "graph"
    executions: list[ToolExecution] = field(default_factory=list)
    event_sink: Callable[[str, dict[str, Any]], Awaitable[None]] | None = None
    business_tool_client: BusinessToolClient | None = None
    grade: str | None = None
    theme: str | None = None
    resource_category: str | None = None
    max_distance_meters: int | None = None
    degraded_reasons: list[str] = field(default_factory=list)
    _call_counts: dict[str, int] = field(default_factory=dict)

    async def _emit(self, event_name: str, data: dict[str, Any]) -> None:
        """尽力发送工具生命周期事件；展示通道故障不得中断业务执行。"""
        if self.event_sink is None:
            return
        try:
            await self.event_sink(event_name, data)
        except Exception:
            return

    async def run(
        self,
        name: str,
        arguments: dict[str, Any],
        callback: Callable[[], Any | Awaitable[Any]],
    ) -> str:
        """执行只读工具，持久化受限结果摘要并在同一轮次中复用既有审计结果。

        ``callback`` 可同步或异步。回调异常被记录为失败摘要而不向模型泄露内部
        堆栈；同一 ``tool_call_id`` 的非失败记录直接回放，避免断线恢复再次访问
        外部服务。返回文本始终受 ``output_character_limit`` 约束。
        """
        started = time.perf_counter()
        # 持久化前先脱敏参数；原始输入仅用于本次实际工具调用，不进入审计表。
        safe_arguments = _sanitize(arguments)
        tool_call_id = self._tool_call_id(name, safe_arguments)
        await self._emit(
            "tool.started",
            {"toolName": name, "name": name, "arguments": safe_arguments},
        )
        if self.turn_id and tool_call_id:
            # 已完成或降级的调用是该轮次的既定事实，恢复时不能再次产生外部副作用。
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
                await self._emit(
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
                # 同步和异步工具使用同一审计与异常边界，避免调用方自行处理执行方式。
                result = await result
            if _is_degraded_result(result):
                status = "degraded"
            output = json.dumps(result, ensure_ascii=False, default=str)
        except Exception as exc:
            # 工具错误仅以异常类型进入模型可见结果，避免堆栈或业务服务响应正文泄露。
            status = "failed"
            output = json.dumps(
                {"error": type(exc).__name__}, ensure_ascii=False
            )
        duration_ms = int((time.perf_counter() - started) * 1000)
        bounded = output[: self.output_character_limit]
        # 输出长度限制同时保护审计表和后续提示词上下文，不改变实际业务服务的完整返回。
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
        await self._emit(
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
            raise BusinessToolError("写入工具必须配置策略")
        if not self.action_id or not self.turn_id:
            raise BusinessToolError("写入工具需要用户确认")
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
        """根据轮次、规范参数和顺序生成稳定且不泄露原始参数的审计键。"""
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
    """服务端固定的工具副作用与确认策略，模型不能覆盖该策略。"""
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
    """为需要人工确认的写工具生成 LangGraph 中断配置。"""
    if not enabled:
        return {}
    return {
        name: {"allowed_decisions": ["approve", "reject"]}
        for name, policy in TOOL_POLICIES.items()
        if policy.effect == "WRITE" and policy.requires_confirmation
    }


def validate_tool_policies() -> None:
    """在启动期确保每个注册工具都有服务端策略且高风险写入必须确认。"""
    registered = {str(item.name) for item in AGENT_TOOLS}
    missing = registered - TOOL_POLICIES.keys()
    if missing:
        raise RuntimeError(
            "以下工具未配置显式服务端策略：" + ", ".join(sorted(missing))
        )
    unsafe = [
        name
        for name, policy in TOOL_POLICIES.items()
        if policy.effect == "WRITE"
        and policy.risk_level == "HIGH"
        and not policy.requires_confirmation
    ]
    if unsafe:
        raise RuntimeError("高风险写入工具必须要求确认")


def _sanitize(arguments: dict[str, Any]) -> dict[str, Any]:
    """截断审计参数并排除疑似密钥或令牌字段，降低持久化泄露风险。"""
    return {
        key: str(value)[:500]
        for key, value in arguments.items()
        if "key" not in key.lower() and "token" not in key.lower()
    }


def _output_summary(result: Any, bounded: str) -> str:
    """生成可展示的工具结果摘要，优先使用计数而不是完整正文。"""
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
    """识别业务检索明确标注的降级响应，供审计和 SSE 状态展示。"""
    return (
        isinstance(result, dict)
        and str(result.get("retrievalStatus", "")).lower() == "degraded"
    )


def _tool_payload(
    runtime: ToolRuntimeContext, query: str, limit: int
) -> dict[str, Any]:
    """组装业务工具载荷，只携带运行时注入的认证范围与受限检索条件。"""
    return {
        "actor": runtime.trusted_context.actor,
        "scope": runtime.trusted_context.scope,
        "query": query.strip(),
        "grade": runtime.grade,
        "theme": runtime.theme,
        "resourceCategory": runtime.resource_category,
        "maxDistanceMeters": runtime.max_distance_meters,
        "topK": limit,
    }


def _merge_items(
    existing: list[dict[str, Any]], incoming: Any, limit: int
) -> list[dict[str, Any]]:
    """按引用标识稳定去重并截断证据，保证新检索结果优先于既有上下文。"""
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
            # 同一引用可能从向量和图谱路径同时返回，只保留优先来源的第一份。
            continue
        seen.add(identity)
        values.append(item)
        if len(values) >= limit:
            break
    return values


def _merge_retrieval(
    runtime: ToolRuntimeContext, result: dict[str, Any]
) -> None:
    """将业务检索结果合并到可信上下文，并记录供回答层展示的降级原因。"""
    retrieval = dict(runtime.trusted_context.retrieval or {})
    for key in ("chunks", "graphFacts"):
        retrieval[key] = _merge_items(
            retrieval.get(key, []), result.get(key), 8
        )
    if result.get("retrievalStatus") is not None:
        # 检索状态只在业务服务明确给出时覆盖，避免本地合并把未知状态伪装为正常。
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
    """业务检索失败时，只从本轮已认证上下文构造受限的降级结果。

    该函数不会访问持久化库或扩大范围，因此“服务不可用”和“无命中”能够被上层
    通过 ``retrievalStatus`` 与 ``degradedReasons`` 明确区分。
    """
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
    """将轮次工具上下文绑定到当前异步调用链，并返回可恢复的上下文令牌。"""
    return _runtime.set(runtime)


def reset_tool_runtime(token: Token) -> None:
    """恢复绑定前的工具上下文，避免并发请求之间串用可信范围。"""
    _runtime.reset(token)


def require_runtime() -> ToolRuntimeContext:
    """取得当前轮次上下文；在脱离 Agent 执行链调用工具时快速失败。"""
    runtime = _runtime.get()
    if runtime is None:
        raise RuntimeError("工具运行时未绑定")
    return runtime


@tool
async def get_scope_context() -> str:
    """返回当前对话已认证的学校、区域或资源上下文。

    结果仅取自请求准备阶段的 ``trusted_context``，用于约束模型而非让模型自行
    推断或扩大可访问范围。
    """
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
    """在当前请求已注入的已审核资源中执行本地只读检索。

    这是确定性的可信上下文查询，不会调用业务服务；``limit`` 被限制在 1 至 8，
    防止模型以过大结果污染后续提示词。
    """
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
    """优先通过业务服务检索可信 RAG 片段和引用候选。

    业务边界不可用时只回退到本轮 ``trusted_context``，并在结果中保留降级标记；
    不将服务故障伪装成“没有检索结果”。
    """
    runtime = require_runtime()
    safe_limit = max(1, min(limit, 8))

    async def retrieve() -> dict[str, Any]:
        """将业务知识检索和范围受限降级封装为同一工具回调。"""
        try:
            if runtime.business_tool_client is None:
                # 客户端未配置与“没有知识命中”不同，必须触发带原因的本地降级。
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
    """优先通过已认证的业务服务检索图谱事实，失败时采用范围受限的本地回退。"""
    runtime = require_runtime()
    safe_limit = max(1, min(limit, 8))

    async def retrieve() -> dict[str, Any]:
        """将业务图谱检索和范围受限降级封装为同一工具回调。"""
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
