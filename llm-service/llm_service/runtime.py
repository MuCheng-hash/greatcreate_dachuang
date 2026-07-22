from __future__ import annotations

import json
from typing import Any

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from .memory import ContextWindowManager
from .model_gateway import ModelGateway, message_text
from .planner import AgentPlan, AgentPlanner
from .repository import ConversationRepository, ThreadRecord
from .schemas import AgentMessageRequest, AgentMessageResponse, AgentModelOutput, Citation, ToolExecution, TrustedContext
from .settings import Settings
from .tools import AGENT_TOOLS, ToolRuntimeContext, bind_tool_runtime, reset_tool_runtime


SYSTEM_PROMPT = """你是红韵乡途学校思政教育助手。
你只能使用已注册工具提供的学校、区域、资源、检索和图谱事实，不得编造事实、来源、引用 ID 或访问其他范围的数据。
需要资料时主动选择合适工具；工具返回的 citationId 才可以作为引用。
最终只输出 JSON 对象，不要 Markdown，格式：
{"answer":"...","citationIds":[],"relatedResources":[],"followUpQuestions":[]}
如果证据不足，请明确说明，不要猜测。
"""


class AgentRuntime:
    def __init__(self, settings: Settings, repository: ConversationRepository, model: ModelGateway | None = None):
        self.settings = settings
        self.repository = repository
        self.model = model or ModelGateway(settings)
        self.context_manager = ContextWindowManager(
            settings.agent_context_token_budget,
            settings.agent_recent_message_count,
            settings.agent_summary_character_limit,
        )
        self.planner = AgentPlanner(settings.agent_max_tool_rounds)
        self._agent = create_agent(
            self.model.chat_model,
            tools=AGENT_TOOLS,
            system_prompt=SYSTEM_PROMPT,
        ) if self.model.chat_model is not None else None

    async def handle(self, request: AgentMessageRequest) -> AgentMessageResponse:
        thread = self._get_or_create_thread(request)
        self.repository.append_message(thread.thread_id, "user", request.message, {"intent": request.intent})
        stored = self.repository.list_messages(thread.thread_id)
        window = self.context_manager.build(stored, thread.summary)
        if window.compacted:
            self.repository.update_summary(thread.thread_id, window.summary)

        trusted = request.context
        plan = self.planner.plan(request.message)
        if self._agent is None:
            result = self._degraded_answer(request, trusted, thread.thread_id, window.compacted)
        else:
            result = await self._invoke_agent(request, trusted, thread, window.messages, window.summary, window.compacted, plan)
        self.repository.append_message(thread.thread_id, "assistant", result.answer, {
            "status": result.status,
            "citations": [item.citation_id for item in result.citations],
            "toolExecutions": [item.model_dump(by_alias=True) for item in result.tool_executions],
        })
        return result

    def create_thread(self, owner_id: str, scope_type: str, scope_id: str | int) -> ThreadRecord:
        return self.repository.create_thread(owner_id, scope_type, scope_id)

    def _get_or_create_thread(self, request: AgentMessageRequest) -> ThreadRecord:
        if request.thread_id:
            return self.repository.require_thread(request.thread_id, request.owner_id, request.scope_type, request.scope_id)
        return self.create_thread(request.owner_id, request.scope_type, request.scope_id)

    async def _invoke_agent(
        self, request: AgentMessageRequest, trusted: TrustedContext, thread: ThreadRecord,
        messages: list[dict[str, str]], summary: str, compacted: bool, plan: AgentPlan,
    ) -> AgentMessageResponse:
        lc_messages: list[Any] = []
        if summary:
            lc_messages.append(SystemMessage(content=f"较早对话摘要（仅作上下文，不是新事实）：\n{summary}"))
        lc_messages.append(SystemMessage(content=(
            "本轮策略计划：先完成目标，再按需调用推荐工具 "
            f"{', '.join(plan.recommended_tools)}；最多执行 {plan.max_tool_rounds} 轮工具调用。"
        )))
        for item in messages:
            lc_messages.append(HumanMessage(content=item["content"]) if item["role"] == "user" else AIMessage(content=item["content"]))
        runtime = ToolRuntimeContext(
            thread_id=thread.thread_id,
            trusted_context=trusted,
            repository=self.repository,
            output_character_limit=self.settings.agent_tool_output_character_limit,
        )
        token = bind_tool_runtime(runtime)
        try:
            result = await self._agent.ainvoke(
                {"messages": lc_messages},
                config={"recursion_limit": max(3, plan.max_tool_rounds * 2 + 3)},
            )
        except Exception:
            return self._degraded_answer(request, trusted, thread.thread_id, compacted, runtime.executions, status="degraded")
        finally:
            reset_tool_runtime(token)
        parsed = self._parse_model_output(result)
        allowed = self._allowed_citations(trusted)
        citations = [self._citation_by_id(trusted, item) for item in parsed.citation_ids if item in allowed]
        citations = [item for item in citations if item is not None]
        answer = parsed.answer.strip() or "暂时无法生成有效回答。"
        status = "incomplete" if not parsed.answer.strip() else "completed"
        return AgentMessageResponse(
            threadId=thread.thread_id, answer=answer, status=status, citations=citations,
            relatedResources=parsed.related_resources[:8], followUpQuestions=parsed.follow_up_questions[:4],
            toolExecutions=runtime.executions, contextCompacted=compacted,
        )

    def _parse_model_output(self, result: dict[str, Any]) -> AgentModelOutput:
        messages = result.get("messages", []) if isinstance(result, dict) else []
        final_content = ""
        for message in reversed(messages):
            if isinstance(message, AIMessage):
                final_content = message_text(message.content).strip()
                if final_content:
                    break
        try:
            if final_content.startswith("```"):
                final_content = final_content.strip("`")
                if final_content.startswith("json"):
                    final_content = final_content[4:].lstrip()
            return AgentModelOutput.model_validate(json.loads(final_content))
        except (ValueError, TypeError, json.JSONDecodeError):
            return AgentModelOutput(answer=final_content or "暂时无法生成有效回答。")

    def _degraded_answer(
        self, request: AgentMessageRequest, trusted: TrustedContext, thread_id: str,
        compacted: bool, executions: list[ToolExecution] | None = None, status: str = "degraded",
    ) -> AgentMessageResponse:
        names = []
        for item in trusted.resources[:5]:
            resource = item.get("resource") if isinstance(item.get("resource"), dict) else item
            name = resource.get("resourceName") or resource.get("name")
            if name:
                names.append(str(name))
        scope_name = (trusted.school or {}).get("schoolName") or (trusted.region or {}).get("name") or "当前范围"
        if names:
            answer = f"当前模型不可用，先基于{scope_name}已审核的资源给出参考：{ '、'.join(names) }。围绕“{request.message}”，建议优先核对资源开放状态、适用年级和安全条件。"
        else:
            answer = f"当前模型不可用，且{scope_name}没有足够的已审核证据回答“{request.message}”。请补充资源或稍后重试。"
        citations = [self._citation_by_id(trusted, item) for item in self._allowed_citations(trusted)]
        return AgentMessageResponse(
            threadId=thread_id, answer=answer, status=status,
            citations=[item for item in citations[:5] if item is not None], relatedResources=names,
            followUpQuestions=["请介绍一个具体资源的教育价值。", "这些资源适合哪个年级？"],
            toolExecutions=executions or [], contextCompacted=compacted,
        )

    def _allowed_citations(self, trusted: TrustedContext) -> set[str]:
        values = {str(item.get("citationId")) for item in trusted.citation_candidates if item.get("citationId")}
        retrieval = trusted.retrieval or {}
        for group in (retrieval.get("chunks", []), retrieval.get("graphFacts", [])):
            values.update(str(item.get("citationId")) for item in group if isinstance(item, dict) and item.get("citationId"))
        return values

    def _citation_by_id(self, trusted: TrustedContext, citation_id: str) -> Citation | None:
        candidates = list(trusted.citation_candidates)
        retrieval = trusted.retrieval or {}
        candidates.extend(retrieval.get("chunks", []))
        candidates.extend(retrieval.get("graphFacts", []))
        for item in candidates:
            if not isinstance(item, dict) or str(item.get("citationId")) != citation_id:
                continue
            return Citation(
                citationId=citation_id,
                title=item.get("title") or ("图谱关系事实" if item.get("text") else None),
                excerpt=item.get("excerpt") or item.get("text"),
                sourceType=item.get("sourceType") or item.get("retrievalMethod"),
                score=item.get("score"),
            )
        return None
