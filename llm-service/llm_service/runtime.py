from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
import time
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

import httpx
import anyio

from fastapi.encoders import jsonable_encoder
from langchain.agents import create_agent
from langchain.agents.middleware import HumanInTheLoopMiddleware
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, SystemMessage
from langgraph.types import Command

from .actions import AgentActionRecord, AgentActionRepository
from .checkpointing import CheckpointManager
from .memory import ContextWindow, ContextWindowManager
from .model_gateway import ModelGateway, message_text
from .observability import FallbackAlertManager, LlmObservability, LlmTraceContext, classify_llm_error
from .planner import AgentPlan, AgentPlanner
from .prompt_manager import PromptManager
from .repository import (
    ConversationRepository,
    ThreadNotFoundError,
    ThreadRecord,
    ThreadScopeError,
)
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
from .turns import (
    AgentTurnRecord,
    AgentTurnRepository,
    TurnConflictError,
    TurnLeaseLostError,
    TurnRegistration,
)
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
    write_tool_interrupts,
    _merge_retrieval,
)


LOGGER = logging.getLogger("llm.stateful_agent")
EventSink = Callable[[str, dict[str, Any]], Awaitable[None]]


class ActionConfirmationRequired(RuntimeError):
    def __init__(self, action: AgentActionRecord):
        self.action = action
        super().__init__("action confirmation is required")


@dataclass(slots=True)
class PreparedTurn:
    registration: TurnRegistration
    thread: ThreadRecord
    window: ContextWindow
    messages: list[dict[str, str]]
    plan: AgentPlan
    memory_context: MemoryContext


class PartialAnswerWriter:
    def __init__(
        self,
        repository: AgentTurnRepository,
        turn_id: str,
        lease_owner: str,
        interval_seconds: float,
        character_threshold: int,
        initial: str = "",
    ):
        self.repository = repository
        self.turn_id = turn_id
        self.lease_owner = lease_owner
        self.interval_seconds = interval_seconds
        self.character_threshold = character_threshold
        self.value = initial
        self._flushed_value = initial
        self._last_flush = 0.0

    async def update(self, value: str, *, force: bool = False) -> None:
        if len(value) >= len(self.value):
            self.value = value
        now = time.monotonic()
        if not force and (
            len(self.value) - len(self._flushed_value) < self.character_threshold
            and now - self._last_flush < self.interval_seconds
        ):
            return
        if self.value == self._flushed_value and not force:
            return
        await self.repository.update_partial(
            self.turn_id, self.lease_owner, self.value
        )
        self._flushed_value = self.value
        self._last_flush = now

    async def reset(self) -> None:
        self.value = ""
        self._flushed_value = ""
        self._last_flush = time.monotonic()
        await self.repository.update_partial(
            self.turn_id, self.lease_owner, ""
        )


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
        turn_repository: AgentTurnRepository | None = None,
        checkpoints: CheckpointManager | None = None,
        action_repository: AgentActionRepository | None = None,
    ):
        self.settings = settings
        self.repository = repository
        self.observability = observability
        self.alerts = alerts or FallbackAlertManager(settings.llm_alert_webhook_url)
        self.model = model or ModelGateway(settings, observability, self.alerts)
        self.prompts = prompts
        self.business_tool_client = business_tool_client
        self.memory_repository = memory_repository or MemoryRepository(
            repository.database,
            content_policy=MemoryContentPolicy(
                settings.agent_memory_content_character_limit
            ),
            pending_days=settings.agent_memory_pending_days,
            task_days=settings.agent_memory_task_days,
            recycle_bin_days=settings.agent_memory_recycle_bin_days,
        )
        self.turn_repository = turn_repository or AgentTurnRepository(
            repository.database
        )
        self.checkpoints = checkpoints or CheckpointManager(repository.database)
        self.action_repository = action_repository or AgentActionRepository(
            repository.database, settings.agent_action_confirmation_minutes
        )
        self.instance_id = str(uuid.uuid4())
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
        self._agents: dict[tuple[str, int, str], Any] = {}
        self._web_domain_cache: tuple[float, list[str]] = (0.0, [])
        self._active_turn_tasks: dict[str, asyncio.Task[Any]] = {}

    async def handle(self, request: AgentMessageRequest) -> AgentMessageResponse:
        prepared = await self._prepare_turn(request)
        completed = self._completed_response(prepared.registration.turn)
        if completed is not None:
            return completed
        writer = self._partial_writer(prepared.registration.turn)
        heartbeat = self._start_heartbeat(prepared.registration.turn)
        try:
            result = await self._run_agent_turn(
                request,
                prepared.thread,
                prepared.messages,
                prepared.window.summary,
                prepared.window.compacted,
                prepared.plan,
                prepared.memory_context,
                prepared.registration,
            )
            result.client_turn_id = request.client_turn_id
            await writer.update(result.answer, force=True)
            await self._persist_response(
                prepared.thread,
                result,
                request.client_turn_id,
                turn=prepared.registration.turn,
                request=request,
            )
            return result
        except asyncio.CancelledError:
            await self._finish_cancelled_or_interrupted(
                request, prepared.registration.turn, writer, "request_cancelled"
            )
            raise
        except ActionConfirmationRequired:
            raise
        except (TurnConflictError, TurnLeaseLostError):
            raise
        except Exception as exc:
            await self._finish_failed_turn(
                request, prepared.registration.turn, writer, exc
            )
            raise
        finally:
            await self._stop_heartbeat(heartbeat)

    async def start_stream(
        self, request: AgentMessageRequest
    ) -> AsyncIterator[str]:
        prepared = await self._prepare_turn(request)
        return self.stream_events(request, prepared=prepared)

    async def stream_events(
        self,
        request: AgentMessageRequest,
        *,
        prepared: PreparedTurn | None = None,
    ) -> AsyncIterator[str]:
        send_stream, receive_stream = anyio.create_memory_object_stream[
            tuple[str, dict[str, Any]]
        ](self.settings.agent_stream_buffer_size)
        run_id = str(uuid.uuid4())
        prepared_turn = prepared or await self._prepare_turn(request)
        completed = self._completed_response(prepared_turn.registration.turn)

        async def publish(
            event_name: str, data: dict[str, Any] | None = None
        ) -> None:
            payload = {"runId": run_id}
            if data:
                payload.update(data)
            await send_stream.send((event_name, payload))

        async def worker() -> None:
            writer = self._partial_writer(prepared_turn.registration.turn)
            heartbeat: asyncio.Task[None] | None = None
            async with send_stream:
                try:
                    if completed is not None:
                        await publish("run.started", {
                            "threadId": completed.thread_id,
                            "clientTurnId": completed.client_turn_id,
                            "resumed": True,
                            "attempt": prepared_turn.registration.turn.attempt_count,
                        })
                        await publish("final", {
                            "threadId": completed.thread_id,
                            "response": completed.model_dump(by_alias=True, mode="json"),
                        })
                        await publish("done")
                        return
                    heartbeat = self._start_heartbeat(
                        prepared_turn.registration.turn
                    )
                    await publish("phase.started", {"phase": "context", "label": "正在准备会话上下文"})
                    await publish("phase.completed", {
                        "phase": "context",
                        "label": "会话上下文已准备",
                        "compacted": prepared_turn.window.compacted,
                    })
                    result = await self._stream_agent_turn(
                        request,
                        run_id,
                        prepared_turn.thread,
                        prepared_turn.messages,
                        prepared_turn.window.summary,
                        prepared_turn.window.compacted,
                        prepared_turn.plan,
                        prepared_turn.memory_context,
                        publish,
                        prepared_turn.registration,
                        writer,
                    )
                    result.client_turn_id = request.client_turn_id
                    await writer.update(result.answer, force=True)
                    await self._persist_response(
                        prepared_turn.thread,
                        result,
                        request.client_turn_id,
                        turn=prepared_turn.registration.turn,
                        request=request,
                    )
                    await publish(
                        "final",
                        {
                            "threadId": result.thread_id,
                            "response": result.model_dump(by_alias=True, mode="json"),
                        },
                    )
                    await publish("done")
                except asyncio.CancelledError:
                    with anyio.CancelScope(shield=True):
                        await self._finish_cancelled_or_interrupted(
                            request,
                            prepared_turn.registration.turn,
                            writer,
                            "stream_disconnected",
                        )
                    raise
                except ActionConfirmationRequired as exc:
                    await publish("action.required", {"action": self._action_event(exc.action)})
                    await publish("done", {
                        "clientTurnId": request.client_turn_id,
                        "turnStatus": "awaiting_confirmation",
                    })
                except TurnConflictError as exc:
                    await publish("error", self._stream_error_payload(
                        request,
                        exc.code,
                        "the requested turn cannot continue",
                        retryable=exc.code in {"thread_busy", "turn_in_progress"},
                    ))
                    await publish("done", {"clientTurnId": request.client_turn_id})
                except Exception as exc:
                    await self._finish_failed_turn(
                        request, prepared_turn.registration.turn, writer, exc
                    )
                    LOGGER.exception("stateful_agent_stream_failed", extra={"runId": run_id})
                    await publish("error", self._stream_error_payload(
                        request,
                        "agent_stream_interrupted",
                        "agent execution interrupted",
                        retryable=True,
                    ))
                    await publish("done", {"clientTurnId": request.client_turn_id})
                finally:
                    if heartbeat is not None:
                        with anyio.CancelScope(shield=True):
                            await self._stop_heartbeat(heartbeat)

        task = asyncio.create_task(worker())
        try:
            async with receive_stream:
                async for event_name, data in receive_stream:
                    yield self._format_sse(event_name, data)
        finally:
            if not task.done():
                task.cancel()
            # A disconnect cancels the StreamingResponse task. Shield the
            # worker's short persistence cleanup from repeated cancellation
            # so the turn reaches interrupted/cancelled before returning.
            with anyio.CancelScope(shield=True):
                try:
                    await task
                except asyncio.CancelledError:
                    pass

    @staticmethod
    def _stream_error_payload(
        request: AgentMessageRequest,
        code: str,
        message: str,
        *,
        retryable: bool,
    ) -> dict[str, Any]:
        return {
            "code": code,
            "errorType": code,
            "message": message,
            "clientTurnId": request.client_turn_id,
            "retryable": retryable,
        }

    async def create_thread(
        self, owner_id: str, scope_type: str, scope_id: str | int
    ) -> ThreadRecord:
        return await self.repository.create_thread(owner_id, scope_type, scope_id)

    async def _prepare_turn(
        self, request: AgentMessageRequest
    ) -> PreparedTurn:
        registration = await self._register_turn(request)
        if registration.turn.status == "cancelled":
            raise TurnConflictError(
                "turn_cancelled", "the requested turn was cancelled"
            )
        thread = await self.repository.get_thread(
            registration.turn.thread_id,
            request.owner_id,
            request.scope_type,
            request.scope_id,
        )
        if registration.turn.status == "completed":
            return PreparedTurn(
                registration,
                thread,
                ContextWindow([], thread.summary, False, thread.summary_through_message_id),
                [],
                self.planner.plan(request.message),
                MemoryContext.empty(),
            )
        await self._capture_explicit_memory(
            request, thread, registration.turn.turn_id
        )
        memory_context = await self._memory_context_for(request)
        window = await self._context_window(thread)
        messages = [
            *window.messages,
            {"role": "user", "content": request.message},
        ]
        return PreparedTurn(
            registration,
            thread,
            window,
            messages,
            self.planner.plan(request.message),
            memory_context,
        )

    async def _register_turn(
        self, request: AgentMessageRequest
    ) -> TurnRegistration:
        request_hash, request_summary = self._request_identity(request)
        try:
            return await self.turn_repository.register(
                client_turn_id=request.client_turn_id,
                requested_thread_id=request.thread_id,
                owner_id=request.owner_id,
                scope_type=request.scope_type,
                scope_id=request.scope_id,
                task_type=request.task_type,
                request_hash=request_hash,
                request_summary=request_summary,
                lease_owner=self.instance_id,
                lease_seconds=self.settings.agent_turn_lease_seconds,
            )
        except LookupError as exc:
            raise ThreadNotFoundError(str(exc)) from exc
        except PermissionError as exc:
            raise ThreadScopeError(str(exc)) from exc

    async def _context_window(self, thread: ThreadRecord) -> ContextWindow:
        current = thread
        for _ in range(3):
            stored = await self.repository.list_context_messages(
                current.thread_id
            )
            window = self.context_manager.build(
                stored,
                current.summary,
                current.summary_through_message_id,
            )
            if not window.compacted:
                return window
            updated = await self.repository.update_summary(
                current.thread_id,
                window.summary,
                expected_cursor=current.summary_through_message_id,
                new_cursor=window.summary_through_message_id,
            )
            if updated:
                return window
            current = await self.repository.get_thread(
                current.thread_id, current.owner_id
            )
        raise RuntimeError("summary_cursor_update_conflict")

    @staticmethod
    def _request_identity(
        request: AgentMessageRequest,
    ) -> tuple[str, dict[str, Any]]:
        payload = request.model_dump(by_alias=True, mode="json")
        payload.pop("clientTurnId", None)
        payload.pop("threadId", None)
        canonical = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        attachment_hashes = [
            hashlib.sha256(item.data_url.encode("utf-8")).hexdigest()
            for item in request.attachments
        ]
        summary = {
            "message": request.message,
            "messageSha256": hashlib.sha256(
                request.message.encode("utf-8")
            ).hexdigest(),
            "messageCharacters": len(request.message),
            "taskType": request.task_type,
            "intent": request.intent,
            "modelId": request.model_id,
            "attachmentSha256": attachment_hashes,
        }
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest(), summary

    @staticmethod
    def _completed_response(
        turn: AgentTurnRecord,
    ) -> AgentMessageResponse | None:
        if turn.status != "completed":
            return None
        if not turn.response:
            raise RuntimeError("completed_turn_response_missing")
        response = AgentMessageResponse.model_validate(turn.response)
        response.thread_id = turn.thread_id
        response.client_turn_id = turn.client_turn_id
        return response

    def _partial_writer(self, turn: AgentTurnRecord) -> PartialAnswerWriter:
        return PartialAnswerWriter(
            self.turn_repository,
            turn.turn_id,
            self.instance_id,
            self.settings.agent_partial_flush_interval_seconds,
            self.settings.agent_partial_flush_characters,
            turn.partial_answer,
        )

    def _start_heartbeat(self, turn: AgentTurnRecord) -> asyncio.Task[None]:
        execution_task = asyncio.current_task()
        if execution_task is None:
            raise RuntimeError("agent turn requires an asyncio task")
        self._active_turn_tasks[turn.turn_id] = execution_task

        async def heartbeat_loop() -> None:
            # This raw asyncio task must not inherit repeated cancellation from
            # the surrounding AnyIO response scope. It is stopped explicitly
            # by ``_stop_heartbeat`` with one ordinary task cancellation.
            with anyio.CancelScope(shield=True):
                while True:
                    await asyncio.sleep(self.settings.agent_turn_heartbeat_seconds)
                    try:
                        cancellation_requested = await self.turn_repository.heartbeat(
                            turn.turn_id,
                            self.instance_id,
                            self.settings.agent_turn_lease_seconds,
                        )
                    except Exception:
                        execution_task.cancel()
                        return
                    if cancellation_requested:
                        execution_task.cancel()
                        return

        return asyncio.create_task(
            heartbeat_loop(), name=f"agent-turn-heartbeat-{turn.turn_id}"
        )

    async def _stop_heartbeat(self, task: asyncio.Task[None]) -> None:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        for turn_id, active in list(self._active_turn_tasks.items()):
            if active is asyncio.current_task() or active.done():
                self._active_turn_tasks.pop(turn_id, None)

    async def cancel_turn(
        self,
        client_turn_id: str,
        owner_id: str,
        scope_type: str,
        scope_id: str | int,
    ) -> AgentTurnRecord:
        turn = await self.turn_repository.request_cancel(
            client_turn_id, owner_id, scope_type, scope_id
        )
        active = self._active_turn_tasks.get(turn.turn_id)
        if active is not None and active is not asyncio.current_task():
            active.cancel()
        return turn

    async def _finish_cancelled_or_interrupted(
        self,
        request: AgentMessageRequest,
        turn: AgentTurnRecord,
        writer: PartialAnswerWriter,
        error_code: str,
    ) -> None:
        try:
            await writer.update(writer.value, force=True)
            cancelled = await self.turn_repository.cancel_requested(turn.turn_id)
            await self.turn_repository.finish_incomplete(
                turn_id=turn.turn_id,
                lease_owner=self.instance_id,
                status="cancelled" if cancelled else "interrupted",
                retryable=not cancelled,
                error_code=error_code,
                user_content=request.message,
                user_metadata=self._user_message_metadata(
                    request,
                    incomplete=True,
                    turn_status="cancelled" if cancelled else "interrupted",
                ),
                partial_answer=writer.value,
                assistant_metadata=self._incomplete_assistant_metadata(
                    request,
                    "cancelled" if cancelled else "interrupted",
                ),
            )
        except Exception:
            LOGGER.exception(
                "agent_turn_interrupt_persist_failed",
                extra={"turnId": turn.turn_id},
            )

    async def _finish_failed_turn(
        self,
        request: AgentMessageRequest,
        turn: AgentTurnRecord,
        writer: PartialAnswerWriter,
        exc: Exception,
    ) -> None:
        retryable = not isinstance(exc, (AssertionError, TypeError, ValueError))
        status = "interrupted" if retryable else "failed"
        try:
            await writer.update(writer.value, force=True)
            await self.turn_repository.finish_incomplete(
                turn_id=turn.turn_id,
                lease_owner=self.instance_id,
                status=status,
                retryable=retryable,
                error_code=type(exc).__name__,
                user_content=request.message,
                user_metadata=self._user_message_metadata(
                    request, incomplete=True, turn_status=status
                ),
                partial_answer=writer.value,
                assistant_metadata=self._incomplete_assistant_metadata(
                    request, status
                ),
            )
        except Exception:
            LOGGER.exception(
                "agent_turn_failure_persist_failed",
                extra={"turnId": turn.turn_id},
            )

    @staticmethod
    def _user_message_metadata(
        request: AgentMessageRequest,
        *,
        incomplete: bool,
        turn_status: str,
    ) -> dict[str, Any]:
        return {
            "intent": request.intent,
            "taskType": request.task_type,
            "clientTurnId": request.client_turn_id,
            "incomplete": incomplete,
            "turnStatus": turn_status,
        }

    @staticmethod
    def _incomplete_assistant_metadata(
        request: AgentMessageRequest, turn_status: str
    ) -> dict[str, Any]:
        return {
            "status": "incomplete",
            "taskType": request.task_type,
            "clientTurnId": request.client_turn_id,
            "incomplete": True,
            "turnStatus": turn_status,
        }

    async def _get_or_create_thread(self, request: AgentMessageRequest) -> ThreadRecord:
        if request.thread_id:
            return await self.repository.require_thread(
                request.thread_id, request.owner_id, request.scope_type, request.scope_id
            )
        return await self.create_thread(
            request.owner_id, request.scope_type, request.scope_id
        )

    async def _memory_context_for(self, request: AgentMessageRequest) -> MemoryContext:
        if not self.settings.agent_memory_enabled:
            return MemoryContext.empty()
        if request.task_type == "RESOURCE_DISCOVERY":
            return MemoryContext.empty()
        query_parts = [request.message, request.grade or "", request.theme or ""]
        if request.task_payload:
            query_parts.append(
                json.dumps(request.task_payload, ensure_ascii=False, default=str)
            )
        return await self.memory_repository.recall(
            request.owner_id,
            request.scope_type,
            request.scope_id,
            query="\n".join(item for item in query_parts if item),
            task_limit=self.settings.agent_memory_task_limit,
            character_limit=self.settings.agent_memory_context_character_limit,
        )

    async def _capture_explicit_memory(
        self,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        turn_id: str | None = None,
    ) -> MemoryRecord | None:
        if not self.settings.agent_memory_enabled:
            return None
        if request.task_type == "RESOURCE_DISCOVERY":
            return None
        setting = await self.memory_repository.get_setting(
            request.owner_id, request.scope_type, request.scope_id
        )
        if not setting.enabled:
            return None
        draft = self.explicit_memory_extractor.extract(request.message)
        if draft is None:
            return None
        return await self.memory_repository.create_memory(
            request.owner_id,
            request.scope_type,
            request.scope_id,
            memory_type=draft.memory_type,
            field_key=draft.field_key,
            content=draft.content,
            status="active",
            source="explicit_chat",
            source_thread_id=thread.thread_id,
            source_turn_id=turn_id,
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

    async def _create_agent_for(
        self, config: ModelConfig, checkpoint_namespace: str
    ) -> Any:
        if not config.configured():
            raise RuntimeError("model_unavailable")
        key = (config.model, config.fallback_level, checkpoint_namespace)
        if key not in self._agents:
            interrupt_on = write_tool_interrupts(
                self.settings.agent_write_tools_enabled
            )
            self._agents[key] = create_agent(
                self.model.build_model(config),
                tools=AGENT_TOOLS,
                system_prompt=await self._load_prompt(),
                checkpointer=self.checkpoints.scoped_saver(
                    checkpoint_namespace
                ),
                middleware=(
                    [HumanInTheLoopMiddleware(interrupt_on=interrupt_on)]
                    if interrupt_on
                    else []
                ),
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
        turn: AgentTurnRecord | None = None,
        checkpoint_namespace: str | None = None,
    ) -> dict[str, Any]:
        invoke_config: dict[str, Any] = {
            "recursion_limit": max(3, plan.max_tool_rounds * 2 + 3),
        }
        if turn is not None and checkpoint_namespace:
            invoke_config["configurable"] = {"thread_id": turn.turn_id}
        if self.observability is not None:
            metadata: dict[str, Any] = {
                "intent": request.intent or "",
                "scopeType": request.scope_type,
                "modelRole": "primary" if config.fallback_level == 0 else (
                    "fallback" if config.fallback_level == 1 else "lightweight"
                ),
                "fallbackLevel": config.fallback_level,
            }
            retrieval_trace = self._retrieval_trace_summary(request.context)
            if retrieval_trace:
                metadata["retrievalTrace"] = retrieval_trace
            trace_context = LlmTraceContext(
                feature="stateful-agent",
                user_id=request.owner_id,
                session_id=thread.thread_id,
                expected_json=True,
                metadata=metadata,
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
        registration: TurnRegistration,
    ) -> AgentMessageResponse:
        if request.task_type != "CHAT":
            return await self._run_structured_task(
                request,
                thread,
                compacted,
                memory_context,
                registration.turn.turn_id,
            )
        prefetched_executions, prefetched_reasons = await self._prefetch_planned_tools(
            request, thread, plan, turn=registration.turn
        )
        executions: list[ToolExecution] = list(prefetched_executions)
        model_attempts = self._model_attempts(request.model_id)
        resume_namespace = registration.turn.checkpoint_namespace
        for attempt_index, (config, injected_agent) in enumerate(model_attempts):
            checkpoint_namespace = f"chat-v1/model-attempt-{attempt_index + 1}"
            if (
                registration.resumed
                and resume_namespace
                and checkpoint_namespace != resume_namespace
            ):
                continue
            await self.turn_repository.set_checkpoint_namespace(
                registration.turn.turn_id,
                self.instance_id,
                checkpoint_namespace,
            )
            runtime = ToolRuntimeContext(
                thread_id=thread.thread_id,
                turn_id=registration.turn.turn_id,
                call_namespace=checkpoint_namespace,
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
                agent = injected_agent or await self._create_agent_for(
                    config, checkpoint_namespace
                )
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
                    turn=registration.turn,
                    checkpoint_namespace=checkpoint_namespace,
                    resumed=registration.resumed,
                )
                return self._with_model_metadata(result, config)
            except (ActionConfirmationRequired, TurnConflictError, TurnLeaseLostError):
                raise
            except Exception as exc:
                executions.extend(runtime.executions[len(prefetched_executions):])
                if await self._has_executing_action(registration.turn.turn_id):
                    raise
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
                    await self.alerts.fallback(
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
                    resume_namespace = None
            finally:
                reset_tool_runtime(token)

        await self.alerts.exhausted(
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
        registration: TurnRegistration,
        partial_writer: PartialAnswerWriter,
    ) -> AgentMessageResponse:
        if request.task_type != "CHAT":
            return await self._stream_structured_task(
                request,
                thread,
                compacted,
                memory_context,
                emit,
                registration,
            )
        primary = self._primary_model_config(request.model_id)
        await emit(
            "run.started",
            {
                "threadId": thread.thread_id,
                "clientTurnId": request.client_turn_id,
                "resumed": registration.resumed,
                "attempt": registration.turn.attempt_count,
                "provider": primary.provider,
                "model": primary.model,
            },
        )
        await emit("phase.started", {
            "phase": "reasoning",
            "label": "正在分析问题并规划处理步骤",
            "recommendedTools": plan.recommended_tools,
        })
        prefetched_executions, prefetched_reasons = await self._prefetch_planned_tools(
            request, thread, plan, emit, turn=registration.turn
        )
        executions: list[ToolExecution] = list(prefetched_executions)
        model_attempts = self._model_attempts(request.model_id)
        if not model_attempts:
            await emit(
                "model.started",
                {
                    "provider": primary.provider,
                    "model": primary.model,
                    "fallbackLevel": primary.fallback_level,
                },
            )
            await emit(
                "model.failed",
                {
                    "provider": primary.provider,
                    "model": primary.model,
                    "fallbackLevel": primary.fallback_level,
                    "errorType": "not_configured",
                },
            )
        resume_namespace = registration.turn.checkpoint_namespace
        for attempt_index, (config, injected_agent) in enumerate(model_attempts):
            checkpoint_namespace = f"chat-v1/model-attempt-{attempt_index + 1}"
            if (
                registration.resumed
                and resume_namespace
                and checkpoint_namespace != resume_namespace
            ):
                continue
            await self.turn_repository.set_checkpoint_namespace(
                registration.turn.turn_id,
                self.instance_id,
                checkpoint_namespace,
            )
            if registration.resumed or attempt_index > 0:
                await emit(
                    "response.reset",
                    {
                        "clientTurnId": request.client_turn_id,
                        "reason": (
                            "checkpoint_resumed"
                            if registration.resumed
                            else "model_fallback"
                        ),
                    },
                )
                await partial_writer.reset()
            runtime = ToolRuntimeContext(
                thread_id=thread.thread_id,
                turn_id=registration.turn.turn_id,
                call_namespace=checkpoint_namespace,
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
            await emit(
                "model.started",
                {
                    "provider": config.provider,
                    "model": config.model,
                    "fallbackLevel": config.fallback_level,
                },
            )
            try:
                agent = injected_agent or await self._create_agent_for(
                    config, checkpoint_namespace
                )
                await emit("phase.completed", {
                    "phase": "reasoning",
                    "label": "分析完成，开始执行",
                    "recommendedTools": plan.recommended_tools,
                })
                await emit("phase.started", {"phase": "response", "label": "正在生成回答"})
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
                    turn=registration.turn,
                    checkpoint_namespace=checkpoint_namespace,
                    resumed=registration.resumed,
                    partial_writer=partial_writer,
                )
                result = self._with_model_metadata(result, config)
                await emit(
                    "model.completed",
                    {
                        "provider": config.provider,
                        "model": config.model,
                        "fallbackLevel": config.fallback_level,
                    },
                )
                await emit("phase.completed", {"phase": "response", "label": "回答生成完成"})
                return result
            except (ActionConfirmationRequired, TurnConflictError, TurnLeaseLostError):
                raise
            except Exception as exc:
                executions.extend(runtime.executions[len(prefetched_executions):])
                if await self._has_executing_action(registration.turn.turn_id):
                    raise
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
                resume_namespace = None
                await emit(
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
            await self.alerts.exhausted(
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
        turn: AgentTurnRecord | None = None,
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
            turn_id=turn.turn_id if turn else None,
            call_namespace="prefetch",
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
                output = await runtime.run(
                    "retrieve_knowledge",
                    {"query": request.message, "limit": 5},
                    lambda: self._retrieve_with_augmentation(request, thread),
                )
                try:
                    result = json.loads(output)
                except (TypeError, ValueError, json.JSONDecodeError):
                    result = {}
                if isinstance(result, dict):
                    _merge_retrieval(runtime, result)
            if "query_graph_relations" in plan.recommended_tools:
                await query_graph_relations.ainvoke(
                    {"query": request.message, "limit": 5}
                )
        finally:
            reset_tool_runtime(token)
        return list(runtime.executions), list(runtime.degraded_reasons)

    async def _retrieve_with_augmentation(
        self, request: AgentMessageRequest, thread: ThreadRecord
    ) -> dict[str, Any]:
        if self.business_tool_client is None:
            return {"retrievalStatus": "degraded", "degradedReason": "business_tool_unconfigured"}
        rewrite = await self._controlled_query_rewrite(request, thread)
        payload = self._retrieval_payload(request, rewrite)
        try:
            first = await self.business_tool_client.query_knowledge(payload)
        except Exception as exc:
            return {"retrievalStatus": "degraded", "degradedReason": type(exc).__name__.lower()}
        trace = first.setdefault("retrievalTrace", {}) if isinstance(first, dict) else {}
        trace["queryRewriteStatus"] = rewrite["status"]
        if not isinstance(first, dict) or not trace.get("augmentationRequired"):
            return first
        domains = await self._authoritative_domains()
        hyde_task = self._generate_hyde(rewrite["searchQuery"])
        web_task = self._search_authoritative_web(rewrite["searchQuery"], domains)
        hyde, web = await asyncio.gather(hyde_task, web_task)
        if not hyde and not web:
            trace["augmentationReason"] = f"{trace.get('augmentationReason') or 'low_recall'}:no_augmentation_available"
            return first
        augmented = dict(payload)
        augmented["hydeQuery"] = hyde or None
        augmented["webEvidence"] = web
        try:
            final = await self.business_tool_client.query_knowledge(augmented)
        except Exception:
            trace["augmentationReason"] = f"{trace.get('augmentationReason') or 'low_recall'}:augmentation_failed"
            return first
        final_trace = final.setdefault("retrievalTrace", {}) if isinstance(final, dict) else {}
        final_trace["queryRewriteStatus"] = rewrite["status"]
        final_trace["hydeStatus"] = "ok" if hyde else "skipped"
        final_trace["webStatus"] = "ok" if web else "skipped"
        return final

    def _retrieval_payload(self, request: AgentMessageRequest, rewrite: dict[str, Any]) -> dict[str, Any]:
        return {
            "actor": request.context.actor,
            "scope": request.context.scope,
            "query": rewrite["searchQuery"],
            "intent": rewrite.get("intent") or request.intent,
            "grade": rewrite.get("grade") or request.grade,
            "theme": rewrite.get("theme") or request.theme,
            "topK": 5,
        }

    async def _controlled_query_rewrite(self, request: AgentMessageRequest, thread: ThreadRecord) -> dict[str, Any]:
        original = request.message.strip()
        trigger = bool(re.search(r"(?:这个|那个|这所|那所|这里|那里|它|该)(?:学校|资源|地方|场馆|遗址)?", original))
        if not trigger:
            return {"status": "skipped", "searchQuery": original}
        context = {
            "school": request.context.school,
            "region": request.context.region,
            "resource": request.context.resource,
            "summary": thread.summary[-1200:],
            "grade": request.grade,
            "theme": request.theme,
        }
        prompt = (
            "你是检索条件改写器。只补全指代，不得生成任何新事实。"
            "输出 JSON：searchQuery、intent、grade、theme、confidence。"
            "如果上下文不足，searchQuery 必须保持原问题，confidence 低于 0.70。\n"
            f"原问题：{original}\n可信上下文：{json.dumps(context, ensure_ascii=False)}"
        )
        def valid(payload: dict[str, Any]) -> bool:
            return isinstance(payload.get("searchQuery"), str) and isinstance(payload.get("confidence"), (int, float))
        result = await self.model.generate_json(prompt, validator=valid, model_id=request.model_id)
        if not result:
            return {"status": "failed", "searchQuery": original}
        query = str(result.get("searchQuery") or "").strip()
        confidence = float(result.get("confidence") or 0.0)
        if not query or len(query) > 600 or confidence < self.settings.retrieval_rewrite_confidence:
            return {"status": "fallback", "searchQuery": original}
        return {
            "status": "applied", "searchQuery": query,
            "intent": str(result.get("intent") or "").strip() or None,
            "grade": str(result.get("grade") or "").strip() or None,
            "theme": str(result.get("theme") or "").strip() or None,
        }

    async def _generate_hyde(self, query: str) -> str | None:
        prompt = (
            "生成用于向量检索的假设性资料摘要，不是最终答案，不得捏造具体人名、日期或来源。"
            "只输出 JSON：{\"hypothesis\":\"...\"}，限 420 个中文字符。\n问题：" + query
        )
        result = await self.model.generate_json(
            prompt, validator=lambda value: isinstance(value.get("hypothesis"), str)
        )
        hypothesis = str((result or {}).get("hypothesis") or "").strip()
        return hypothesis[: self.settings.retrieval_hyde_max_characters] or None

    async def _authoritative_domains(self) -> list[str]:
        now = time.monotonic()
        cached_at, cached = self._web_domain_cache
        if cached and now - cached_at < self.settings.retrieval_web_cache_seconds:
            return cached
        if self.business_tool_client is None:
            return []
        try:
            domains = await self.business_tool_client.web_source_domains()
        except Exception:
            return []
        self._web_domain_cache = (now, domains)
        return domains

    async def _search_authoritative_web(self, query: str, domains: list[str]) -> list[dict[str, Any]]:
        if not self.settings.tavily_api_key or not domains:
            return []
        body = {"api_key": self.settings.tavily_api_key, "query": query, "search_depth": "basic",
                "max_results": 5, "include_domains": domains, "include_raw_content": False,
                "include_answer": False}
        try:
            async with httpx.AsyncClient(timeout=self.settings.tavily_timeout_seconds) as client:
                response = await client.post(self.settings.tavily_base_url, json=body)
                response.raise_for_status()
                values = response.json().get("results") or []
        except (httpx.HTTPError, ValueError):
            return []
        result: list[dict[str, Any]] = []
        for index, item in enumerate(values):
            url = str(item.get("url") or "").strip()
            host = (urlparse(url).hostname or "").lower()
            if not url.startswith("https://") or not self._allowed_web_host(host, domains):
                continue
            excerpt = str(item.get("content") or "").replace("\x00", " ").strip()
            if not excerpt or re.search(
                r"ignore (?:all|previous)|system prompt|ignore .*instructions|disregard|"
                r"忽略.*(?:指令|之前|系统)|系统提示",
                excerpt,
                re.I,
            ):
                continue
            result.append({"title": str(item.get("title") or host)[:240], "url": url,
                           "domain": host, "excerpt": excerpt[:900], "rank": index + 1,
                           "providerScore": item.get("score")})
        return result

    @staticmethod
    def _allowed_web_host(host: str, domains: list[str]) -> bool:
        return any(host == domain or host.endswith("." + domain) for domain in domains)

    async def _run_structured_task(
        self,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        compacted: bool,
        memory_context: MemoryContext,
        turn_id: str,
    ) -> AgentMessageResponse:
        prompt_key, validator = self._structured_task_config(request)
        selection, run_id = await self._start_structured_prompt(
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
            memory_candidates = await self._persist_inferred_candidates(
                request,
                thread.thread_id,
                generated.get("memoryCandidates")
                if isinstance(generated.get("memoryCandidates"), list)
                else [],
                source="teaching_plan",
                source_turn_id=turn_id,
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
        await self._finish_structured_prompt(
            run_id, status, elapsed, result, error_message
        )
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
        registration: TurnRegistration,
    ) -> AgentMessageResponse:
        prompt_key, validator = self._structured_task_config(request)
        selection, prompt_run_id = await self._start_structured_prompt(
            prompt_key, request, thread, memory_context
        )
        primary = self._primary_model_config(request.model_id)
        await emit(
            "run.started",
            {
                "threadId": thread.thread_id,
                "clientTurnId": request.client_turn_id,
                "resumed": registration.resumed,
                "attempt": registration.turn.attempt_count,
                "taskType": request.task_type,
                "provider": primary.provider,
                "model": primary.model,
            },
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
                await emit("model.started", data)
            elif event_name == "token":
                if teaching_plan_parser is not None:
                    for patch in teaching_plan_parser.feed(str(data.get("delta") or "")):
                        await emit("plan.patch", {"patch": patch})
                # 其他结构化任务仍不向前端发送原始 JSON 分片。
                continue
            elif event_name == "fallback":
                error_message = str(data.get("errorType") or "model_failed")
                await emit("model.failed", data)
            elif event_name == "complete":
                generated = data.get("result") if isinstance(data.get("result"), dict) else None
                metadata = {key: value for key, value in data.items() if key != "result"}
                await emit("model.completed", metadata)
            elif event_name == "exhausted":
                error_message = "fallback_exhausted"

        if generated is None:
            result = self._structured_fallback(request)
            status = "degraded"
        else:
            memory_candidates = await self._persist_inferred_candidates(
                request,
                thread.thread_id,
                generated.get("memoryCandidates")
                if isinstance(generated.get("memoryCandidates"), list)
                else [],
                source="teaching_plan",
                source_turn_id=registration.turn.turn_id,
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
        await self._finish_structured_prompt(
            prompt_run_id, status, elapsed, result, error_message
        )
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

    async def _start_structured_prompt(
        self,
        prompt_key: str,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        memory_context: MemoryContext,
    ):
        if self.prompts is None:
            raise RuntimeError("prompt_manager_unavailable")
        subject_key = f"{request.scope_type}:{request.scope_id}"
        selection = await self.prompts.resolve(
            prompt_key,
            subject_key,
            task_context(request, memory_context.prompt),
        )
        run_id = await self.prompts.start_run(
            selection, subject_key, self._primary_model_config(request.model_id).model, len(selection.content)
        )
        return selection, run_id

    async def _finish_structured_prompt(
        self, run_id: str, status: str, elapsed: int, result: dict[str, Any], error_message: str
    ) -> None:
        if self.prompts is not None:
            await self.prompts.finish_run(
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
            clientTurnId=request.client_turn_id,
            taskType=request.task_type,
            answer=task_answer(request, result),
            status=status,
            generationStatus=status,
            retrievalStatus=self._retrieval_status(request.context),
            retrievalMethods=self._retrieval_methods(request.context),
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
        if not any(item is not None for item in values):
            values = [
                self._citation_by_id(trusted, citation_id)
                for citation_id in self._ordered_evidence_citation_ids(trusted)[:5]
            ]
        return [item for item in values if item is not None]

    async def _persist_response(
        self,
        thread: ThreadRecord,
        result: AgentMessageResponse,
        client_turn_id: str | None = None,
        *,
        turn: AgentTurnRecord | None = None,
        request: AgentMessageRequest | None = None,
    ) -> None:
        metadata = {
            "status": result.status,
            "taskType": result.task_type,
            "citations": [item.citation_id for item in result.citations],
            "retrievalMethods": result.retrieval_methods,
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
            "responseSnapshot": self._response_snapshot(result),
        }
        if client_turn_id:
            metadata["clientTurnId"] = client_turn_id
        metadata["incomplete"] = False
        metadata["turnStatus"] = "completed"
        if turn is not None:
            if request is None:
                raise ValueError("request is required when completing an agent turn")
            await self.turn_repository.complete(
                turn_id=turn.turn_id,
                lease_owner=self.instance_id,
                user_content=request.message,
                user_metadata=self._user_message_metadata(
                    request, incomplete=False, turn_status="completed"
                ),
                assistant_content=result.answer,
                assistant_metadata=metadata,
                response=result.model_dump(by_alias=True, mode="json"),
            )
            return
        await self.repository.append_message(
            thread.thread_id,
            "assistant",
            result.answer,
            metadata,
        )

    @staticmethod
    def _response_snapshot(result: AgentMessageResponse) -> dict[str, Any]:
        return {
            "schemaVersion": 1,
            "status": result.status,
            "generationStatus": result.generation_status,
            "retrievalStatus": result.retrieval_status,
            "retrievalMethods": list(result.retrieval_methods),
            "citations": [
                item.model_dump(by_alias=True, mode="json")
                for item in result.citations[:5]
            ],
            "relatedResources": list(result.related_resources),
            "followUpQuestions": list(result.follow_up_questions),
            "provider": result.provider,
            "model": result.model,
            "fallbackLevel": result.fallback_level,
            "toolExecutions": [
                {
                    "name": item.name,
                    "status": item.status,
                    "durationMs": item.duration_ms,
                }
                for item in result.tool_executions
            ],
            "contextCompacted": result.context_compacted,
            "memoryApplied": (
                result.memory_applied.model_dump(by_alias=True, mode="json")
                if result.memory_applied
                else None
            ),
        }

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
        turn: AgentTurnRecord | None = None,
        checkpoint_namespace: str | None = None,
        resumed: bool = False,
    ) -> AgentMessageResponse:
        lc_messages = self._build_messages(
            messages, summary, plan, request, memory_context
        )
        runtime = tool_runtime or ToolRuntimeContext(
            thread_id=thread.thread_id,
            turn_id=turn.turn_id if turn else None,
            call_namespace=checkpoint_namespace or "graph",
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
        durable_resume = bool(
            resumed
            and turn is not None
            and checkpoint_namespace
            and hasattr(target_agent, "aget_state")
            and await self.checkpoints.has_checkpoint(
                turn.turn_id, checkpoint_namespace
            )
        )
        resume_action = (
            await self.action_repository.resumable_for_turn(turn.turn_id)
            if durable_resume and turn is not None
            else None
        )
        graph_input: Any = None if durable_resume else {"messages": lc_messages}
        if resume_action is not None and resume_action.status in {
            "approved", "rejected", "executing"
        }:
            decision = "approve" if resume_action.status == "approved" else "reject"
            if decision == "approve":
                resume_action = await self.action_repository.mark_executing(
                    resume_action.action_id
                )
            elif resume_action.status == "executing":
                decision = "approve"
            runtime.action_id = resume_action.action_id
            graph_input = Command(resume={"decisions": [{"type": decision}]})
        result = await target_agent.ainvoke(
            graph_input,
            config=self._agent_invoke_config(
                request,
                thread,
                model_config or self._primary_model_config(),
                plan,
                turn,
                checkpoint_namespace,
            ),
        )
        await self._raise_for_pending_action(
            target_agent,
            turn,
            request,
            thread,
            model_config,
            plan,
            checkpoint_namespace,
        )
        await self._finalize_resumed_action(resume_action, runtime.executions)
        return await self._response_from_model_result(
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
            source_turn_id=turn.turn_id if turn else None,
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
        turn: AgentTurnRecord | None = None,
        checkpoint_namespace: str | None = None,
        resumed: bool = False,
        partial_writer: PartialAnswerWriter | None = None,
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
        durable_resume = bool(
            resumed
            and turn is not None
            and checkpoint_namespace
            and hasattr(target_agent, "aget_state")
            and await self.checkpoints.has_checkpoint(
                turn.turn_id, checkpoint_namespace
            )
        )
        resume_action = (
            await self.action_repository.resumable_for_turn(turn.turn_id)
            if durable_resume and turn is not None
            else None
        )
        graph_input: Any = None if durable_resume else {"messages": lc_messages}
        if resume_action is not None and resume_action.status in {
            "approved", "rejected", "executing"
        }:
            decision = "approve" if resume_action.status == "approved" else "reject"
            if decision == "approve":
                resume_action = await self.action_repository.mark_executing(
                    resume_action.action_id
                )
                await emit("action.started", {"actionId": resume_action.action_id})
            elif resume_action.status == "executing":
                decision = "approve"
                await emit(
                    "action.started",
                    {"actionId": resume_action.action_id, "resumed": True},
                )
            runtime.action_id = resume_action.action_id
            graph_input = Command(resume={"decisions": [{"type": decision}]})
        if hasattr(target_agent, "astream"):
            async for chunk in target_agent.astream(
                graph_input,
                config=self._agent_invoke_config(
                    request,
                    thread,
                    model_config or self._primary_model_config(),
                    plan,
                    turn,
                    checkpoint_namespace,
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
                        await emit("token", {"delta": partial_answer[emitted_answer_length:]})
                        emitted_answer_length = len(partial_answer)
                        if partial_writer is not None:
                            await partial_writer.update(partial_answer)
            if durable_resume and not model_buffer:
                snapshot = await target_agent.aget_state(
                    self._agent_invoke_config(
                        request,
                        thread,
                        model_config or self._primary_model_config(),
                        plan,
                        turn,
                        checkpoint_namespace,
                    )
                )
                values = getattr(snapshot, "values", {}) or {}
                if isinstance(values, dict):
                    model_messages = list(values.get("messages") or [])
                    model_buffer = self._last_ai_message_text(model_messages)
            await self._raise_for_pending_action(
                target_agent,
                turn,
                request,
                thread,
                model_config,
                plan,
                checkpoint_namespace,
            )
        else:
            result = await target_agent.ainvoke(
                graph_input,
                config=self._agent_invoke_config(
                    request,
                    thread,
                    model_config or self._primary_model_config(),
                    plan,
                    turn,
                    checkpoint_namespace,
                ),
            )
            model_messages = result.get("messages", []) if isinstance(result, dict) else []
            model_buffer = self._last_ai_message_text(model_messages)
            await self._raise_for_pending_action(
                target_agent,
                turn,
                request,
                thread,
                model_config,
                plan,
                checkpoint_namespace,
            )

        parse_messages = [AIMessage(content=model_buffer)] if model_buffer else model_messages
        response = await self._response_from_model_result(
            {"messages": parse_messages}, trusted, thread.thread_id, compacted,
            runtime.executions, runtime.degraded_reasons, request.message,
            request.grade, request.theme, memory_context, request,
            turn.turn_id if turn else None,
        )
        if emitted_answer_length < len(response.answer):
            await self._emit_answer_chunks(response.answer[emitted_answer_length:], emit)
        if partial_writer is not None:
            await partial_writer.update(response.answer, force=True)
        await self._finalize_resumed_action(resume_action, runtime.executions, emit)
        return response

    async def _raise_for_pending_action(
        self,
        agent: Any,
        turn: AgentTurnRecord | None,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        model_config: ModelConfig | None,
        plan: AgentPlan,
        checkpoint_namespace: str | None,
    ) -> None:
        if turn is None or not hasattr(agent, "aget_state"):
            return
        snapshot = await agent.aget_state(
            self._agent_invoke_config(
                request,
                thread,
                model_config or self._primary_model_config(),
                plan,
                turn,
                checkpoint_namespace,
            )
        )
        pending: list[tuple[str, dict[str, Any]]] = []
        for task in getattr(snapshot, "tasks", ()) or ():
            for interrupt in getattr(task, "interrupts", ()) or ():
                value = getattr(interrupt, "value", None)
                if not isinstance(value, dict):
                    continue
                requests = value.get("action_requests")
                if not isinstance(requests, list):
                    continue
                for item in requests:
                    if isinstance(item, dict):
                        pending.append((str(getattr(interrupt, "id", "") or ""), item))
        if not pending:
            return
        if len(pending) != 1:
            raise RuntimeError("multiple write actions in one model step are not allowed")
        interrupt_id, action_request = pending[0]
        tool_name = str(action_request.get("name") or "").strip()
        arguments = action_request.get("args")
        if not tool_name or not isinstance(arguments, dict):
            raise RuntimeError("invalid write action interrupt")
        logical_call_id = interrupt_id or hashlib.sha256(
            json.dumps(action_request, sort_keys=True, default=str).encode("utf-8")
        ).hexdigest()
        action = await self.action_repository.create_or_get(
            turn_id=turn.turn_id,
            logical_call_id=logical_call_id,
            tool_name=tool_name,
            arguments=arguments,
            sanitized_arguments={
                key: str(value)[:500]
                for key, value in arguments.items()
                if "key" not in key.lower() and "token" not in key.lower()
            },
            risk_level="HIGH",
            requires_confirmation=True,
        )
        raise ActionConfirmationRequired(action)

    async def _has_executing_action(self, turn_id: str) -> bool:
        action = await self.action_repository.resumable_for_turn(turn_id)
        return action is not None and action.status == "executing"

    async def _finalize_resumed_action(
        self,
        action: AgentActionRecord | None,
        executions: list[ToolExecution],
        emit: EventSink | None = None,
    ) -> None:
        if action is None or action.status != "executing":
            return
        execution = next(
            (item for item in reversed(executions) if item.name == action.tool_name), None
        )
        if execution is None:
            return
        if execution.status == "completed":
            completed = await self.action_repository.mark_succeeded(
                action.action_id, result_summary="写操作已完成"
            )
            if emit is not None:
                await emit("action.completed", {"actionId": completed.action_id})
        elif execution.status == "failed":
            failed = await self.action_repository.mark_failed(
                action.action_id, "write_tool_failed"
            )
            if emit is not None:
                await emit(
                    "action.failed",
                    {"actionId": failed.action_id, "errorCode": failed.error_code},
                )

    @staticmethod
    def _action_event(action: AgentActionRecord) -> dict[str, Any]:
        return {
            "actionId": action.action_id,
            "clientTurnId": action.client_turn_id,
            "threadId": action.thread_id,
            "toolName": action.tool_name,
            "title": f"确认执行 {action.tool_name}",
            "summary": "该操作会修改业务数据，请确认是否继续。",
            "arguments": action.sanitized_arguments,
            "riskLevel": action.risk_level,
            "status": action.status,
            "expiresAt": action.expires_at.isoformat(),
        }

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
        chunks = {
            str(item.get("citationId")): item
            for item in retrieval.get("chunks", [])
            if isinstance(item, dict) and item.get("citationId")
        }
        graph_facts = {
            str(item.get("citationId")): item
            for item in retrieval.get("graphFacts", [])
            if isinstance(item, dict) and item.get("citationId")
        }
        ranked_candidates = sorted(
            (
                item for item in trusted.citation_candidates
                if isinstance(item, dict)
                and item.get("citationId")
                and item.get("evidenceType") in {"chunk", "graph_fact"}
                and item.get("rank") is not None
            ),
            key=lambda item: (int(item.get("rank") or 10_000), str(item.get("citationId"))),
        )
        joint_evidence: list[dict[str, Any]] = []
        graph_count = 0
        for candidate in ranked_candidates:
            evidence_type = str(candidate.get("evidenceType"))
            if evidence_type == "graph_fact" and graph_count >= 3:
                continue
            citation_id = str(candidate.get("citationId"))
            source = chunks.get(citation_id) or graph_facts.get(citation_id) or {}
            joint_evidence.append({**source, **candidate})
            if evidence_type == "graph_fact":
                graph_count += 1
            if len(joint_evidence) >= 8:
                break
        if not joint_evidence:
            joint_evidence = [
                *list(chunks.values()), *list(graph_facts.values())
            ][:8]
        evidence = {
            "retrievalStatus": retrieval.get("retrievalStatus", "empty"),
            "evidence": joint_evidence,
        }
        if not joint_evidence:
            return ""
        serialized = json.dumps(evidence, ensure_ascii=False, default=str)
        return (
            "业务服务已在本轮生成前完成可信范围内的检索。只能使用以下证据回答，"
            "citationIds 只能来自其中的 citationId；不要声称没有调用工具，也不要补造事实：\n"
            f"{serialized[:6000]}"
        )

    async def _response_from_model_result(
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
        source_turn_id: str | None = None,
    ) -> AgentMessageResponse:
        parsed = self._parse_model_output(result)
        memory_candidates = (
            await self._persist_inferred_candidates(
                request,
                thread_id,
                parsed.memory_candidates,
                source_turn_id=source_turn_id,
            )
            if request is not None
            else []
        )
        allowed = self._allowed_citations(trusted)
        citations = [self._citation_by_id(trusted, item) for item in parsed.citation_ids if item in allowed]
        citations = [item for item in citations if item is not None]
        if not citations:
            citations = [
                self._citation_by_id(trusted, item)
                for item in self._ordered_evidence_citation_ids(trusted)[:5]
            ]
            citations = [item for item in citations if item is not None]
        answer = parsed.answer.strip()
        normalized_reasons = list(dict.fromkeys(degraded_reasons or []))
        return AgentMessageResponse(
            threadId=thread_id,
            clientTurnId=request.client_turn_id if request is not None else "",
            answer=answer or "暂时无法生成有效回答。",
            status="completed" if answer else "incomplete",
            generationStatus="completed" if answer else "degraded",
            retrievalStatus=("degraded" if normalized_reasons else self._retrieval_status(trusted)),
            retrievalMethods=self._retrieval_methods(trusted),
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

    async def _persist_inferred_candidates(
        self,
        request: AgentMessageRequest,
        thread_id: str,
        candidates: list[Any] | None,
        *,
        source: str = "inferred_chat",
        source_turn_id: str | None = None,
    ) -> list[MemoryItem]:
        if not candidates or not self.settings.agent_memory_enabled:
            return []
        if request.task_type == "RESOURCE_DISCOVERY":
            return []
        if self.explicit_memory_extractor.extract(request.message) is not None:
            return []
        setting = await self.memory_repository.get_setting(
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
                record = await self.memory_repository.create_memory(
                    request.owner_id,
                    request.scope_type,
                    request.scope_id,
                    memory_type=str(payload.get("memoryType") or ""),
                    field_key=payload.get("fieldKey"),
                    content=str(payload.get("content") or ""),
                    status="pending",
                    source=source,
                    source_thread_id=thread_id,
                    source_turn_id=source_turn_id,
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
            await emit("token", {"delta": answer[index:index + size]})
            # Let the SSE consumer flush each queued chunk instead of making
            # the fallback answer appear as one large response.
            await asyncio.sleep(0)

    async def _load_prompt(self) -> str:
        if self.prompts is not None:
            try:
                return await self.prompts.active_content("agent")
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
        citations = [
            self._citation_by_id(trusted, item)
            for item in self._ordered_evidence_citation_ids(trusted)[:5]
        ]
        return AgentMessageResponse(
            threadId=thread_id,
            clientTurnId=request.client_turn_id,
            answer=answer,
            status=status,
            generationStatus="degraded",
            retrievalStatus=self._retrieval_status(trusted),
            retrievalMethods=self._retrieval_methods(trusted),
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

    def _retrieval_trace_summary(self, trusted: TrustedContext) -> dict[str, Any]:
        retrieval = trusted.retrieval or {}
        trace = retrieval.get("retrievalTrace")
        if not isinstance(trace, dict):
            return {}
        summary_keys = (
            "retrievalStatus", "intent", "needGraph", "graphStatus",
            "denseCandidateCount", "lexicalCandidateCount", "rrfCandidateCount",
            "hydeCandidateCount", "webCandidateCount", "augmentationRequired", "augmentationReason",
            "crossEncoderStatus", "queryRewriteStatus", "hydeStatus", "webStatus",
            "graphCandidateCount", "rerankedCandidateCount", "retrievalMethods",
        )
        summary = {key: trace.get(key) for key in summary_keys if key in trace}
        top_candidates: list[dict[str, Any]] = []
        for item in (trace.get("topCandidates") or [])[:8]:
            if not isinstance(item, dict):
                continue
            top_candidates.append({
                key: item.get(key)
                for key in ("citationId", "evidenceType", "score", "rank", "retrievalMethod")
                if key in item
            })
        if top_candidates:
            summary["topCandidates"] = top_candidates
        return summary

    def _retrieval_methods(self, trusted: TrustedContext) -> list[str]:
        retrieval = trusted.retrieval or {}
        values: list[str] = []
        for value in retrieval.get("retrievalMethods", []):
            normalized = str(value).strip()
            if normalized and normalized not in values:
                values.append(normalized)
        for item in retrieval.get("chunks", []):
            if not isinstance(item, dict):
                continue
            normalized = str(item.get("retrievalMethod") or "").strip()
            derived: list[str] = []
            if normalized.startswith("hybrid-rrf"):
                derived.extend(["dense", "lexical", "rrf"])
            elif normalized.startswith("dense"):
                derived.append("dense")
            elif normalized.startswith("lexical"):
                derived.append("lexical")
            elif normalized:
                derived.append(normalized)
            if normalized.endswith("+heuristic-rerank"):
                derived.append("heuristic-rerank")
            for method in derived:
                if method not in values:
                    values.append(method)
        if retrieval.get("graphFacts") and "knowledge-graph" not in values:
            values.append("knowledge-graph")
        return values

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
        ranked_candidates = sorted(
            (
                item for item in trusted.citation_candidates
                if isinstance(item, dict) and item.get("rank") is not None
            ),
            key=lambda item: int(item.get("rank") or 10_000),
        )
        groups = (
            ranked_candidates,
            retrieval.get("chunks", []),
            retrieval.get("graphFacts", []),
            trusted.citation_candidates,
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
