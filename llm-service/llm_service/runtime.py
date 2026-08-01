from __future__ import annotations

import asyncio
import json
import logging
import re
import uuid
from collections.abc import AsyncIterator, Callable
from typing import Any

from fastapi.encoders import jsonable_encoder
from langchain.agents import create_agent
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, SystemMessage

from .memory import ContextWindowManager
from .model_gateway import ModelGateway, message_text
from .observability import FallbackAlertManager, LlmObservability, LlmTraceContext, classify_llm_error
from .planner import AgentPlan, AgentPlanner
from .prompt_manager import PromptManager
from .repository import ConversationRepository, ThreadRecord
from .schemas import (
    AgentMessageRequest,
    AgentMessageResponse,
    AgentModelOutput,
    Citation,
    MemoryApplied,
    MemoryItem,
    ToolExecution,
    TrustedContext,
)
from .settings import ModelConfig, Settings
from .user_memory import (
    ExplicitMemoryExtractor,
    MemoryContentPolicy,
    MemoryContext,
    MemoryRecord,
    MemoryRepository,
    MemoryValidationError,
)
from .structured_tasks import (
    IncrementalTeachingPlanParser,
    normalize_resource_discovery,
    normalize_teaching_plan,
    resource_discovery_fallback,
    resource_discovery_valid,
    structured_task_stream_text,
    task_answer,
    task_context,
    teaching_plan_fallback,
    teaching_plan_valid,
)
from .business_tool_client import BusinessToolClient
from .tools import (
    AGENT_TOOLS,
    ToolRuntimeContext,
    bind_tool_runtime,
    query_graph_relations,
    reset_tool_runtime,
    retrieve_knowledge,
)


LOGGER = logging.getLogger("llm.stateful_agent")
EventSink = Callable[[str, dict[str, Any]], None]


class AgentRuntime:
    def __init__(
        self,
        settings: Settings,
        repository: ConversationRepository,
        model: ModelGateway | None = None,
        observability: LlmObservability | None = None,
        alerts: FallbackAlertManager | None = None,
        prompts: PromptManager | None = None,
        business_tool_client: BusinessToolClient | None = None,
        memory_repository: MemoryRepository | None = None,
    ):
        self.settings = settings
        self.repository = repository
        self.observability = observability
        self.alerts = alerts or FallbackAlertManager(settings.llm_alert_webhook_url)
        self.model = model or ModelGateway(settings, observability, self.alerts)
        self.prompts = prompts
        self.business_tool_client = business_tool_client
        self.memory_repository = memory_repository or MemoryRepository(
            settings.database_path,
            content_policy=MemoryContentPolicy(
                settings.agent_memory_content_character_limit
            ),
            pending_days=settings.agent_memory_pending_days,
            task_days=settings.agent_memory_task_days,
            recycle_bin_days=settings.agent_memory_recycle_bin_days,
        )
        self.explicit_memory_extractor = ExplicitMemoryExtractor()
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
        thread, window, plan, memory_context = self._prepare_turn(request)
        result = await self._run_agent_turn(
            request,
            thread,
            window.messages,
            window.summary,
            window.compacted,
            plan,
            memory_context,
        )
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
                publish("phase.started", {"phase": "context", "label": "正在准备会话上下文"})
                thread, window, plan, memory_context = self._prepare_turn(request)
                publish("phase.completed", {
                    "phase": "context",
                    "label": "会话上下文已准备",
                    "compacted": window.compacted,
                })
                result = await self._stream_agent_turn(
                    request,
                    run_id,
                    thread,
                    window.messages,
                    window.summary,
                    window.compacted,
                    plan,
                    memory_context,
                    publish,
                )
                self._persist_response(thread, result)
                publish(
                    "final",
                    {
                        "threadId": result.thread_id,
                        "response": result.model_dump(by_alias=True, mode="json"),
                    },
                )
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

    def _prepare_turn(
        self, request: AgentMessageRequest
    ) -> tuple[ThreadRecord, Any, AgentPlan, MemoryContext]:
        thread = self._get_or_create_thread(request)
        self._capture_explicit_memory(request, thread)
        memory_context = self._memory_context_for(request)
        self.repository.append_message(
            thread.thread_id,
            "user",
            request.message,
            {"intent": request.intent, "taskType": request.task_type},
        )
        stored = self.repository.list_messages(thread.thread_id)
        window = self.context_manager.build(stored, thread.summary)
        if window.compacted:
            self.repository.update_summary(thread.thread_id, window.summary)
        return thread, window, self.planner.plan(request.message), memory_context

    def _get_or_create_thread(self, request: AgentMessageRequest) -> ThreadRecord:
        if request.thread_id:
            return self.repository.require_thread(
                request.thread_id, request.owner_id, request.scope_type, request.scope_id
            )
        return self.create_thread(request.owner_id, request.scope_type, request.scope_id)

    def _memory_context_for(self, request: AgentMessageRequest) -> MemoryContext:
        if not self.settings.agent_memory_enabled:
            return MemoryContext.empty()
        if request.task_type == "RESOURCE_DISCOVERY":
            return MemoryContext.empty()
        query_parts = [request.message, request.grade or "", request.theme or ""]
        if request.task_payload:
            query_parts.append(
                json.dumps(request.task_payload, ensure_ascii=False, default=str)
            )
        return self.memory_repository.recall(
            request.owner_id,
            request.scope_type,
            request.scope_id,
            query="\n".join(item for item in query_parts if item),
            task_limit=self.settings.agent_memory_task_limit,
            character_limit=self.settings.agent_memory_context_character_limit,
        )

    def _capture_explicit_memory(
        self, request: AgentMessageRequest, thread: ThreadRecord
    ) -> MemoryRecord | None:
        if not self.settings.agent_memory_enabled:
            return None
        if request.task_type == "RESOURCE_DISCOVERY":
            return None
        setting = self.memory_repository.get_setting(
            request.owner_id, request.scope_type, request.scope_id
        )
        if not setting.enabled:
            return None
        draft = self.explicit_memory_extractor.extract(request.message)
        if draft is None:
            return None
        return self.memory_repository.create_memory(
            request.owner_id,
            request.scope_type,
            request.scope_id,
            memory_type=draft.memory_type,
            field_key=draft.field_key,
            content=draft.content,
            status="active",
            source="explicit_chat",
            source_thread_id=thread.thread_id,
            confidence=1.0,
        )

    def _model_attempts(self, model_id: str | None = None) -> list[tuple[ModelConfig, Any | None]]:
        if self._agent is not None:
            config = ModelConfig(
                provider="injected",
                model=self.settings.primary_model,
                base_url="",
                api_key="injected",
                fallback_level=0,
            )
            return [(config, self._agent)]
        return [(config, None) for config in self.model.model_configs_for(model_id)]

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

    def _primary_model_config(self, model_id: str | None = None) -> ModelConfig:
        attempts = self._model_attempts(model_id)
        if attempts:
            return attempts[0][0]
        return ModelConfig(
            provider=(
                self.settings.agent_primary_provider
                or self.settings.primary_provider
                or self.settings.llm_provider
                or "openai-compatible"
            ),
            model=(
                self.settings.agent_primary_model
                or self.settings.primary_model
                or self.settings.llm_model
            ),
            base_url=(
                self.settings.agent_primary_base_url
                or self.settings.primary_base_url
                or self.settings.llm_api_url
                or self.settings.llm_base_url
            ),
            api_key=(
                self.settings.agent_primary_api_key
                or self.settings.primary_api_key
                or self.settings.llm_api_key
            ),
            fallback_level=0,
        )

    def _agent_invoke_config(
        self,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        config: ModelConfig,
        plan: AgentPlan,
    ) -> dict[str, Any]:
        invoke_config: dict[str, Any] = {
            "recursion_limit": max(3, plan.max_tool_rounds * 2 + 3),
        }
        if self.observability is not None:
            trace_context = LlmTraceContext(
                feature="stateful-agent",
                user_id=request.owner_id,
                session_id=thread.thread_id,
                expected_json=True,
                metadata={
                    "intent": request.intent or "",
                    "scopeType": request.scope_type,
                    "modelRole": "primary" if config.fallback_level == 0 else (
                        "fallback" if config.fallback_level == 1 else "lightweight"
                    ),
                    "fallbackLevel": config.fallback_level,
                },
            )
            invoke_config["callbacks"] = [
                self.observability.callback(trace_context, config.provider, config.model)
            ]
        return invoke_config

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
        memory_context: MemoryContext,
    ) -> AgentMessageResponse:
        if request.task_type != "CHAT":
            return await self._run_structured_task(
                request, thread, compacted, memory_context
            )
        prefetched_executions, prefetched_reasons = await self._prefetch_planned_tools(
            request, thread, plan
        )
        executions: list[ToolExecution] = list(prefetched_executions)
        model_attempts = self._model_attempts(request.model_id)
        for attempt_index, (config, injected_agent) in enumerate(model_attempts):
            runtime = ToolRuntimeContext(
                thread_id=thread.thread_id,
                trusted_context=request.context,
                repository=self.repository,
                output_character_limit=self.settings.agent_tool_output_character_limit,
                business_tool_client=self.business_tool_client,
                grade=request.grade,
                theme=request.theme,
                executions=list(prefetched_executions),
                degraded_reasons=list(prefetched_reasons),
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
                    memory_context,
                    tool_runtime=runtime,
                    agent=agent,
                    model_config=config,
                )
                return self._with_model_metadata(result, config)
            except Exception as exc:
                executions.extend(runtime.executions[len(prefetched_executions):])
                error_type = classify_llm_error(exc)
                LOGGER.warning(
                    "stateful_agent_model_failed",
                    extra={
                        "threadId": thread.thread_id,
                        "provider": config.provider,
                        "model": config.model,
                        "fallbackLevel": config.fallback_level,
                        "errorType": error_type,
                    },
                )
                next_config = model_attempts[attempt_index + 1][0] if attempt_index + 1 < len(model_attempts) else None
                if next_config is not None:
                    self.alerts.fallback(
                        LlmTraceContext(
                            feature="stateful-agent",
                            user_id=request.owner_id,
                            session_id=thread.thread_id,
                        ),
                        config.model,
                        next_config.model,
                        error_type,
                        next_config.fallback_level,
                    )
            finally:
                reset_tool_runtime(token)

        self.alerts.exhausted(
            LlmTraceContext(
                feature="stateful-agent",
                user_id=request.owner_id,
                session_id=thread.thread_id,
            ),
            [
                {
                    "provider": config.provider,
                    "model": config.model,
                    "fallbackLevel": config.fallback_level,
                    "status": "failed",
                }
                for config, _ in model_attempts
            ] or [{"status": "not_configured"}],
        )
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
        memory_context: MemoryContext,
        emit: EventSink,
    ) -> AgentMessageResponse:
        if request.task_type != "CHAT":
            return await self._stream_structured_task(
                request, thread, compacted, memory_context, emit
            )
        primary = self._primary_model_config(request.model_id)
        emit(
            "run.started",
            {
                "threadId": thread.thread_id,
                "provider": primary.provider,
                "model": primary.model,
            },
        )
        emit("phase.started", {
            "phase": "reasoning",
            "label": "正在分析问题并规划处理步骤",
            "recommendedTools": plan.recommended_tools,
        })
        prefetched_executions, prefetched_reasons = await self._prefetch_planned_tools(
            request, thread, plan, emit
        )
        executions: list[ToolExecution] = list(prefetched_executions)
        model_attempts = self._model_attempts(request.model_id)
        if not model_attempts:
            emit(
                "model.started",
                {
                    "provider": primary.provider,
                    "model": primary.model,
                    "fallbackLevel": primary.fallback_level,
                },
            )
            emit(
                "model.failed",
                {
                    "provider": primary.provider,
                    "model": primary.model,
                    "fallbackLevel": primary.fallback_level,
                    "errorType": "not_configured",
                },
            )
        for config, injected_agent in model_attempts:
            runtime = ToolRuntimeContext(
                thread_id=thread.thread_id,
                trusted_context=request.context,
                repository=self.repository,
                output_character_limit=self.settings.agent_tool_output_character_limit,
                business_tool_client=self.business_tool_client,
                grade=request.grade,
                theme=request.theme,
                event_sink=emit,
                executions=list(prefetched_executions),
                degraded_reasons=list(prefetched_reasons),
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
                emit("phase.completed", {
                    "phase": "reasoning",
                    "label": "分析完成，开始执行",
                    "recommendedTools": plan.recommended_tools,
                })
                emit("phase.started", {"phase": "response", "label": "正在生成回答"})
                result = await self._invoke_agent_stream(
                    request,
                    request.context,
                    thread,
                    messages,
                    summary,
                    compacted,
                    plan,
                    memory_context,
                    runtime,
                    emit,
                    agent=agent,
                    model_config=config,
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
                emit("phase.completed", {"phase": "response", "label": "回答生成完成"})
                return result
            except Exception as exc:
                executions.extend(runtime.executions[len(prefetched_executions):])
                LOGGER.warning(
                    "stateful_agent_stream_model_failed",
                    extra={
                        "runId": run_id,
                        "provider": config.provider,
                        "model": config.model,
                        "fallbackLevel": config.fallback_level,
                        "errorType": classify_llm_error(exc),
                    },
                )
                emit(
                    "model.failed",
                    {
                        "provider": config.provider,
                        "model": config.model,
                        "fallbackLevel": config.fallback_level,
                        "errorType": classify_llm_error(exc),
                    },
                )
            finally:
                reset_tool_runtime(token)

        if not model_attempts:
            self.alerts.exhausted(
                LlmTraceContext(
                    feature="stateful-agent-stream",
                    user_id=request.owner_id,
                    session_id=thread.thread_id,
                ),
                [{"status": "not_configured"}],
            )
        result = self._degraded_answer(
            request, request.context, thread.thread_id, compacted, executions, status="degraded"
        )
        await self._emit_answer_chunks(result.answer, emit)
        return result

    async def _prefetch_planned_tools(
        self,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        plan: AgentPlan,
        emit: EventSink | None = None,
    ) -> tuple[list[ToolExecution], list[str]]:
        """Run deterministic evidence retrieval before model generation.

        The model remains free to call tools itself, but a model that returns a
        final JSON answer without emitting a tool call must not bypass the
        authenticated business retrieval boundary.
        """
        if not request.context.actor or not request.context.scope:
            return [], []

        runtime = ToolRuntimeContext(
            thread_id=thread.thread_id,
            trusted_context=request.context,
            repository=self.repository,
            output_character_limit=self.settings.agent_tool_output_character_limit,
            business_tool_client=self.business_tool_client,
            grade=request.grade,
            theme=request.theme,
            event_sink=emit,
        )
        token = bind_tool_runtime(runtime)
        try:
            if "retrieve_knowledge" in plan.recommended_tools:
                retrieve_knowledge.invoke({"query": request.message, "limit": 5})
            if "query_graph_relations" in plan.recommended_tools:
                query_graph_relations.invoke({"query": request.message, "limit": 5})
        finally:
            reset_tool_runtime(token)
        return list(runtime.executions), list(runtime.degraded_reasons)

    async def _run_structured_task(
        self,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        compacted: bool,
        memory_context: MemoryContext,
    ) -> AgentMessageResponse:
        prompt_key, validator = self._structured_task_config(request)
        selection, run_id = self._start_structured_prompt(
            prompt_key, request, thread, memory_context
        )
        started = asyncio.get_running_loop().time()
        trace_context = LlmTraceContext(
            feature=f"stateful-{request.task_type.lower()}",
            user_id=request.owner_id,
            session_id=thread.thread_id,
            trace_id=run_id,
            expected_json=True,
            metadata={"taskType": request.task_type, "promptVersion": selection.version},
        )
        model_kwargs = {"model_id": request.model_id} if request.model_id else {}
        generated, metadata = await self.model.generate_json_with_metadata(
            selection.content, trace_context, validator, **model_kwargs
        )
        memory_candidates: list[MemoryItem] = []
        if generated is None:
            result = self._structured_fallback(request)
            status = "degraded"
            error_message = "model_unavailable_or_invalid_response"
        else:
            memory_candidates = self._persist_inferred_candidates(
                request,
                thread.thread_id,
                generated.get("memoryCandidates")
                if isinstance(generated.get("memoryCandidates"), list)
                else [],
                source="teaching_plan",
            )
            result = self._normalize_structured_result(generated, request)
            status = "completed"
            error_message = ""
        if request.task_type == "TEACHING_PLAN":
            result["promptVersion"] = selection.version
            result["promptRunId"] = run_id
            result["promptExperiment"] = selection.experiment_key
            result["promptVariant"] = selection.variant
        elapsed = round((asyncio.get_running_loop().time() - started) * 1000)
        self._finish_structured_prompt(run_id, status, elapsed, result, error_message)
        return self._structured_response(
            request,
            thread.thread_id,
            compacted,
            result,
            metadata,
            status,
            memory_context,
            memory_candidates,
        )

    async def _stream_structured_task(
        self,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        compacted: bool,
        memory_context: MemoryContext,
        emit: EventSink,
    ) -> AgentMessageResponse:
        prompt_key, validator = self._structured_task_config(request)
        selection, prompt_run_id = self._start_structured_prompt(
            prompt_key, request, thread, memory_context
        )
        primary = self._primary_model_config(request.model_id)
        emit(
            "run.started",
            {"threadId": thread.thread_id, "taskType": request.task_type, "provider": primary.provider, "model": primary.model},
        )
        started = asyncio.get_running_loop().time()
        trace_context = LlmTraceContext(
            feature=f"stateful-{request.task_type.lower()}-stream",
            user_id=request.owner_id,
            session_id=thread.thread_id,
            trace_id=prompt_run_id,
            expected_json=True,
            metadata={"taskType": request.task_type, "promptVersion": selection.version},
        )
        generated: dict[str, Any] | None = None
        memory_candidates: list[MemoryItem] = []
        metadata: dict[str, Any] = {}
        error_message = ""
        teaching_plan_parser = (
            IncrementalTeachingPlanParser()
            if request.task_type == "TEACHING_PLAN"
            else None
        )
        model_kwargs = {"model_id": request.model_id} if request.model_id else {}
        async for event_name, data in self.model.stream_json_events(
            selection.content, trace_context, validator, **model_kwargs
        ):
            if event_name == "attempt":
                metadata = dict(data)
                emit("model.started", data)
            elif event_name == "token":
                if teaching_plan_parser is not None:
                    for patch in teaching_plan_parser.feed(str(data.get("delta") or "")):
                        emit("plan.patch", {"patch": patch})
                # 其他结构化任务仍不向前端发送原始 JSON 分片。
                continue
            elif event_name == "fallback":
                error_message = str(data.get("errorType") or "model_failed")
                emit("model.failed", data)
            elif event_name == "complete":
                generated = data.get("result") if isinstance(data.get("result"), dict) else None
                metadata = {key: value for key, value in data.items() if key != "result"}
                emit("model.completed", metadata)
            elif event_name == "exhausted":
                error_message = "fallback_exhausted"

        if generated is None:
            result = self._structured_fallback(request)
            status = "degraded"
        else:
            memory_candidates = self._persist_inferred_candidates(
                request,
                thread.thread_id,
                generated.get("memoryCandidates")
                if isinstance(generated.get("memoryCandidates"), list)
                else [],
                source="teaching_plan",
            )
            result = self._normalize_structured_result(generated, request)
            status = "completed"
        if request.task_type == "TEACHING_PLAN":
            result["promptVersion"] = selection.version
            result["promptRunId"] = prompt_run_id
            result["promptExperiment"] = selection.experiment_key
            result["promptVariant"] = selection.variant
        if request.task_type != "TEACHING_PLAN":
            readable_text = structured_task_stream_text(request, result)
            if readable_text:
                await self._emit_answer_chunks(readable_text, emit)
        elapsed = round((asyncio.get_running_loop().time() - started) * 1000)
        self._finish_structured_prompt(prompt_run_id, status, elapsed, result, error_message)
        return self._structured_response(
            request,
            thread.thread_id,
            compacted,
            result,
            metadata,
            status,
            memory_context,
            memory_candidates,
        )

    def _structured_task_config(self, request: AgentMessageRequest):
        if request.task_type == "TEACHING_PLAN":
            return "teaching-plan", teaching_plan_valid
        if request.task_type == "RESOURCE_DISCOVERY":
            return "resource-discovery", resource_discovery_valid
        raise ValueError(f"unsupported taskType: {request.task_type}")

    def _start_structured_prompt(
        self,
        prompt_key: str,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        memory_context: MemoryContext,
    ):
        if self.prompts is None:
            raise RuntimeError("prompt_manager_unavailable")
        subject_key = f"{request.scope_type}:{request.scope_id}"
        selection = self.prompts.resolve(
            prompt_key,
            subject_key,
            task_context(request, memory_context.prompt),
        )
        run_id = self.prompts.start_run(
            selection, subject_key, self._primary_model_config(request.model_id).model, len(selection.content)
        )
        return selection, run_id

    def _finish_structured_prompt(
        self, run_id: str, status: str, elapsed: int, result: dict[str, Any], error_message: str
    ) -> None:
        if self.prompts is not None:
            self.prompts.finish_run(
                run_id, status, elapsed, len(json.dumps(result, ensure_ascii=False)), error_message
            )

    def _normalize_structured_result(
        self, result: dict[str, Any], request: AgentMessageRequest
    ) -> dict[str, Any]:
        if request.task_type == "TEACHING_PLAN":
            return normalize_teaching_plan(result, request)
        return normalize_resource_discovery(result, request)

    def _structured_fallback(self, request: AgentMessageRequest) -> dict[str, Any]:
        if request.task_type == "TEACHING_PLAN":
            return teaching_plan_fallback(request)
        return resource_discovery_fallback(request)

    def _structured_response(
        self,
        request: AgentMessageRequest,
        thread_id: str,
        compacted: bool,
        result: dict[str, Any],
        metadata: dict[str, Any],
        status: str,
        memory_context: MemoryContext,
        memory_candidates: list[MemoryItem] | None = None,
    ) -> AgentMessageResponse:
        response = AgentMessageResponse(
            threadId=thread_id,
            taskType=request.task_type,
            answer=task_answer(request, result),
            status=status,
            generationStatus=status,
            retrievalStatus=self._retrieval_status(request.context),
            provider=metadata.get("provider"),
            model=metadata.get("model"),
            fallbackLevel=metadata.get("fallbackLevel"),
            citations=self._task_citations(result, request.context),
            relatedResources=result.get("relatedResources") or [],
            followUpQuestions=self._follow_up_questions(
                result.get("followUpSuggestions"), result.get("relatedResources"), request.message,
                request.grade, request.theme,
            ),
            contextCompacted=compacted,
            memoryApplied=self._memory_applied(memory_context)
            if status == "completed"
            else None,
            memoryCandidates=memory_candidates or None,
        )
        if status == "degraded":
            response.provider = "local"
            response.model = "local"
            response.fallback_level = "local"
        if request.task_type == "TEACHING_PLAN":
            response.teaching_plan = result
        else:
            response.resource_discovery = result
        return response

    def _task_citations(self, result: dict[str, Any], trusted: TrustedContext) -> list[Citation]:
        allowed = self._allowed_citations(trusted)
        values = []
        for item in result.get("citations") or []:
            if isinstance(item, dict) and item.get("citationId") in allowed:
                values.append(self._citation_by_id(trusted, str(item["citationId"])))
        return [item for item in values if item is not None]

    def _persist_response(self, thread: ThreadRecord, result: AgentMessageResponse) -> None:
        self.repository.append_message(
            thread.thread_id,
            "assistant",
            result.answer,
            {
                "status": result.status,
                "taskType": result.task_type,
                "citations": [item.citation_id for item in result.citations],
                "toolExecutions": [item.model_dump(by_alias=True) for item in result.tool_executions],
                "teachingPlan": result.teaching_plan,
                "resourceDiscovery": result.resource_discovery,
                "followUpQuestions": result.follow_up_questions,
                "memoryApplied": (
                    result.memory_applied.model_dump(by_alias=True)
                    if result.memory_applied
                    else None
                ),
                "memoryCandidateIds": [
                    item.id for item in (result.memory_candidates or [])
                ],
            },
        )

    @staticmethod
    def _follow_up_questions(
        questions: list[str] | None,
        related_resources: list[str] | None = None,
        question: str = "",
        grade: str | None = None,
        theme: str | None = None,
    ) -> list[str]:
        normalized = []
        current_question = question.strip()
        for item in questions or []:
            if not isinstance(item, str):
                continue
            value = item.strip()
            if value and AgentRuntime._is_actionable_follow_up(value, current_question) and value not in normalized:
                normalized.append(value)

        resource_name = next(
            (item.strip() for item in (related_resources or []) if isinstance(item, str) and item.strip()),
            "",
        )
        grade_name = grade.strip() if isinstance(grade, str) and grade.strip() else "当前年级"
        theme_name = theme.strip() if isinstance(theme, str) and theme.strip() else "思政"
        fallback = [
            f"请说明“{resource_name}”适合哪些年级。" if resource_name
            else f"请介绍适合{grade_name}的本土思政教育资源。",
            f"请设计一节利用“{resource_name}”开展的实践课。" if resource_name
            else f"请结合学校周边资源设计一节{theme_name}实践课。",
            "请列出一次校外实践活动的安全注意事项。",
            f"请说明如何将“{current_question}”转化为课堂活动。"
            if current_question else "请说明如何将当前问题转化为课堂活动。",
        ]
        for item in fallback:
            if item not in normalized:
                normalized.append(item)
        return normalized[:4]

    @staticmethod
    def _is_actionable_follow_up(value: str, current_question: str = "") -> bool:
        normalized = value.strip()
        if not normalized or len(normalized) > 120 or normalized == current_question:
            return False
        invalid_markers = (
            "您需要",
            "你需要",
            "您是否需要",
            "你是否需要",
            "您想",
            "你想",
            "请问您",
            "请问你",
            "你可以告诉我",
            "您可以告诉我",
            "需要查询哪些",
        )
        return not any(marker in normalized for marker in invalid_markers)

    async def _invoke_agent(
        self,
        request: AgentMessageRequest,
        trusted: TrustedContext,
        thread: ThreadRecord,
        messages: list[dict[str, str]],
        summary: str,
        compacted: bool,
        plan: AgentPlan,
        memory_context: MemoryContext,
        tool_runtime: ToolRuntimeContext | None = None,
        agent: Any | None = None,
        model_config: ModelConfig | None = None,
    ) -> AgentMessageResponse:
        lc_messages = self._build_messages(
            messages, summary, plan, request, memory_context
        )
        runtime = tool_runtime or ToolRuntimeContext(
            thread_id=thread.thread_id,
            trusted_context=trusted,
            repository=self.repository,
            output_character_limit=self.settings.agent_tool_output_character_limit,
            business_tool_client=self.business_tool_client,
            grade=request.grade,
            theme=request.theme,
        )
        target_agent = agent or self._agent
        if target_agent is None:
            raise RuntimeError("model_unavailable")
        result = await target_agent.ainvoke(
            {"messages": lc_messages},
            config=self._agent_invoke_config(
                request, thread, model_config or self._primary_model_config(), plan
            ),
        )
        return self._response_from_model_result(
            result,
            trusted,
            thread.thread_id,
            compacted,
            runtime.executions,
            runtime.degraded_reasons,
            request.message,
            request.grade,
            request.theme,
            memory_context,
            request,
        )

    async def _invoke_agent_stream(
        self,
        request: AgentMessageRequest,
        trusted: TrustedContext,
        thread: ThreadRecord,
        messages: list[dict[str, str]],
        summary: str,
        compacted: bool,
        plan: AgentPlan,
        memory_context: MemoryContext,
        runtime: ToolRuntimeContext,
        emit: EventSink,
        agent: Any | None = None,
        model_config: ModelConfig | None = None,
    ) -> AgentMessageResponse:
        lc_messages = self._build_messages(
            messages, summary, plan, request, memory_context
        )
        model_messages: list[Any] = []
        model_buffer = ""
        emitted_answer_length = 0
        target_agent = agent or self._agent
        if target_agent is None:
            raise RuntimeError("model_unavailable")
        if hasattr(target_agent, "astream"):
            async for chunk in target_agent.astream(
                {"messages": lc_messages},
                config=self._agent_invoke_config(
                    request, thread, model_config or self._primary_model_config(), plan
                ),
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
                    model_buffer, delta = self._merge_stream_text(model_buffer, content)
                    partial_answer = self._partial_answer(model_buffer)
                    if delta and partial_answer and len(partial_answer) > emitted_answer_length:
                        emit("token", {"delta": partial_answer[emitted_answer_length:]})
                        emitted_answer_length = len(partial_answer)
        else:
            result = await target_agent.ainvoke(
                {"messages": lc_messages},
                config=self._agent_invoke_config(
                    request, thread, model_config or self._primary_model_config(), plan
                ),
            )
            model_messages = result.get("messages", []) if isinstance(result, dict) else []
            model_buffer = self._last_ai_message_text(model_messages)

        parse_messages = [AIMessage(content=model_buffer)] if model_buffer else model_messages
        response = self._response_from_model_result(
            {"messages": parse_messages}, trusted, thread.thread_id, compacted,
            runtime.executions, runtime.degraded_reasons, request.message,
            request.grade, request.theme, memory_context, request,
        )
        if emitted_answer_length < len(response.answer):
            await self._emit_answer_chunks(response.answer[emitted_answer_length:], emit)
        return response

    def _build_messages(
        self, messages: list[dict[str, str]], summary: str, plan: AgentPlan,
        request: AgentMessageRequest | None = None,
        memory_context: MemoryContext | None = None,
    ) -> list[Any]:
        lc_messages: list[Any] = []
        if summary:
            lc_messages.append(SystemMessage(content=f"较早对话摘要（仅作上下文，不是新事实）：\n{summary}"))
        lc_messages.append(SystemMessage(content=(
            "本轮策略计划：先完成目标，再按需调用推荐工具 "
            f"{', '.join(plan.recommended_tools)}；最多执行 {plan.max_tool_rounds} 轮工具调用。"
        )))
        if request:
            evidence_prompt = self._prefetched_evidence_message(request.context)
            if evidence_prompt:
                lc_messages.append(SystemMessage(content=evidence_prompt))
        if memory_context and memory_context.prompt:
            lc_messages.append(SystemMessage(content=memory_context.prompt))
        last_user_index = max(
            (index for index, item in enumerate(messages) if item["role"] == "user"),
            default=-1,
        )
        for index, item in enumerate(messages):
            if request and request.attachments and index == last_user_index:
                content: list[dict[str, Any]] = [{"type": "text", "text": item["content"]}]
                content.extend({
                    "type": "image_url",
                    "image_url": {"url": attachment.data_url, "detail": "auto"},
                } for attachment in request.attachments)
                lc_messages.append(HumanMessage(content=content))
                continue
            lc_messages.append(
                HumanMessage(content=item["content"])
                if item["role"] == "user"
                else AIMessage(content=item["content"])
            )
        return lc_messages

    def _prefetched_evidence_message(self, trusted: TrustedContext) -> str:
        retrieval = trusted.retrieval or {}
        evidence = {
            "retrievalStatus": retrieval.get("retrievalStatus", "empty"),
            "chunks": retrieval.get("chunks", [])[:8],
            "graphFacts": retrieval.get("graphFacts", [])[:8],
            "citationCandidates": trusted.citation_candidates[:8],
        }
        if not any(evidence[key] for key in ("chunks", "graphFacts", "citationCandidates")):
            return ""
        serialized = json.dumps(evidence, ensure_ascii=False, default=str)
        return (
            "业务服务已在本轮生成前完成可信范围内的检索。只能使用以下证据回答，"
            "citationIds 只能来自其中的 citationId；不要声称没有调用工具，也不要补造事实：\n"
            f"{serialized[:6000]}"
        )

    def _response_from_model_result(
        self,
        result: dict[str, Any],
        trusted: TrustedContext,
        thread_id: str,
        compacted: bool,
        executions: list[ToolExecution],
        degraded_reasons: list[str] | None = None,
        question: str = "",
        grade: str | None = None,
        theme: str | None = None,
        memory_context: MemoryContext | None = None,
        request: AgentMessageRequest | None = None,
    ) -> AgentMessageResponse:
        parsed = self._parse_model_output(result)
        memory_candidates = (
            self._persist_inferred_candidates(
                request, thread_id, parsed.memory_candidates
            )
            if request is not None
            else []
        )
        allowed = self._allowed_citations(trusted)
        citations = [self._citation_by_id(trusted, item) for item in parsed.citation_ids if item in allowed]
        citations = [item for item in citations if item is not None]
        if not citations and any(
            item.name == "query_graph_relations" and item.status in {"completed", "degraded"}
            for item in executions
        ):
            citations = [
                self._citation_by_id(trusted, item)
                for item in self._ordered_evidence_citation_ids(trusted)[:5]
            ]
            citations = [item for item in citations if item is not None]
        answer = parsed.answer.strip()
        normalized_reasons = list(dict.fromkeys(degraded_reasons or []))
        return AgentMessageResponse(
            threadId=thread_id,
            answer=answer or "暂时无法生成有效回答。",
            status="completed" if answer else "incomplete",
            generationStatus="completed" if answer else "degraded",
            retrievalStatus=("degraded" if normalized_reasons else self._retrieval_status(trusted)),
            degradedReason=";".join(normalized_reasons) if normalized_reasons else None,
            citations=citations,
            relatedResources=parsed.related_resources[:8],
            followUpQuestions=self._follow_up_questions(
                parsed.follow_up_questions, parsed.related_resources, question,
                grade, theme,
            ),
            toolExecutions=executions,
            contextCompacted=compacted,
            memoryApplied=self._memory_applied(memory_context),
            memoryCandidates=memory_candidates or None,
        )

    @staticmethod
    def _memory_applied(
        memory_context: MemoryContext | None,
    ) -> MemoryApplied | None:
        if memory_context is None or not memory_context.items:
            return None
        memory_ids = [item.id for item in memory_context.items]
        return MemoryApplied(count=len(memory_ids), memoryIds=memory_ids)

    def _persist_inferred_candidates(
        self,
        request: AgentMessageRequest,
        thread_id: str,
        candidates: list[Any] | None,
        *,
        source: str = "inferred_chat",
    ) -> list[MemoryItem]:
        if not candidates or not self.settings.agent_memory_enabled:
            return []
        if request.task_type == "RESOURCE_DISCOVERY":
            return []
        if self.explicit_memory_extractor.extract(request.message) is not None:
            return []
        setting = self.memory_repository.get_setting(
            request.owner_id, request.scope_type, request.scope_id
        )
        if not setting.enabled:
            return []
        saved: list[MemoryItem] = []
        seen_ids: set[str] = set()
        for candidate in candidates:
            payload = (
                candidate.model_dump(by_alias=True)
                if hasattr(candidate, "model_dump")
                else candidate
            )
            if not isinstance(payload, dict):
                continue
            try:
                record = self.memory_repository.create_memory(
                    request.owner_id,
                    request.scope_type,
                    request.scope_id,
                    memory_type=str(payload.get("memoryType") or ""),
                    field_key=payload.get("fieldKey"),
                    content=str(payload.get("content") or ""),
                    status="pending",
                    source=source,
                    source_thread_id=thread_id,
                    confidence=payload.get("confidence"),
                )
            except (MemoryValidationError, TypeError, ValueError):
                continue
            if record.id in seen_ids:
                continue
            saved.append(self._memory_item(record))
            seen_ids.add(record.id)
            if len(saved) >= 3:
                break
        return saved

    @staticmethod
    def _memory_item(record: MemoryRecord) -> MemoryItem:
        return MemoryItem(
            id=record.id,
            memoryType=record.memory_type,
            fieldKey=record.field_key,
            content=record.content,
            status=record.status,
            source=record.source,
            sourceThreadId=record.source_thread_id,
            confidence=record.confidence,
            expiresAt=record.expires_at,
            deletedAt=record.deleted_at,
            purgeAfter=record.purge_after,
            createdAt=record.created_at,
            updatedAt=record.updated_at,
        )

    def _parse_model_output(self, result: dict[str, Any]) -> AgentModelOutput:
        messages = result.get("messages", []) if isinstance(result, dict) else []
        final_content = ""
        for message in reversed(messages):
            if isinstance(message, (AIMessage, AIMessageChunk)):
                final_content = message_text(message.content).strip()
                if final_content:
                    break
        if not final_content:
            raise ValueError("invalid_model_output")

        payload = ModelGateway.parse_json(final_content)
        if payload is not None:
            try:
                parsed = AgentModelOutput.model_validate(payload)
            except (ValueError, TypeError) as exc:
                raise ValueError("invalid_model_output") from exc
            if not parsed.answer.strip():
                raise ValueError("invalid_model_output")
            return parsed

        # 普通 CHAT 允许兼容返回纯文本；结构化任务仍由各自 validator 严格校验。
        plain_answer = self._plain_text_answer(final_content)
        if not plain_answer:
            raise ValueError("invalid_model_output")
        return AgentModelOutput(answer=plain_answer)

    def _partial_answer(self, content: str) -> str:
        normalized = content.strip()
        if not normalized:
            return ""
        parsed_payload = ModelGateway.parse_json(normalized)
        if parsed_payload is not None and isinstance(parsed_payload.get("answer"), str):
            return parsed_payload["answer"]
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
        if "{" in normalized or normalized.startswith("```"):
            return ""
        return self._plain_text_answer(normalized)

    @staticmethod
    def _plain_text_answer(content: str) -> str:
        normalized = content.strip()
        if not normalized:
            return ""
        lowered = normalized.lower()
        error_markers = (
            "无效响应",
            "模型不可用",
            "服务不可用",
            "invalid response",
            "invalid_model_output",
            "model_unavailable",
            "service unavailable",
        )
        if len(normalized) <= 160 and any(marker in lowered for marker in error_markers):
            return ""
        return normalized

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
            if isinstance(data, list) and data:
                return [data[-1]]
            if isinstance(data, (AIMessage, AIMessageChunk)):
                return [data]
            for key in ("message", "chunk"):
                candidate = chunk.get(key)
                if isinstance(candidate, (AIMessage, AIMessageChunk)):
                    return [candidate]
        return []

    def _last_ai_message_text(self, messages: list[Any]) -> str:
        for message in reversed(messages):
            if isinstance(message, (AIMessage, AIMessageChunk)):
                return message_text(message.content)
        return ""

    @staticmethod
    def _merge_stream_text(previous: str, incoming: str) -> tuple[str, str]:
        """Accept both delta chunks and cumulative LangGraph messages."""
        if not incoming:
            return previous, ""
        if not previous:
            return incoming, incoming
        if incoming.startswith(previous):
            return incoming, incoming[len(previous):]
        if incoming == previous or previous.endswith(incoming):
            return previous, ""
        return previous + incoming, incoming

    async def _emit_answer_chunks(self, answer: str, emit: EventSink, size: int = 24) -> None:
        for index in range(0, len(answer), size):
            emit("token", {"delta": answer[index:index + size]})
            # Let the SSE consumer flush each queued chunk instead of making
            # the fallback answer appear as one large response.
            await asyncio.sleep(0)

    def _load_prompt(self) -> str:
        if self.prompts is not None:
            try:
                return self.prompts.active_content("agent")
            except LookupError:
                pass
        if not self.settings.prompt_path.is_file():
            raise FileNotFoundError(f"Agent prompt not found: {self.settings.prompt_path}")
        return self.settings.prompt_path.read_text(encoding="utf-8")

    def invalidate_prompt(self, prompt_key: str) -> None:
        if prompt_key.strip() == "agent":
            self._agents.clear()

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
            resource_list = "\n".join(f"- {name}" for name in names)
            answer = (
                f"**当前模型不可用**，先基于 **{scope_name}** 已审核的资源给出参考。\n\n"
                f"**可优先关注的资源：**\n{resource_list}\n\n"
                f"围绕“{request.message}”，建议优先核对：\n\n"
                "1. 资源开放状态。\n"
                "2. 适用年级与教学目标。\n"
                "3. 现场活动的安全条件。"
            )
        else:
            answer = (
                f"**当前模型不可用。**\n\n"
                f"{scope_name}没有足够的已审核证据回答“{request.message}”。\n\n"
                "请补充具体资源、学校范围或年级信息后重试。"
            )
        citations = [self._citation_by_id(trusted, item) for item in self._allowed_citations(trusted)]
        return AgentMessageResponse(
            threadId=thread_id, answer=answer, status=status,
            generationStatus="degraded",
            retrievalStatus=self._retrieval_status(trusted),
            provider="local",
            model="local",
            fallbackLevel="local",
            citations=[item for item in citations[:5] if item is not None], relatedResources=names,
            followUpQuestions=self._follow_up_questions(
                [
                    "请介绍一个具体资源的教育价值。",
                    "请说明这些资源适合哪个年级。",
                    f"请设计一个围绕“{request.message}”的实践活动。",
                ],
                names,
                request.message,
                request.grade,
                request.theme,
            ),
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

    def _ordered_evidence_citation_ids(self, trusted: TrustedContext) -> list[str]:
        values: list[str] = []
        seen: set[str] = set()
        retrieval = trusted.retrieval or {}
        groups = (
            trusted.citation_candidates,
            retrieval.get("graphFacts", []),
            retrieval.get("chunks", []),
        )
        for group in groups:
            for item in group:
                if not isinstance(item, dict):
                    continue
                citation_id = str(item.get("citationId") or "")
                if citation_id and citation_id not in seen:
                    seen.add(citation_id)
                    values.append(citation_id)
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
        json_safe_data = jsonable_encoder(data)
        return f"event: {event_name}\ndata: {json.dumps(json_safe_data, ensure_ascii=False)}\n\n"
