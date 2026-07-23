from __future__ import annotations

import asyncio
import json
import logging
import re
import uuid
from collections.abc import AsyncIterator, Callable
from typing import Any

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, SystemMessage

from .memory import ContextWindowManager
from .model_gateway import ModelGateway, message_text
from .planner import AgentPlan, AgentPlanner
from .repository import ConversationRepository, ThreadRecord
from .schemas import AgentMessageRequest, AgentMessageResponse, AgentModelOutput, Citation, ToolExecution, TrustedContext
from .settings import ModelConfig, Settings
from .tools import AGENT_TOOLS, ToolRuntimeContext, bind_tool_runtime, reset_tool_runtime


LOGGER = logging.getLogger("llm.stateful_agent")
EventSink = Callable[[str, dict[str, Any]], None]


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
        # Tests and compatibility callers may inject one agent here. Normal
        # requests build an agent from each configured model in model_chain().
        self._agent: Any | None = None
        self._agents: dict[tuple[str, int], Any] = {}

    async def handle(self, request: AgentMessageRequest) -> AgentMessageResponse:
        thread, window, plan = self._prepare_turn(request)
        result = await self._run_agent_turn(request, thread, window.messages, window.summary, window.compacted, plan)
        self._persist_response(thread, result)
        return result

    async def stream_events(self, request: AgentMessageRequest) -> AsyncIterator[str]:
        queue: asyncio.Queue[tuple[str, dict[str, Any]] | None] = asyncio.Queue()
        run_id = str(uuid.uuid4())

        def publish(event_name: str, data: dict[str, Any] | None = None) -> None:
            payload = {"runId": run_id}
            if data:
                payload.update(data)
            queue.put_nowait((event_name, payload))

        async def worker() -> None:
            try:
                thread, window, plan = self._prepare_turn(request)
                result = await self._stream_agent_turn(
                    request, run_id, thread, window.messages, window.summary, window.compacted, plan, publish
                )
                self._persist_response(thread, result)
                publish("final", {"threadId": result.thread_id, "response": result.model_dump(by_alias=True)})
                publish("done")
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                LOGGER.exception("stateful_agent_stream_failed", extra={"runId": run_id})
                publish("error", {"errorType": type(exc).__name__, "message": str(exc)})
                publish("done")
            finally:
                queue.put_nowait(None)

        task = asyncio.create_task(worker())
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                event_name, data = item
                yield self._format_sse(event_name, data)
        finally:
            if not task.done():
                task.cancel()

    def create_thread(self, owner_id: str, scope_type: str, scope_id: str | int) -> ThreadRecord:
        return self.repository.create_thread(owner_id, scope_type, scope_id)

    def _prepare_turn(self, request: AgentMessageRequest) -> tuple[ThreadRecord, Any, AgentPlan]:
        thread = self._get_or_create_thread(request)
        self.repository.append_message(thread.thread_id, "user", request.message, {"intent": request.intent})
        stored = self.repository.list_messages(thread.thread_id)
        window = self.context_manager.build(stored, thread.summary)
        if window.compacted:
            self.repository.update_summary(thread.thread_id, window.summary)
        return thread, window, self.planner.plan(request.message)

    def _get_or_create_thread(self, request: AgentMessageRequest) -> ThreadRecord:
        if request.thread_id:
            return self.repository.require_thread(
                request.thread_id, request.owner_id, request.scope_type, request.scope_id
            )
        return self.create_thread(request.owner_id, request.scope_type, request.scope_id)

    def _model_attempts(self) -> list[tuple[ModelConfig, Any | None]]:
        if self._agent is not None:
            config = ModelConfig(
                provider="injected",
                model=self.settings.primary_model,
                base_url="",
                api_key="injected",
                fallback_level=0,
            )
            return [(config, self._agent)]
        return [(config, None) for config in self.model.model_configs()]

    def _create_agent_for(self, config: ModelConfig) -> Any:
        if not config.configured():
            raise RuntimeError("model_unavailable")
        key = (config.model, config.fallback_level)
        if key not in self._agents:
            self._agents[key] = create_agent(
                self.model.build_model(config),
                tools=AGENT_TOOLS,
                system_prompt=self._load_prompt(),
            )
        return self._agents[key]

    def _primary_model_config(self) -> ModelConfig:
        attempts = self._model_attempts()
        if attempts:
            return attempts[0][0]
        return ModelConfig(
            provider=self.settings.primary_provider or "openai-compatible",
            model=self.settings.primary_model,
            base_url=self.settings.primary_base_url,
            api_key=self.settings.primary_api_key,
            fallback_level=0,
        )

    def _with_model_metadata(
        self, response: AgentMessageResponse, config: ModelConfig, generation_status: str = "completed"
    ) -> AgentMessageResponse:
        response.generation_status = generation_status
        response.provider = config.provider
        response.model = config.model
        response.fallback_level = config.fallback_level
        return response

    async def _run_agent_turn(
        self,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        messages: list[dict[str, str]],
        summary: str,
        compacted: bool,
        plan: AgentPlan,
    ) -> AgentMessageResponse:
        executions: list[ToolExecution] = []
        for config, injected_agent in self._model_attempts():
            runtime = ToolRuntimeContext(
                thread_id=thread.thread_id,
                trusted_context=request.context,
                repository=self.repository,
                output_character_limit=self.settings.agent_tool_output_character_limit,
            )
            token = bind_tool_runtime(runtime)
            try:
                agent = injected_agent or self._create_agent_for(config)
                result = await self._invoke_agent(
                    request,
                    request.context,
                    thread,
                    messages,
                    summary,
                    compacted,
                    plan,
                    tool_runtime=runtime,
                    agent=agent,
                )
                return self._with_model_metadata(result, config)
            except Exception as exc:
                executions.extend(runtime.executions)
                LOGGER.warning(
                    "stateful_agent_model_failed",
                    extra={
                        "threadId": thread.thread_id,
                        "provider": config.provider,
                        "model": config.model,
                        "fallbackLevel": config.fallback_level,
                        "errorType": type(exc).__name__,
                    },
                )
            finally:
                reset_tool_runtime(token)

        return self._degraded_answer(
            request, request.context, thread.thread_id, compacted, executions, status="degraded"
        )

    async def _stream_agent_turn(
        self,
        request: AgentMessageRequest,
        run_id: str,
        thread: ThreadRecord,
        messages: list[dict[str, str]],
        summary: str,
        compacted: bool,
        plan: AgentPlan,
        emit: EventSink,
    ) -> AgentMessageResponse:
        primary = self._primary_model_config()
        emit(
            "run.started",
            {
                "threadId": thread.thread_id,
                "provider": primary.provider,
                "model": primary.model,
            },
        )
        executions: list[ToolExecution] = []
        for config, injected_agent in self._model_attempts():
            runtime = ToolRuntimeContext(
                thread_id=thread.thread_id,
                trusted_context=request.context,
                repository=self.repository,
                output_character_limit=self.settings.agent_tool_output_character_limit,
                event_sink=emit,
            )
            token = bind_tool_runtime(runtime)
            emit(
                "model.started",
                {
                    "provider": config.provider,
                    "model": config.model,
                    "fallbackLevel": config.fallback_level,
                },
            )
            try:
                agent = injected_agent or self._create_agent_for(config)
                result = await self._invoke_agent_stream(
                    request,
                    request.context,
                    thread,
                    messages,
                    summary,
                    compacted,
                    plan,
                    runtime,
                    emit,
                    agent=agent,
                )
                result = self._with_model_metadata(result, config)
                emit(
                    "model.completed",
                    {
                        "provider": config.provider,
                        "model": config.model,
                        "fallbackLevel": config.fallback_level,
                    },
                )
                return result
            except Exception as exc:
                executions.extend(runtime.executions)
                LOGGER.warning(
                    "stateful_agent_stream_model_failed",
                    extra={
                        "runId": run_id,
                        "provider": config.provider,
                        "model": config.model,
                        "fallbackLevel": config.fallback_level,
                        "errorType": type(exc).__name__,
                    },
                )
                emit(
                    "model.failed",
                    {
                        "provider": config.provider,
                        "model": config.model,
                        "fallbackLevel": config.fallback_level,
                        "errorType": type(exc).__name__,
                    },
                )
            finally:
                reset_tool_runtime(token)

        result = self._degraded_answer(
            request, request.context, thread.thread_id, compacted, executions, status="degraded"
        )
        self._emit_answer_chunks(result.answer, emit)
        return result

    def _persist_response(self, thread: ThreadRecord, result: AgentMessageResponse) -> None:
        self.repository.append_message(
            thread.thread_id,
            "assistant",
            result.answer,
            {
                "status": result.status,
                "citations": [item.citation_id for item in result.citations],
                "toolExecutions": [item.model_dump(by_alias=True) for item in result.tool_executions],
            },
        )

    async def _invoke_agent(
        self,
        request: AgentMessageRequest,
        trusted: TrustedContext,
        thread: ThreadRecord,
        messages: list[dict[str, str]],
        summary: str,
        compacted: bool,
        plan: AgentPlan,
        tool_runtime: ToolRuntimeContext | None = None,
        agent: Any | None = None,
    ) -> AgentMessageResponse:
        lc_messages = self._build_messages(messages, summary, plan)
        runtime = tool_runtime or ToolRuntimeContext(
            thread_id=thread.thread_id,
            trusted_context=trusted,
            repository=self.repository,
            output_character_limit=self.settings.agent_tool_output_character_limit,
        )
        target_agent = agent or self._agent
        if target_agent is None:
            raise RuntimeError("model_unavailable")
        result = await target_agent.ainvoke(
            {"messages": lc_messages},
            config={"recursion_limit": max(3, plan.max_tool_rounds * 2 + 3)},
        )
        return self._response_from_model_result(result, trusted, thread.thread_id, compacted, runtime.executions)

    async def _invoke_agent_stream(
        self,
        request: AgentMessageRequest,
        trusted: TrustedContext,
        thread: ThreadRecord,
        messages: list[dict[str, str]],
        summary: str,
        compacted: bool,
        plan: AgentPlan,
        runtime: ToolRuntimeContext,
        emit: EventSink,
        agent: Any | None = None,
    ) -> AgentMessageResponse:
        lc_messages = self._build_messages(messages, summary, plan)
        model_messages: list[Any] = []
        model_buffer = ""
        emitted_answer_length = 0
        target_agent = agent or self._agent
        if target_agent is None:
            raise RuntimeError("model_unavailable")
        if hasattr(target_agent, "astream"):
            async for chunk in target_agent.astream(
                {"messages": lc_messages},
                config={"recursion_limit": max(3, plan.max_tool_rounds * 2 + 3)},
                stream_mode="messages",
                version="v2",
            ):
                for message in self._stream_messages(chunk):
                    model_messages.append(message)
                    if not isinstance(message, (AIMessage, AIMessageChunk)):
                        continue
                    content = message_text(message.content)
                    if not content:
                        continue
                    model_buffer += content
                    partial_answer = self._partial_answer(model_buffer)
                    if partial_answer and len(partial_answer) > emitted_answer_length:
                        emit("token", {"delta": partial_answer[emitted_answer_length:]})
                        emitted_answer_length = len(partial_answer)
        else:
            result = await target_agent.ainvoke(
                {"messages": lc_messages},
                config={"recursion_limit": max(3, plan.max_tool_rounds * 2 + 3)},
            )
            model_messages = result.get("messages", []) if isinstance(result, dict) else []
            model_buffer = self._last_ai_message_text(model_messages)

        parse_messages = [AIMessage(content=model_buffer)] if model_buffer else model_messages
        response = self._response_from_model_result(
            {"messages": parse_messages}, trusted, thread.thread_id, compacted, runtime.executions
        )
        if emitted_answer_length < len(response.answer):
            self._emit_answer_chunks(response.answer[emitted_answer_length:], emit)
        return response

    def _build_messages(
        self, messages: list[dict[str, str]], summary: str, plan: AgentPlan
    ) -> list[Any]:
        lc_messages: list[Any] = []
        if summary:
            lc_messages.append(SystemMessage(content=f"较早对话摘要（仅作上下文，不是新事实）：\n{summary}"))
        lc_messages.append(SystemMessage(content=(
            "本轮策略计划：先完成目标，再按需调用推荐工具 "
            f"{', '.join(plan.recommended_tools)}；最多执行 {plan.max_tool_rounds} 轮工具调用。"
        )))
        for item in messages:
            lc_messages.append(
                HumanMessage(content=item["content"])
                if item["role"] == "user"
                else AIMessage(content=item["content"])
            )
        return lc_messages

    def _response_from_model_result(
        self,
        result: dict[str, Any],
        trusted: TrustedContext,
        thread_id: str,
        compacted: bool,
        executions: list[ToolExecution],
    ) -> AgentMessageResponse:
        parsed = self._parse_model_output(result)
        allowed = self._allowed_citations(trusted)
        citations = [self._citation_by_id(trusted, item) for item in parsed.citation_ids if item in allowed]
        citations = [item for item in citations if item is not None]
        answer = parsed.answer.strip()
        return AgentMessageResponse(
            threadId=thread_id,
            answer=answer or "暂时无法生成有效回答。",
            status="completed" if answer else "incomplete",
            generationStatus="completed" if answer else "degraded",
            retrievalStatus=self._retrieval_status(trusted),
            citations=citations,
            relatedResources=parsed.related_resources[:8],
            followUpQuestions=parsed.follow_up_questions[:4],
            toolExecutions=executions,
            contextCompacted=compacted,
        )

    def _parse_model_output(self, result: dict[str, Any]) -> AgentModelOutput:
        messages = result.get("messages", []) if isinstance(result, dict) else []
        final_content = ""
        for message in reversed(messages):
            if isinstance(message, (AIMessage, AIMessageChunk)):
                final_content = message_text(message.content).strip()
                if final_content:
                    break
        try:
            parsed = AgentModelOutput.model_validate(json.loads(self._strip_json_fence(final_content)))
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            raise ValueError("invalid_model_output") from exc
        if not parsed.answer.strip():
            raise ValueError("invalid_model_output")
        return parsed

    def _partial_answer(self, content: str) -> str:
        normalized = content.strip()
        if not normalized:
            return ""
        candidate = self._strip_json_fence(normalized)
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict) and isinstance(parsed.get("answer"), str):
                return parsed["answer"]
        except (ValueError, TypeError, json.JSONDecodeError):
            pass
        match = re.search(r'"answer"\s*:\s*"((?:\\.|[^"\\])*)', candidate)
        if match:
            try:
                return json.loads('"' + match.group(1) + '"')
            except (ValueError, json.JSONDecodeError):
                return match.group(1)
        return normalized if not normalized.startswith("{") else ""

    def _strip_json_fence(self, value: str) -> str:
        normalized = value.strip()
        if normalized.startswith("```"):
            normalized = normalized[3:]
            if normalized.startswith("json"):
                normalized = normalized[4:]
            if normalized.endswith("```"):
                normalized = normalized[:-3]
        return normalized.strip()

    def _stream_messages(self, chunk: Any) -> list[Any]:
        if isinstance(chunk, (AIMessage, AIMessageChunk)):
            return [chunk]
        if isinstance(chunk, tuple) and chunk:
            return [chunk[0]]
        if isinstance(chunk, dict):
            if "messages" in chunk and isinstance(chunk["messages"], list):
                return [chunk["messages"][-1]] if chunk["messages"] else []
            data = chunk.get("data")
            if isinstance(data, tuple) and data:
                return [data[0]]
            if isinstance(data, (AIMessage, AIMessageChunk)):
                return [data]
        return []

    def _last_ai_message_text(self, messages: list[Any]) -> str:
        for message in reversed(messages):
            if isinstance(message, (AIMessage, AIMessageChunk)):
                return message_text(message.content)
        return ""

    def _emit_answer_chunks(self, answer: str, emit: EventSink, size: int = 24) -> None:
        for index in range(0, len(answer), size):
            emit("token", {"delta": answer[index:index + size]})

    def _load_prompt(self) -> str:
        if not self.settings.prompt_path.is_file():
            raise FileNotFoundError(f"Agent prompt not found: {self.settings.prompt_path}")
        return self.settings.prompt_path.read_text(encoding="utf-8")

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
            generationStatus="degraded",
            retrievalStatus=self._retrieval_status(trusted),
            provider="local",
            model="local",
            fallbackLevel="local",
            citations=[item for item in citations[:5] if item is not None], relatedResources=names,
            followUpQuestions=["请介绍一个具体资源的教育价值。", "这些资源适合哪个年级？"],
            toolExecutions=executions or [], contextCompacted=compacted,
        )

    def _retrieval_status(self, trusted: TrustedContext) -> str:
        retrieval = trusted.retrieval or {}
        status = retrieval.get("retrievalStatus")
        if status:
            return str(status).lower()
        if retrieval.get("chunks") or retrieval.get("graphFacts") or trusted.citation_candidates:
            return "ok"
        return "empty"

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

    @staticmethod
    def _format_sse(event_name: str, data: dict[str, Any]) -> str:
        return f"event: {event_name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
