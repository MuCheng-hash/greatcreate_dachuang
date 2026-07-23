from __future__ import annotations

import json
import logging
import re
import threading
import time
import uuid
from pathlib import Path
from queue import Queue
from typing import Any, Callable, Iterator

from langchain.agents import create_agent
from langchain.agents.middleware import before_model
from langgraph.checkpoint.memory import InMemorySaver

from llm_service.observability import (
    FallbackAlertManager,
    LlmObservability,
    LlmTraceContext,
    classify_llm_error,
)

from .config import AgentModelConfig, AgentSettings
from .schemas import AgentFinalResponse, AgentRuntimeRequest, dump_model
from .tools import AgentState, AgentToolError, JavaToolClient, create_tools

LOGGER = logging.getLogger("llm.agent")
PROMPT_ROOT = Path(__file__).resolve().parent.parent / "prompts" / "agent"


class AgentRuntime:
    def __init__(
        self,
        settings: AgentSettings | None = None,
        observability: LlmObservability | None = None,
        alerts: FallbackAlertManager | None = None,
    ):
        self.settings = settings or AgentSettings.from_env()
        self.observability = observability
        self.alerts = alerts or FallbackAlertManager()
        self.checkpointer = InMemorySaver()
        self._conversation_owners: dict[str, str] = {}
        self._owner_lock = threading.RLock()

    def run(self, payload: dict[str, Any]) -> dict[str, Any]:
        request = AgentRuntimeRequest.model_validate(payload)
        run_id = str(uuid.uuid4())
        conversation_id = request.conversation_id or str(uuid.uuid4())
        request.conversation_id = conversation_id
        self._claim_conversation(request)
        started = time.perf_counter()
        events: list[dict[str, Any]] = []
        emit = lambda event_name, data: events.append(self._event(event_name, run_id, conversation_id, data))
        primary_config = self.settings.model_chain()[0]
        emit(
            "run.started",
            {"provider": primary_config.provider, "model": primary_config.model},
        )
        response = self._execute(request, run_id, emit)
        response["runId"] = run_id
        response["conversationId"] = conversation_id
        response.setdefault("promptVersion", self.settings.prompt_version)
        LOGGER.info(
            "agent_run_completed",
            extra={
                "runId": run_id,
                "conversationId": conversation_id,
                "model": response.get("model"),
                "provider": response.get("provider"),
                "promptVersion": response.get("promptVersion", self.settings.prompt_version),
                "latencyMs": round((time.perf_counter() - started) * 1000),
                "inputTokens": response.get("inputTokens"),
                "outputTokens": response.get("outputTokens"),
                "generationStatus": response.get("generationStatus"),
                "retrievalStatus": response.get("retrievalStatus"),
                "fallbackLevel": response.get("fallbackLevel"),
            },
        )
        return response

    def stream_events(self, payload: dict[str, Any]) -> Iterator[str]:
        queue: Queue[dict[str, Any] | None] = Queue()
        cancelled = threading.Event()

        def put_event(event: dict[str, Any]) -> None:
            if not cancelled.is_set():
                queue.put(event)

        def worker() -> None:
            started = time.perf_counter()
            run_id = str(uuid.uuid4())
            conversation_id: str | None = None
            try:
                request = AgentRuntimeRequest.model_validate(payload)
                conversation_id = request.conversation_id or str(uuid.uuid4())
                request.conversation_id = conversation_id
                self._claim_conversation(request)
                primary_config = self.settings.model_chain()[0]
                put_event(
                    self._event(
                        "run.started",
                        run_id,
                        conversation_id,
                        {
                            "provider": primary_config.provider,
                            "model": primary_config.model,
                        },
                    )
                )

                def emit(event_name: str, data: dict[str, Any]) -> None:
                    put_event(self._event(event_name, run_id, conversation_id, data))

                response = self._execute_stream(request, run_id, emit)
                if cancelled.is_set():
                    return
                response["runId"] = run_id
                response["conversationId"] = conversation_id
                response.setdefault("promptVersion", self.settings.prompt_version)
                LOGGER.info(
                    "agent_stream_completed",
                    extra={
                        "runId": run_id,
                        "conversationId": conversation_id,
                        "model": response.get("model"),
                        "provider": response.get("provider"),
                        "promptVersion": response.get("promptVersion"),
                        "latencyMs": round((time.perf_counter() - started) * 1000),
                        "generationStatus": response.get("generationStatus"),
                        "retrievalStatus": response.get("retrievalStatus"),
                        "inputTokens": response.get("inputTokens"),
                        "outputTokens": response.get("outputTokens"),
                        "fallbackLevel": response.get("fallbackLevel"),
                    },
                )
                put_event(self._event("final", run_id, conversation_id, {"response": response}))
                put_event(self._event("done", run_id, conversation_id, {}))
            except Exception as exc:  # pragma: no cover - covered through endpoint smoke tests
                error_type = self._error_type(exc)
                LOGGER.exception(
                    "agent_stream_failed",
                    extra={
                        "runId": run_id,
                        "conversationId": conversation_id,
                        "latencyMs": round((time.perf_counter() - started) * 1000),
                        "errorType": error_type,
                    },
                )
                put_event(
                    self._event(
                        "error",
                        run_id,
                        conversation_id,
                        {"errorType": error_type, "message": str(exc)},
                    )
                )
                put_event(self._event("done", run_id, conversation_id, {}))
            finally:
                queue.put(None)

        threading.Thread(target=worker, name="agent-stream", daemon=True).start()
        try:
            while True:
                event = queue.get()
                if event is None:
                    break
                yield self._format_sse(event)
        finally:
            cancelled.set()

    def _execute(
        self,
        request: AgentRuntimeRequest,
        run_id: str,
        emit: Callable[[str, dict[str, Any]], None],
    ) -> dict[str, Any]:
        last_error: Exception | None = None
        attempts: list[dict[str, Any]] = []
        chain = self.settings.model_chain()
        alert_context = self._alert_context(request, run_id)
        for index, model_config in enumerate(chain):
            model_name = model_config.model
            attempt_started = time.perf_counter()
            try:
                model = self._build_model(model_name)
                if model is None:
                    attempts.append(self._failed_attempt(model_config, "not_configured"))
                    if index + 1 < len(chain):
                        next_config = chain[index + 1]
                        self.alerts.fallback(
                            alert_context, model_config.model, next_config.model,
                            "not_configured", next_config.fallback_level,
                        )
                        emit("model.fallback", {
                            "failedModel": model_config.model,
                            "nextModel": next_config.model,
                            "errorType": "not_configured",
                            "fallbackLevel": next_config.fallback_level,
                            "reset": False,
                        })
                    continue
                emit(
                    "model.started",
                    {
                        "provider": model_config.provider,
                        "model": model_name,
                        "fallbackLevel": model_config.fallback_level,
                    },
                )
                response = self._invoke_agent(request, run_id, model, model_name, emit)
                latency_ms = round((time.perf_counter() - attempt_started) * 1000)
                LOGGER.info(
                    "agent_model_completed",
                    extra={
                        "runId": run_id,
                        "conversationId": request.conversation_id,
                        "provider": model_config.provider,
                        "model": model_name,
                        "promptVersion": self.settings.prompt_version,
                        "latencyMs": latency_ms,
                        "inputTokens": response.get("inputTokens"),
                        "outputTokens": response.get("outputTokens"),
                        "fallbackLevel": model_config.fallback_level,
                    },
                )
                return response
            except Exception as exc:
                last_error = exc
                error_type = self._error_type(exc)
                attempts.append(self._failed_attempt(model_config, error_type))
                latency_ms = round((time.perf_counter() - attempt_started) * 1000)
                LOGGER.warning(
                    "agent_model_failed",
                    extra={
                        "runId": run_id,
                        "conversationId": request.conversation_id,
                        "provider": model_config.provider,
                        "model": model_name,
                        "errorType": error_type,
                        "fallbackLevel": model_config.fallback_level,
                        "latencyMs": latency_ms,
                    },
                )
                emit(
                    "model.failed",
                    {
                        "provider": model_config.provider,
                        "model": model_name,
                        "errorType": error_type,
                        "fallbackLevel": model_config.fallback_level,
                        "latencyMs": latency_ms,
                    },
                )

            if index + 1 < len(chain):
                next_config = chain[index + 1]
                error_type = attempts[-1]["errorType"] if attempts else "provider_error"
                self.alerts.fallback(
                    alert_context, model_config.model, next_config.model,
                    error_type, next_config.fallback_level,
                )
                emit("model.fallback", {
                    "failedModel": model_config.model,
                    "nextModel": next_config.model,
                    "errorType": error_type,
                    "fallbackLevel": next_config.fallback_level,
                    "reset": False,
                })

        self.alerts.exhausted(alert_context, attempts or [{"status": "not_configured"}])
        return self._local_fallback(request, run_id, last_error)

    def _execute_stream(
        self,
        request: AgentRuntimeRequest,
        run_id: str,
        emit: Callable[[str, dict[str, Any]], None],
    ) -> dict[str, Any]:
        last_error: Exception | None = None
        attempts: list[dict[str, Any]] = []
        chain = self.settings.model_chain()
        alert_context = self._alert_context(request, run_id)
        for index, model_config in enumerate(chain):
            model_name = model_config.model
            attempt_started = time.perf_counter()
            try:
                model = self._build_model(model_name)
                if model is None:
                    attempts.append(self._failed_attempt(model_config, "not_configured"))
                    if index + 1 < len(chain):
                        next_config = chain[index + 1]
                        self.alerts.fallback(
                            alert_context, model_config.model, next_config.model,
                            "not_configured", next_config.fallback_level,
                        )
                        emit("model.fallback", {
                            "failedModel": model_config.model,
                            "nextModel": next_config.model,
                            "errorType": "not_configured",
                            "fallbackLevel": next_config.fallback_level,
                            "reset": False,
                        })
                    continue
                emit(
                    "model.started",
                    {
                        "provider": model_config.provider,
                        "model": model_name,
                        "fallbackLevel": model_config.fallback_level,
                    },
                )
                response = self._invoke_agent_stream(request, run_id, model, model_name, emit)
                latency_ms = round((time.perf_counter() - attempt_started) * 1000)
                LOGGER.info(
                    "agent_stream_model_completed",
                    extra={
                        "runId": run_id,
                        "conversationId": request.conversation_id,
                        "provider": model_config.provider,
                        "model": model_name,
                        "promptVersion": self.settings.prompt_version,
                        "latencyMs": latency_ms,
                        "inputTokens": response.get("inputTokens"),
                        "outputTokens": response.get("outputTokens"),
                        "fallbackLevel": model_config.fallback_level,
                    },
                )
                return response
            except Exception as exc:
                last_error = exc
                error_type = self._error_type(exc)
                attempts.append(self._failed_attempt(model_config, error_type))
                latency_ms = round((time.perf_counter() - attempt_started) * 1000)
                LOGGER.warning(
                    "agent_stream_model_failed",
                    extra={
                        "runId": run_id,
                        "conversationId": request.conversation_id,
                        "provider": model_config.provider,
                        "model": model_name,
                        "errorType": error_type,
                        "fallbackLevel": model_config.fallback_level,
                        "latencyMs": latency_ms,
                    },
                )
                emit(
                    "model.failed",
                    {
                        "provider": model_config.provider,
                        "model": model_name,
                        "errorType": error_type,
                        "fallbackLevel": model_config.fallback_level,
                        "latencyMs": latency_ms,
                    },
                )

            if index + 1 < len(chain):
                next_config = chain[index + 1]
                error_type = attempts[-1]["errorType"] if attempts else "provider_error"
                self.alerts.fallback(
                    alert_context, model_config.model, next_config.model,
                    error_type, next_config.fallback_level,
                )
                emit("model.fallback", {
                    "failedModel": model_config.model,
                    "nextModel": next_config.model,
                    "errorType": error_type,
                    "fallbackLevel": next_config.fallback_level,
                    "reset": True,
                })

        self.alerts.exhausted(alert_context, attempts or [{"status": "not_configured"}])
        response = self._local_fallback(request, run_id, last_error)
        self._emit_text_chunks(response.get("answer", ""), emit)
        return response

    def _invoke_agent(
        self,
        request: AgentRuntimeRequest,
        run_id: str,
        model: Any,
        model_name: str,
        emit: Callable[[str, dict[str, Any]], None],
    ) -> dict[str, Any]:
        client = JavaToolClient(
            self.settings.internal_business_base_url,
            self.settings.internal_service_token,
            self.settings.llm_timeout_seconds,
        )
        tools = create_tools(client, run_id, emit)
        agent = self._create_agent(model, tools)
        callback = self._trace_callback(request, run_id, model_name)
        result = agent.invoke(
            self._initial_state(request),
            config=self._agent_config(request.conversation_id or "", callback),
        )
        content = self._last_message_text(result.get("messages", []))
        response = self._parse_response(content, request, run_id, result.get("messages", []))
        response["model"] = model_name
        response["provider"] = self.settings.model_config_for(model_name).provider
        return response

    def _invoke_agent_stream(
        self,
        request: AgentRuntimeRequest,
        run_id: str,
        model: Any,
        model_name: str,
        emit: Callable[[str, dict[str, Any]], None],
    ) -> dict[str, Any]:
        client = JavaToolClient(
            self.settings.internal_business_base_url,
            self.settings.internal_service_token,
            self.settings.llm_timeout_seconds,
        )
        tools = create_tools(client, run_id, emit)
        agent = self._create_agent(model, tools)
        callback = self._trace_callback(request, run_id, model_name)
        config = self._agent_config(request.conversation_id or "", callback)
        model_buffer = ""
        emitted_answer_length = 0
        messages: list[Any] = []

        for chunk in agent.stream(
            self._initial_state(request),
            config=config,
            stream_mode="messages",
            version="v2",
        ):
            for message in self._stream_messages(chunk):
                messages.append(message)
                if not self._is_ai_message(message):
                    continue
                content = self._message_content(message)
                if not content:
                    continue
                model_buffer += content
                partial_answer = self._partial_answer(model_buffer)
                if partial_answer and len(partial_answer) > emitted_answer_length:
                    emit("token", {"delta": partial_answer[emitted_answer_length:]})
                    emitted_answer_length = len(partial_answer)

        # Streaming providers may expose the final JSON as many AI message
        # chunks; the last chunk can be only ``}``. Prefer the accumulated
        # buffer so the response parser sees the complete JSON document.
        content = model_buffer or self._last_message_text(messages)
        response = self._parse_response(content, request, run_id, messages)
        response["model"] = model_name
        response["provider"] = self.settings.model_config_for(model_name).provider
        answer = response.get("answer", "")
        if emitted_answer_length < len(answer):
            self._emit_text_chunks(answer[emitted_answer_length:], emit)
        return response

    def _create_agent(self, model: Any, tools: list[Any]) -> Any:
        return create_agent(
            model=model,
            tools=tools,
            system_prompt=self._load_prompt(),
            middleware=[self._history_middleware()],
            state_schema=AgentState,
            checkpointer=self.checkpointer,
        )

    def _history_middleware(self) -> Any:
        max_messages = max(2, self.settings.max_history_messages)

        @before_model(state_schema=AgentState, name="trim_agent_history")
        def trim_agent_history(state: AgentState, runtime: Any) -> dict[str, Any] | None:
            messages = list(state.get("messages") or [])
            if len(messages) <= max_messages:
                return None
            return {"messages": messages[-max_messages:]}

        return trim_agent_history

    def _initial_state(self, request: AgentRuntimeRequest) -> dict[str, Any]:
        return {
            "messages": [{"role": "user", "content": request.question}],
            "conversationId": request.conversation_id or "",
            "scope": request.scope.model_dump(by_alias=True, exclude_none=True),
            "intent": "UNKNOWN",
            "lastToolResults": [],
            "citationCandidates": [],
            "agent_context": {
                "question": request.question,
                "actor": request.actor.model_dump(by_alias=True, exclude_none=True),
                "scope": request.scope.model_dump(by_alias=True, exclude_none=True),
                "grade": request.grade,
                "theme": request.theme,
                "topK": request.top_k,
            },
            "tool_trace": [],
        }

    def _agent_config(self, conversation_id: str, callback: Any | None = None) -> dict[str, Any]:
        config: dict[str, Any] = {
            "configurable": {"thread_id": conversation_id},
            "recursion_limit": max(3, self.settings.max_iterations * 2 + 1),
        }
        if callback is not None:
            config["callbacks"] = [callback]
        return config

    def _trace_callback(
        self, request: AgentRuntimeRequest, run_id: str, model_name: str
    ) -> Any | None:
        if self.observability is None:
            return None
        actor = request.actor
        if actor.account_id is not None:
            user_id = f"account:{actor.account_id}"
        elif actor.school_id is not None:
            user_id = f"school:{actor.school_id}"
        else:
            user_id = "anonymous"
        model_config = self.settings.model_config_for(model_name)
        context = LlmTraceContext(
            feature="agent-runtime",
            user_id=user_id,
            session_id=request.conversation_id or "",
            trace_id=run_id,
            expected_json=True,
            metadata={
                "scopeType": request.scope.scope_type if request.scope else None,
                "scopeId": request.scope.scope_id if request.scope else None,
                "fallbackLevel": model_config.fallback_level,
                "promptVersion": self.settings.prompt_version,
            },
        )
        return self.observability.callback(
            context, model_config.provider, model_name
        )

    def _alert_context(self, request: AgentRuntimeRequest, run_id: str) -> LlmTraceContext:
        actor = request.actor
        if actor.account_id is not None:
            user_id = f"account:{actor.account_id}"
        elif actor.school_id is not None:
            user_id = f"school:{actor.school_id}"
        else:
            user_id = "anonymous"
        return LlmTraceContext(
            feature="agent-runtime",
            user_id=user_id,
            session_id=request.conversation_id or "",
            trace_id=run_id,
            metadata={
                "scopeType": request.scope.scope_type if request.scope else None,
                "scopeId": request.scope.scope_id if request.scope else None,
            },
        )

    @staticmethod
    def _failed_attempt(model_config: AgentModelConfig, error_type: str) -> dict[str, Any]:
        return {
            "provider": model_config.provider,
            "model": model_config.model,
            "fallbackLevel": model_config.fallback_level,
            "status": "failed",
            "errorType": error_type,
        }

    def _build_model(self, model_name: str) -> Any | None:
        model_config = self.settings.model_config_for(model_name)
        if not model_config.api_key or not model_name:
            return None
        try:
            from langchain_openai import ChatOpenAI
        except ImportError:
            LOGGER.error("langchain_openai_missing")
            return None
        base_url = self._resolve_base_url(model_config.base_url)
        return ChatOpenAI(
            model=model_name,
            api_key=model_config.api_key,
            base_url=base_url,
            temperature=0.2,
            timeout=self.settings.llm_timeout_seconds,
            max_retries=0,
            stream_usage=True,
        )

    def _resolve_base_url(self, configured_url: str | None = None) -> str | None:
        value = (configured_url or self.settings.llm_api_url or self.settings.llm_base_url).rstrip("/")
        if not value:
            return None
        suffix = "/chat/completions"
        if value.endswith(suffix):
            value = value[: -len(suffix)]
        return value

    def _load_prompt(self) -> str:
        path = PROMPT_ROOT / self.settings.prompt_version / "system.md"
        if not path.exists():
            path = PROMPT_ROOT / "v1" / "system.md"
        return path.read_text(encoding="utf-8")

    def _parse_response(
        self,
        content: str,
        request: AgentRuntimeRequest,
        run_id: str,
        messages: list[Any],
    ) -> dict[str, Any]:
        parsed = self._parse_json(content)
        if not isinstance(parsed, dict) or not str(parsed.get("answer", "")).strip():
            raise ValueError("invalid_json")
        allowed = self._collect_citation_ids(messages)
        raw_ids = parsed.get("citationIds") or []
        citation_ids = [str(item) for item in raw_ids if str(item) in allowed][:5]
        citations = [item for item in self._collect_citations(messages) if item.get("citationId") in citation_ids]
        retrieval_status = self._resolve_retrieval_status(parsed, messages, allowed)
        response = dump_model(
            AgentFinalResponse(
                answer=str(parsed["answer"]),
                conversationId=request.conversation_id or "",
                runId=run_id,
                intent=self._normalize_intent(parsed.get("intent"), request.question),
                generationStatus="completed",
                retrievalStatus=retrieval_status,
                scopeType=request.scope.scope_type if request.scope else None,
                scopeId=request.scope.scope_id if request.scope else None,
                relatedResources=self._string_list(parsed.get("relatedResources")),
                citations=citations,
                citationIds=citation_ids,
                followUpQuestions=self._string_list(parsed.get("followUpQuestions"), 4),
                message="已由 LangChain Agent 根据受控工具结果生成回答。",
                **self._usage_fields(messages),
            )
        )
        return response

    def _local_fallback(
        self,
        request: AgentRuntimeRequest,
        run_id: str,
        error: Exception | None,
    ) -> dict[str, Any]:
        scope_name = request.scope.name if request.scope and request.scope.name else "当前学校"
        question = request.question.strip()
        if "附近" in question or "资源" in question:
            answer = f"围绕“{question}”，{scope_name}可以优先查询已审核的周边教育资源，并结合学生年级和活动主题进行筛选。"
            intent = "NEARBY_RESOURCE"
        elif any(word in question for word in ("关系", "关联", "联系")):
            answer = f"当前无法完成{scope_name}的图谱关系查询，请稍后重试或补充具体人物、学校或资源名称。"
            intent = "RELATION_QUERY"
        elif any(word in question for word in ("如何", "怎样", "怎么", "活动", "课堂")):
            answer = f"可以围绕{scope_name}的真实教育资源设计课堂导入、现场体验、实践任务和反思展示四个环节。"
            intent = "TEACHING_SUGGESTION"
        else:
            answer = "请补充具体学校、资源或教学问题，我会结合已授权的业务数据进行回答。"
            intent = "UNKNOWN"
        response = dump_model(
            AgentFinalResponse(
                answer=answer,
                conversationId=request.conversation_id or "",
                runId=run_id,
                intent=intent,
                generationStatus="degraded",
                retrievalStatus="degraded",
                scopeType=request.scope.scope_type if request.scope else None,
                scopeId=request.scope.scope_id if request.scope else None,
                followUpQuestions=[
                    f"{scope_name}有哪些资源适合四年级学生？",
                    f"如何利用{scope_name}周边资源设计一次实践活动？",
                ],
                message="真实 Agent 模型或业务工具不可用，已使用本地结构化兜底回答。",
                fallbackLevel="local",
            )
        )
        response["model"] = "local"
        response["provider"] = "local"
        return response

    def _claim_conversation(self, request: AgentRuntimeRequest) -> None:
        conversation_id = request.conversation_id or ""
        actor = request.actor
        owner = f"{actor.account_id}:{actor.school_id}:{actor.role_code}"
        with self._owner_lock:
            previous = self._conversation_owners.get(conversation_id)
            if previous is not None and previous != owner:
                raise ValueError("conversation does not belong to current actor")
            self._conversation_owners[conversation_id] = owner

    def _collect_citation_ids(self, messages: list[Any]) -> set[str]:
        return {
            str(item["citationId"]).strip()
            for item in self._collect_citations(messages)
            if isinstance(item, dict) and str(item.get("citationId") or "").strip()
        }

    def _tool_payloads(self, messages: list[Any]) -> list[dict[str, Any]]:
        payloads: list[dict[str, Any]] = []
        for message in messages:
            if getattr(message, "type", "") not in {"tool", "ToolMessage"}:
                continue
            content = self._message_content(message)
            try:
                data = json.loads(content)
            except (TypeError, ValueError, json.JSONDecodeError):
                continue
            if isinstance(data, dict):
                payloads.append(data)
        return payloads

    def _resolve_retrieval_status(
        self,
        parsed: dict[str, Any],
        messages: list[Any],
        allowed_citation_ids: set[str],
    ) -> str:
        """Keep retrieval status evidence-bound instead of trusting model text."""
        actual_statuses: list[str] = []
        tool_failed = False
        for tool_result in self._tool_payloads(messages):
            if tool_result.get("status") == "error":
                tool_failed = True
            data = tool_result.get("data")
            if isinstance(data, dict) and data.get("retrievalStatus"):
                actual_statuses.append(str(data["retrievalStatus"]).lower())

        if "ok" in actual_statuses:
            return "ok"
        if "degraded" in actual_statuses:
            return "degraded"
        if "empty" in actual_statuses:
            return "empty"
        if tool_failed:
            return "degraded"
        return "ok" if allowed_citation_ids else "empty"

    def _normalize_intent(self, value: Any, question: str) -> str:
        allowed = {
            "NEARBY_RESOURCE",
            "TEACHING_SUGGESTION",
            "RESOURCE_EXPLANATION",
            "RELATION_QUERY",
            "UNKNOWN",
        }
        raw = str(value or "").strip().upper()
        if raw in allowed:
            return raw

        source = f"{raw} {question or ''}"
        if any(word in source for word in ("关系", "关联", "联系", "RELATION")):
            return "RELATION_QUERY"
        if any(word in source for word in (
            "如何", "怎样", "怎么", "活动", "课堂", "方案", "教学", "TEACHING"
        )):
            return "TEACHING_SUGGESTION"
        if any(word in source for word in (
            "解释", "介绍", "说明", "适合", "适配", "学段", "年级", "EXPLANATION"
        )):
            return "RESOURCE_EXPLANATION"
        if any(word in source for word in (
            "附近", "资源", "红色", "查询", "搜索", "NEARBY"
        )):
            return "NEARBY_RESOURCE"
        return "UNKNOWN"

    def _collect_citations(self, messages: list[Any]) -> list[dict[str, Any]]:
        citations: list[dict[str, Any]] = []
        for data in self._tool_payloads(messages):
            self._find_citations(data, citations)
        unique: dict[str, dict[str, Any]] = {}
        for item in citations:
            citation_id = item.get("citationId")
            if citation_id:
                unique.setdefault(str(citation_id), item)
        return list(unique.values())

    def _find_citations(self, value: Any, output: list[dict[str, Any]]) -> None:
        if isinstance(value, dict):
            if value.get("citationId"):
                output.append(value)
            for child in value.values():
                self._find_citations(child, output)
        elif isinstance(value, list):
            for child in value:
                self._find_citations(child, output)

    def _last_message_text(self, messages: list[Any]) -> str:
        for message in reversed(messages):
            if self._is_ai_message(message):
                content = self._message_content(message)
                if content.strip():
                    return content
        return ""

    def _stream_messages(self, chunk: Any) -> list[Any]:
        if not isinstance(chunk, dict):
            return []
        if chunk.get("type") == "messages":
            data = chunk.get("data")
            if isinstance(data, tuple) and data:
                return [data[0]]
            return [data] if data is not None else []
        if chunk.get("type") == "updates":
            return self._messages_from_updates(chunk.get("data"))
        return []

    def _messages_from_updates(self, value: Any) -> list[Any]:
        messages: list[Any] = []
        if isinstance(value, dict):
            for key, child in value.items():
                if key == "messages" and isinstance(child, list):
                    messages.extend(child)
                else:
                    messages.extend(self._messages_from_updates(child))
        elif isinstance(value, list):
            for child in value:
                messages.extend(self._messages_from_updates(child))
        return messages

    def _is_ai_message(self, message: Any) -> bool:
        return getattr(message, "type", "") in {"ai", "AIMessageChunk"}

    def _message_content(self, message: Any) -> str:
        content = getattr(message, "content", "")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            return "".join(
                str(block.get("text", "")) if isinstance(block, dict) else str(block)
                for block in content
            )
        return str(content or "")

    def _partial_answer(self, content: str) -> str:
        match = re.search(r'"answer"\s*:\s*"((?:\\.|[^"\\])*)', content)
        if not match:
            return ""
        try:
            return json.loads('"' + match.group(1) + '"')
        except (TypeError, ValueError, json.JSONDecodeError):
            return match.group(1)

    def _emit_text_chunks(self, text: str, emit: Callable[[str, dict[str, Any]], None]) -> None:
        for index in range(0, len(text), 8):
            emit("token", {"delta": text[index:index + 8]})

    def _usage_fields(self, messages: list[Any]) -> dict[str, int | None]:
        usage_by_message_id: dict[str, dict[str, Any]] = {}
        usage_without_id: list[dict[str, Any]] = []
        for message in messages:
            usage = self._message_usage(message)
            if not usage:
                continue
            message_id = getattr(message, "id", None)
            if message_id:
                # Streaming chunks for one model response can repeat the same
                # usage metadata; keep the latest value for that message.
                usage_by_message_id[str(message_id)] = usage
            else:
                usage_without_id.append(usage)

        usages = list(usage_by_message_id.values()) + usage_without_id
        input_values = [
            value
            for usage in usages
            for value in [self._int_or_none(usage.get("input_tokens", usage.get("prompt_tokens")))]
            if value is not None
        ]
        output_values = [
            value
            for usage in usages
            for value in [self._int_or_none(usage.get("output_tokens", usage.get("completion_tokens")))]
            if value is not None
        ]
        return {
            "input_tokens": sum(input_values) if input_values else None,
            "output_tokens": sum(output_values) if output_values else None,
        }

    def _message_usage(self, message: Any) -> dict[str, Any]:
        usage_metadata = getattr(message, "usage_metadata", None)
        if isinstance(usage_metadata, dict) and self._has_usage_values(usage_metadata):
            return usage_metadata
        response_metadata = getattr(message, "response_metadata", None) or {}
        if not isinstance(response_metadata, dict):
            return {}
        for candidate in (
            response_metadata.get("token_usage"),
            response_metadata.get("usage"),
            response_metadata,
        ):
            if isinstance(candidate, dict) and self._has_usage_values(candidate):
                return candidate
        return {}

    def _has_usage_values(self, usage: dict[str, Any]) -> bool:
        return any(
            key in usage
            for key in ("input_tokens", "output_tokens", "prompt_tokens", "completion_tokens")
        )

    def _int_or_none(self, value: Any) -> int | None:
        try:
            return int(value) if value is not None else None
        except (TypeError, ValueError):
            return None

    def _parse_json(self, content: str) -> dict[str, Any] | None:
        normalized = content.strip()
        if normalized.startswith("```"):
            normalized = normalized.split("\n", 1)[1] if "\n" in normalized else normalized
            if normalized.endswith("```"):
                normalized = normalized[:-3].rstrip()
        try:
            parsed = json.loads(normalized)
        except (TypeError, ValueError, json.JSONDecodeError):
            return None
        return parsed if isinstance(parsed, dict) else None

    def _string_list(self, values: Any, limit: int = 8) -> list[str]:
        if not isinstance(values, list):
            return []
        return [str(value).strip() for value in values if str(value).strip()][:limit]

    def _error_type(self, error: Exception) -> str:
        if isinstance(error, AgentToolError):
            return "tool_error"
        message = str(error).lower()
        if "recursion" in message or "iteration" in message or "maximum" in message:
            return "max_iterations"
        return classify_llm_error(error)

    def _event(
        self,
        event_name: str,
        run_id: str,
        conversation_id: str | None,
        data: dict[str, Any],
    ) -> dict[str, Any]:
        return {
            "event": event_name,
            "runId": run_id,
            "conversationId": conversation_id,
            **data,
        }

    def _format_sse(self, event: dict[str, Any]) -> str:
        event_copy = dict(event)
        event_name = event_copy.pop("event", "message")
        return f"event: {event_name}\ndata: {json.dumps(event_copy, ensure_ascii=False)}\n\n"
