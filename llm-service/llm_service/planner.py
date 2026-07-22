from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class AgentPlan:
    objective: str
    recommended_tools: tuple[str, ...]
    max_tool_rounds: int


class AgentPlanner:
    """Small deterministic policy planner that constrains, but does not replace, LangGraph planning."""

    def __init__(self, max_tool_rounds: int):
        self.max_tool_rounds = max(1, max_tool_rounds)

    def plan(self, message: str) -> AgentPlan:
        normalized = message.replace(" ", "").lower()
        tools: list[str] = ["get_scope_context"]
        if any(term in normalized for term in ("附近", "周边", "资源", "适合", "年级")):
            tools.append("search_approved_resources")
        if any(term in normalized for term in ("资料", "依据", "为什么", "教育价值", "介绍")):
            tools.append("retrieve_knowledge")
        if any(term in normalized for term in ("关系", "关联", "人物", "事件")):
            tools.append("query_graph_relations")
        return AgentPlan(
            objective=message[:500],
            recommended_tools=tuple(dict.fromkeys(tools)),
            max_tool_rounds=self.max_tool_rounds,
        )
