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
    ResourceDiscoveryOutput,
    TeachingPlanOutput,
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
    """模型请求高风险写工具时中断轮次，要求用户先确认动作。"""
    def __init__(self, action: AgentActionRecord):
        self.action = action
        super().__init__("action confirmation is required")


@dataclass(slots=True)
class PreparedTurn:
    """已完成注册、上下文组装与记忆准备，但尚未执行模型的轮次快照。"""
    registration: TurnRegistration
    thread: ThreadRecord
    window: ContextWindow
    messages: list[dict[str, str]]
    plan: AgentPlan
    memory_context: MemoryContext


class PartialAnswerWriter:
    """按时间和字符阈值批量持久化流式部分回答。

    该写入器只在当前轮次租约有效时更新；最终完成、失败或中断路径会强制刷新，
    从而让断线恢复获得尽可能新的内容而不为每个 token 启动数据库事务。
    """
    def __init__(
        self,
        repository: AgentTurnRepository,
        turn_id: str,
        lease_owner: str,
        interval_seconds: float,
        character_threshold: int,
        initial: str = "",
    ):
        """保存租约身份与刷新阈值，使用已有部分回答作为恢复起点。"""
        self.repository = repository
        self.turn_id = turn_id
        self.lease_owner = lease_owner
        self.interval_seconds = interval_seconds
        self.character_threshold = character_threshold
        self.value = initial
        self._flushed_value = initial
        self._last_flush = 0.0

    async def update(self, value: str, *, force: bool = False) -> None:
        """在达到刷新条件或强制请求时写入新部分回答。"""
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
        """清空内存中的部分回答并强制同步到持久化层。"""
        self.value = ""
        self._flushed_value = ""
        self._last_flush = time.monotonic()
        await self.repository.update_partial(
            self.turn_id, self.lease_owner, ""
        )


class AgentRuntime:
    """编排有状态 Agent 轮次、可信检索、记忆、模型降级与 SSE 交付。

    本类将认证后的请求范围固化到会话、轮次和工具上下文；模型只能使用已准备的
    消息与证据。执行结果先按租约持久化，再通过同步响应或 SSE 交付，因此传输断开
    不会改变轮次事实，客户端可凭 ``clientTurnId`` 恢复。
    """
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
        """组装持久化仓库、模型网关和可选依赖，初始化实例级租约所有者。"""
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
        # 测试和兼容调用方可以在此注入一个 Agent。普通请求会根据
        # model_chain() 中的每个已配置模型创建 Agent。
        self._agent: Any | None = None
        self._agents: dict[tuple[str, int, str], Any] = {}
        self._web_domain_cache: tuple[float, list[str]] = (0.0, [])
        self._active_turn_tasks: dict[str, asyncio.Task[Any]] = {}

    async def handle(self, request: AgentMessageRequest) -> AgentMessageResponse:
        """同步执行一轮 Agent 请求，并返回已持久化的最终响应或既有完成结果。"""
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
        """启动 SSE 响应流；具体轮次执行由 ``stream_events`` 负责。"""
        prepared = await self._prepare_turn(request)
        return self.stream_events(request, prepared=prepared)

    async def stream_events(
        self,
        request: AgentMessageRequest,
        *,
        prepared: PreparedTurn | None = None,
    ) -> AsyncIterator[str]:
        """以 SSE 交付轮次生命周期、工具事件和回答增量。

        SSE 只是传输通道。后台任务在持久化轮次和部分答案后继续完成；连接断开时
        仅标记消费者不可用，后续同 ``clientTurnId`` 请求会从轮次记录恢复。
        """
        # SSE 是传输通道，不代表持久化轮次的生命周期。浏览器或代理短暂断开后，
        # 客户端可以使用相同的 clientTurnId 重连并恢复已持久化的响应。
        send_stream, _receive_stream = anyio.create_memory_object_stream[
            tuple[str, dict[str, Any]]
        ](1)
        event_queue: asyncio.Queue[tuple[str, dict[str, Any]] | None] = asyncio.Queue()
        stream_connected = True
        run_id = str(uuid.uuid4())
        prepared_turn = prepared or await self._prepare_turn(request)
        completed = self._completed_response(prepared_turn.registration.turn)

        async def publish(
            event_name: str, data: dict[str, Any] | None = None
        ) -> None:
            if not stream_connected:
                return
            payload = {"runId": run_id}
            if data:
                payload.update(data)
            event_queue.put_nowait((event_name, payload))

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
                    if stream_connected:
                        event_queue.put_nowait(None)

        task = asyncio.create_task(worker())
        try:
            while True:
                event = await event_queue.get()
                if event is None:
                    break
                event_name, data = event
                yield self._format_sse(event_name, data)
        finally:
            stream_connected = False

    @staticmethod
    def _stream_error_payload(
        request: AgentMessageRequest,
        code: str,
        message: str,
        *,
        retryable: bool,
    ) -> dict[str, Any]:
        """构造不含内部异常详情的统一流式错误载荷。"""
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
        """为已认证账号和范围创建空会话，范围由调用方请求上下文提供。"""
        return await self.repository.create_thread(owner_id, scope_type, scope_id)

    async def _prepare_turn(
        self, request: AgentMessageRequest
    ) -> PreparedTurn:
        """注册或恢复轮次，并在首次执行时准备记忆、历史窗口和工具计划。

        已完成轮次不重复触发记忆提取或模型调用；新轮次的所有上下文均由已认证请求
        和持久化会话构成，模型参数不能覆盖其中的范围。
        """
        registration = await self._register_turn(request)
        if registration.turn.status == "cancelled":
            # 已取消的幂等键不能重新激活，客户端必须生成新的 clientTurnId 再发起提问。
            raise TurnConflictError(
                "turn_cancelled", "请求的轮次已取消"
            )
        thread = await self.repository.get_thread(
            registration.turn.thread_id,
            request.owner_id,
            request.scope_type,
            request.scope_id,
        )
        if registration.turn.status == "completed":
            # 恢复完成轮次不读取新记忆或重算上下文，确保结果与首次提交完全一致。
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
            # 先保留已压缩后的正式历史，再追加本次用户消息以维持提示词内的时间顺序。
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
        """将 API 请求映射为具有请求哈希的持久化轮次，并转换存储边界异常。"""
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
            # 底层轮次仓储不直接依赖会话异常类型，运行时在此统一成 API 层可识别的语义。
            raise ThreadNotFoundError(str(exc)) from exc
        except PermissionError as exc:
            raise ThreadScopeError(str(exc)) from exc

    async def _context_window(self, thread: ThreadRecord) -> ContextWindow:
        """构建模型上下文窗口，必要时以乐观游标提交会话摘要。

        摘要竞争时最多重读三次，避免旧窗口覆盖新摘要；超过次数交由上层将该轮次
        标记为可恢复失败。
        """
        current = thread
        for _ in range(3):
            # 摘要仅能由观察到相同游标的执行者提交，冲突时重读而不是覆盖对方的新摘要。
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
        """计算去除传输标识后的请求哈希，并保存不含附件原文的恢复摘要。"""
        payload = request.model_dump(by_alias=True, mode="json")
        # clientTurnId 与 threadId 是传输/路由字段，不能影响“同一请求”的哈希判断。
        payload.pop("clientTurnId", None)
        payload.pop("threadId", None)
        canonical = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        attachment_hashes = [
            # 恢复摘要只存附件摘要，不把可能较大的 Data URL 或二进制原文写入轮次表。
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
        """从已完成轮次响应快照重建 API 响应，保证幂等重连返回同一事实。"""
        if turn.status != "completed":
            return None
        if not turn.response:
            raise RuntimeError("已完成轮次缺少响应内容")
        response = AgentMessageResponse.model_validate(turn.response)
        response.thread_id = turn.thread_id
        response.client_turn_id = turn.client_turn_id
        return response

    def _partial_writer(self, turn: AgentTurnRecord) -> PartialAnswerWriter:
        """为当前持久化轮次创建带租约约束的部分回答写入器。"""
        return PartialAnswerWriter(
            self.turn_repository,
            turn.turn_id,
            self.instance_id,
            self.settings.agent_partial_flush_interval_seconds,
            self.settings.agent_partial_flush_characters,
            turn.partial_answer,
        )

    def _start_heartbeat(self, turn: AgentTurnRecord) -> asyncio.Task[None]:
        """启动独立心跳任务续租，并在取消或租约异常时取消实际执行任务。"""
        execution_task = asyncio.current_task()
        if execution_task is None:
            raise RuntimeError("Agent 轮次需要 asyncio 任务")
        self._active_turn_tasks[turn.turn_id] = execution_task

        async def heartbeat_loop() -> None:
            # 这个原生 asyncio 任务不能继承外围 AnyIO 响应作用域的重复取消。
            # 它由 ``_stop_heartbeat`` 显式执行一次普通任务取消来停止。
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
                        # 续租失败意味着执行权已不可靠，主动取消模型调用避免竞争提交最终答案。
                        execution_task.cancel()
                        return
                    if cancellation_requested:
                        # 取消标记由仓储持久化；心跳负责把该跨进程信号转为本地任务取消。
                        execution_task.cancel()
                        return

        return asyncio.create_task(
            heartbeat_loop(), name=f"agent-turn-heartbeat-{turn.turn_id}"
        )

    async def _stop_heartbeat(self, task: asyncio.Task[None]) -> None:
        """停止心跳并清理当前实例登记的执行任务引用。"""
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
        """请求取消指定范围内的轮次，并主动中断本实例持有的执行任务。"""
        turn = await self.turn_repository.request_cancel(
            client_turn_id, owner_id, scope_type, scope_id
        )
        active = self._active_turn_tasks.get(turn.turn_id)
        if active is not None and active is not asyncio.current_task():
            # 仅中断本实例实际持有的任务；其他实例会在下一次心跳中观察到取消标记。
            active.cancel()
        return turn

    async def _finish_cancelled_or_interrupted(
        self,
        request: AgentMessageRequest,
        turn: AgentTurnRecord,
        writer: PartialAnswerWriter,
        error_code: str,
    ) -> None:
        """在取消或连接中断后强制保存部分答案，并以可恢复终态结束轮次。"""
        try:
            await writer.update(writer.value, force=True)
            cancelled = await self.turn_repository.cancel_requested(turn.turn_id)
            # 客户端显式取消不可重试，传输断开导致的 interrupted 则保留恢复机会。
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
        """按异常类型区分可重试中断和不可重试失败，并保留已生成的部分内容。"""
        retryable = not isinstance(exc, (AssertionError, TypeError, ValueError))
        # 参数/断言错误属于确定性失败；传输和下游异常允许用相同 clientTurnId 恢复。
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
        """构造用户消息持久化元数据，使恢复查询能判断它是否属于未完成轮次。"""
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
        """构造未完成助手消息的持久化元数据，禁止其进入正式模型历史。"""
        return {
            "status": "incomplete",
            "taskType": request.task_type,
            "clientTurnId": request.client_turn_id,
            "incomplete": True,
            "turnStatus": turn_status,
        }

    async def _get_or_create_thread(self, request: AgentMessageRequest) -> ThreadRecord:
        """取得已校验会话或在当前认证范围内创建新会话。"""
        if request.thread_id:
            return await self.repository.require_thread(
                request.thread_id, request.owner_id, request.scope_type, request.scope_id
            )
        return await self.create_thread(
            request.owner_id, request.scope_type, request.scope_id
        )

    async def _memory_context_for(self, request: AgentMessageRequest) -> MemoryContext:
        """按任务类型和开关读取当前范围的记忆，不将资源发现任务的记忆注入模型。"""
        if not self.settings.agent_memory_enabled:
            # 总开关优先于单范围设置，关闭时既不读取也不写入记忆。
            return MemoryContext.empty()
        if request.task_type == "RESOURCE_DISCOVERY":
            # 资源发现应只依据当前检索范围，避免用户偏好影响客观资源筛选。
            return MemoryContext.empty()
        query_parts = [request.message, request.grade or "", request.theme or "", request.resource_category or ""]
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
        """仅将用户明确“记住”指令写入当前范围，并附带来源会话与轮次审计关联。"""
        if not self.settings.agent_memory_enabled:
            return None
        if request.task_type == "RESOURCE_DISCOVERY":
            return None
        setting = await self.memory_repository.get_setting(
            request.owner_id, request.scope_type, request.scope_id
        )
        if not setting.enabled:
            # 用户在当前范围关闭记忆后，不从旧记录推断或注入任何偏好。
            return None
        draft = self.explicit_memory_extractor.extract(request.message)
        if draft is None:
            # 仅识别明确记忆指令，普通聊天内容不得被规则提取器自动固化。
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
        """返回请求模型优先的 Agent 尝试链；注入 Agent 仅用于兼容和测试。"""
        if self._agent is not None:
            config = ModelConfig(
                provider="injected",
                model=self.settings.primary_model,
                base_url="",
                api_key="injected",
                fallback_level=0,
                supports_json_object=True,
            )
            return [(config, self._agent)]
        return [
            (config, None)
            for config in self.model.model_configs_for(model_id)
            if config.supports_json_object
        ]

    async def _create_agent_for(
        self, config: ModelConfig, checkpoint_namespace: str
    ) -> Any:
        """按模型和检查点命名空间缓存 Agent 图实例。

        检查点命名空间进入缓存键，避免不同轮次恢复到彼此的图状态；写工具启用时由
        中间件在执行前产生确认中断。
        """
        if not config.configured():
            raise RuntimeError("model_unavailable")
        key = (config.model, config.fallback_level, checkpoint_namespace)
        if key not in self._agents:
            interrupt_on = write_tool_interrupts(
                self.settings.agent_write_tools_enabled
            )
            self._agents[key] = create_agent(
                self.model.build_model(config).bind(
                    response_format={"type": "json_object"}
                ),
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
        """构造 Agent 调用配置，将轮次和模型身份写入可追踪配置而非提示词。"""
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
        """执行非流式聊天 Agent，并在模型级失败时按配置链尝试后备模型。"""
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
                resource_category=request.resource_category,
                max_distance_meters=request.max_distance_meters,
                client_turn_id=request.client_turn_id,
                executions=list(prefetched_executions),
                degraded_reasons=list(prefetched_reasons),
            )
            token = bind_tool_runtime(runtime)
            prompt_run_id = await self._start_agent_prompt_run(request, thread, memory_context, config)
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
                result = self._with_model_metadata(result, config)
                await self._finish_structured_prompt(prompt_run_id, "completed", 0, {"answer": result.answer}, "")
                return result
            except (ActionConfirmationRequired, TurnConflictError, TurnLeaseLostError):
                raise
            except Exception as exc:
                await self._finish_structured_prompt(prompt_run_id, "failed", 0, {}, str(exc))
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
        """流式执行聊天 Agent，持续写入部分答案并支持检查点恢复。

        已恢复的轮次若存在图检查点，以 ``Command(resume=...)`` 延续确认后的图状态；
        对累计内容和增量内容统一去重后才发送 SSE token，避免浏览器重复展示。
        """
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
                resource_category=request.resource_category,
                max_distance_meters=request.max_distance_meters,
                client_turn_id=request.client_turn_id,
                event_sink=emit,
                executions=list(prefetched_executions),
                degraded_reasons=list(prefetched_reasons),
            )
            token = bind_tool_runtime(runtime)
            prompt_run_id = await self._start_agent_prompt_run(request, thread, memory_context, config)
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
                await self._finish_structured_prompt(prompt_run_id, "completed", 0, {"answer": result.answer}, "")
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
                await self._finish_structured_prompt(prompt_run_id, "failed", 0, {}, str(exc))
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
                        "exceptionType": type(exc).__name__,
                        "errorMessage": str(exc)[:500],
                    },
                    exc_info=True,
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
        """在模型生成前执行确定性的证据检索。

        模型仍可自行调用工具，但即使它直接生成最终 JSON 而没有发出工具调用，也不能
        绕过已认证的业务检索边界。预取结果会合并到 ``trusted_context``，供后续模型
        调用及引用校验共同使用。
        """
        if not request.context.actor or not request.context.scope:
            # 缺少认证主体或范围时不执行任何业务工具，避免空范围请求被服务端误解释。
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
            resource_category=request.resource_category,
            max_distance_meters=request.max_distance_meters,
            client_turn_id=request.client_turn_id,
            event_sink=emit,
        )
        token = bind_tool_runtime(runtime)
        try:
            if "retrieve_knowledge" in plan.recommended_tools:
                try:
                    # 工具超时只产生可追踪降级，不让一次预取失败阻断模型对已有上下文的回答。
                    output = await asyncio.wait_for(
                        runtime.run(
                            "retrieve_knowledge",
                            {"query": request.message, "limit": 5},
                            lambda: self._retrieve_with_augmentation(request, thread),
                        ),
                        timeout=self.settings.agent_tool_timeout_seconds,
                    )
                except asyncio.TimeoutError:
                    runtime.degraded_reasons.append("retrieve_knowledge_timeout")
                    output = "{}"
                try:
                    result = json.loads(output)
                except (TypeError, ValueError, json.JSONDecodeError):
                    result = {}
                if isinstance(result, dict):
                    # 将预取 JSON 写回可信上下文，后续模型调用与工具调用复用同一证据集合。
                    _merge_retrieval(runtime, result)
            if "query_graph_relations" in plan.recommended_tools:
                try:
                    await asyncio.wait_for(
                        query_graph_relations.ainvoke(
                            {"query": request.message, "limit": 5}
                        ),
                        timeout=self.settings.agent_tool_timeout_seconds,
                    )
                except asyncio.TimeoutError:
                    runtime.degraded_reasons.append("query_graph_relations_timeout")
        finally:
            # ContextVar 必须在 finally 中恢复，避免并发轮次继承本轮的身份和范围。
            reset_tool_runtime(token)
        return list(runtime.executions), list(runtime.degraded_reasons)

    async def _retrieve_with_augmentation(
        self, request: AgentMessageRequest, thread: ThreadRecord
    ) -> dict[str, Any]:
        """先执行业务检索；低召回时在受控改写、HyDE 和域名白名单范围内增强一次。"""
        if self.business_tool_client is None:
            return {"retrievalStatus": "degraded", "degradedReason": "business_tool_unconfigured"}
        tool_authorization = request.context.tool_authorization
        if not tool_authorization:
            return {"retrievalStatus": "degraded", "degradedReason": "tool_context_authorization_missing"}
        rewrite = await self._controlled_query_rewrite(request, thread)
        payload = self._retrieval_payload(request, rewrite)
        try:
            first = await self.business_tool_client.query_knowledge(
                payload, tool_authorization=tool_authorization,
                client_turn_id=request.client_turn_id,
            )
        except Exception as exc:
            # 网络或协议失败必须显式标注降级，不能伪装成正常的空结果。
            return {"retrievalStatus": "degraded", "degradedReason": type(exc).__name__.lower()}
        trace = first.setdefault("retrievalTrace", {}) if isinstance(first, dict) else {}
        trace["queryRewriteStatus"] = rewrite["status"]
        if not isinstance(first, dict) or not trace.get("augmentationRequired"):
            # 是否需要二次增强由业务服务的检索诊断决定，模型不能单方面扩大联网范围。
            return first
        domains = await self._authoritative_domains()
        hyde_task = self._generate_hyde(rewrite["searchQuery"])
        web_task = self._search_authoritative_web(rewrite["searchQuery"], domains)
        hyde, web = await asyncio.gather(hyde_task, web_task)
        if not hyde and not web:
            trace["augmentationReason"] = f"{trace.get('augmentationReason') or 'low_recall'}:no_augmentation_available"
            return first
        augmented = dict(payload)
        # 保留首轮经业务服务裁决过的范围和过滤条件，仅添加受限增强证据。
        augmented["hydeQuery"] = hyde or None
        augmented["webEvidence"] = web
        try:
            final = await self.business_tool_client.query_knowledge(
                augmented, tool_authorization=tool_authorization,
                client_turn_id=request.client_turn_id,
            )
        except Exception:
            # 增强失败时返回首轮结果，避免因可选能力故障丢失已经取得的业务证据。
            trace["augmentationReason"] = f"{trace.get('augmentationReason') or 'low_recall'}:augmentation_failed"
            return first
        final_trace = final.setdefault("retrievalTrace", {}) if isinstance(final, dict) else {}
        final_trace["queryRewriteStatus"] = rewrite["status"]
        final_trace["hydeStatus"] = "ok" if hyde else "skipped"
        final_trace["webStatus"] = "ok" if web else "skipped"
        return final

    def _retrieval_payload(self, request: AgentMessageRequest, rewrite: dict[str, Any]) -> dict[str, Any]:
        """将认证范围与受控改写结果构造成业务检索载荷，固定本轮 topK 上限。"""
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
        """只在疑似指代时补全检索词；置信度不足时严格保留用户原问题。"""
        original = request.message.strip()
        trigger = bool(re.search(r"(?:这个|那个|这所|那所|这里|那里|它|该)(?:学校|资源|地方|场馆|遗址)?", original))
        if not trigger:
            # 未发现指代无需经过模型改写，避免无意义调用改变明确查询的检索语义。
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
            # 长查询或低置信补全可能注入虚构实体，回退到用户原文比猜测更安全。
            return {"status": "fallback", "searchQuery": original}
        return {
            "status": "applied", "searchQuery": query,
            "intent": str(result.get("intent") or "").strip() or None,
            "grade": str(result.get("grade") or "").strip() or None,
            "theme": str(result.get("theme") or "").strip() or None,
        }

    async def _generate_hyde(self, query: str) -> str | None:
        """生成长度受限的假设性检索摘要；失败返回空值而不影响首轮检索结果。"""
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
        """读取并短暂缓存业务侧维护的权威域名；缓存失效时宁可跳过联网增强。"""
        now = time.monotonic()
        cached_at, cached = self._web_domain_cache
        if cached and now - cached_at < self.settings.retrieval_web_cache_seconds:
            # 域名配置不会随单次请求变化，复用缓存避免每轮检索增加一次业务 HTTP 调用。
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
        """在业务白名单域名内搜索网页证据，并过滤协议不安全和提示注入文本。"""
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
                # 结果提供方可能返回重定向或近似域名，必须在本地再次执行严格白名单检查。
                continue
            excerpt = str(item.get("content") or "").replace("\x00", " ").strip()
            if not excerpt or re.search(
                r"ignore (?:all|previous)|system prompt|ignore .*instructions|disregard|"
                r"忽略.*(?:指令|之前|系统)|系统提示",
                excerpt,
                re.I,
            ):
                # 网页摘要携带指令时不能进入模型上下文，即使域名在白名单中也应丢弃。
                continue
            result.append({"title": str(item.get("title") or host)[:240], "url": url,
                           "domain": host, "excerpt": excerpt[:900], "rank": index + 1,
                           "providerScore": item.get("score")})
        return result

    @staticmethod
    def _allowed_web_host(host: str, domains: list[str]) -> bool:
        """允许白名单根域及其子域，不接受仅包含相同字符串的伪造域名。"""
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
            selection.content,
            trace_context,
            validator,
            response_schema=(
                TeachingPlanOutput
                if request.task_type == "TEACHING_PLAN"
                else ResourceDiscoveryOutput
            ),
            **model_kwargs,
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
                await emit(
                    "response.reset",
                    {
                        "clientTurnId": request.client_turn_id,
                        "reason": "model_fallback",
                        "failedModel": data.get("failedModel"),
                        "nextModel": data.get("nextModel"),
                        "errorType": error_message,
                    },
                )
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
        raise ValueError(f"不支持的 taskType：{request.task_type}")

    async def _start_structured_prompt(
        self,
        prompt_key: str,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        memory_context: MemoryContext,
    ):
        if self.prompts is None:
            raise RuntimeError("提示词管理器不可用")
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

    async def _start_agent_prompt_run(
        self,
        request: AgentMessageRequest,
        thread: ThreadRecord,
        memory_context: MemoryContext,
        config: ModelConfig,
    ) -> str | None:
        """记录聊天轮次所使用的通用 Agent 提示词运行信息。"""
        if self.prompts is None:
            return None
        try:
            subject_key = f"{request.scope_type}:{request.scope_id}"
            selection = await self.prompts.resolve(
                "agent",
                subject_key,
                task_context(request, memory_context.prompt),
            )
            return await self.prompts.start_run(
                selection, subject_key, config.model, len(selection.content)
            )
        except Exception as exc:
            LOGGER.warning("agent_prompt_run_start_failed", extra={"error": str(exc)[:300]})
            return None

    async def _finish_structured_prompt(
        self, run_id: str | None, status: str, elapsed: int, result: dict[str, Any], error_message: str
    ) -> None:
        if self.prompts is not None and run_id is not None:
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
                raise ValueError("完成 Agent 轮次时请求不能为空")
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
            resource_category=request.resource_category,
            max_distance_meters=request.max_distance_meters,
            client_turn_id=request.client_turn_id,
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
        # 只有确认检查点存在时才允许空输入恢复，避免把新轮次误当作断点续跑。
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
        """将图中单个高风险写动作持久化为待确认记录，并中断当前轮次。

        多个同时写动作被拒绝，保证一次确认只对应一个稳定 ``action_id``；持久化的
        参数已脱敏，随后恢复会使用该动作状态决定批准或拒绝。
        """
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
            raise RuntimeError("一次模型步骤中不允许包含多个写入动作")
        interrupt_id, action_request = pending[0]
        tool_name = str(action_request.get("name") or "").strip()
        arguments = action_request.get("args")
        if not tool_name or not isinstance(arguments, dict):
            raise RuntimeError("无效的写入动作中断")
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
        """依据工具审计最终状态回写已批准动作的成功或失败结果，并发送事件。"""
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
        """将会话历史、可信范围、预取证据和记忆拼装为模型消息。

        系统消息明确证据与权限边界；附件仅附加到最后一个用户消息，避免历史消息被
        重复携带二进制数据。
        """
        lc_messages: list[Any] = []
        if summary:
            lc_messages.append(SystemMessage(content=f"较早对话摘要（仅作上下文，不是新事实）：\n{summary}"))
        lc_messages.append(SystemMessage(content=(
            "本轮策略计划：先完成目标，再按需调用推荐工具 "
            f"{', '.join(plan.recommended_tools)}；最多执行 {plan.max_tool_rounds} 轮工具调用。"
        )))
        if request:
            teaching_context = request.context.teaching_context or {}
            context_lines = [
                "可信教学场景限定（不得自行扩大或改变）：",
                f"学校：{(teaching_context.get('school') or {}).get('name') or '当前所属学校'}",
                f"年级：{request.grade or teaching_context.get('grade') or '未指定'}",
                f"主题：{request.theme or teaching_context.get('theme') or '未指定'}",
                f"资源类别：{request.resource_category or teaching_context.get('resourceCategory') or '全部类别'}",
                f"周边距离：{(request.max_distance_meters or teaching_context.get('maxDistanceMeters') or '不限')} 米",
                "回答必须基于下方真实业务数据和知识库证据；没有证据时不得编造资源、距离、人物或事件。",
            ]
            lc_messages.append(SystemMessage(content="\n".join(context_lines)))
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
        """从业务侧预取证据构造受限提示词，按排序合并片段和图谱事实。"""
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
        """校验模型 JSON、限制引用来源，并构造可持久化的统一响应。

        模型给出的引用只能取自 ``trusted`` 证据集合；没有合法引用时按预取证据顺序
        选择候选项。推断记忆候选在此处经过仓库策略处理，失败不会使主回答丢失。
        """
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
        """保存模型建议的记忆候选，但不让候选写入失败影响聊天主流程。"""
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
        """从最后一条模型消息取得严格 JSON 输出，拒绝空回答和非对象载荷。"""
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
        if payload is None:
            raise ValueError("invalid_model_output")
        try:
            parsed = AgentModelOutput.model_validate(payload)
        except (ValueError, TypeError) as exc:
            raise ValueError("invalid_model_output") from exc
        if not parsed.answer.strip():
            raise ValueError("invalid_model_output")
        return parsed

    def _partial_answer(self, content: str) -> str:
        """从尚未完成的 JSON 流中提取当前可验证的 ``answer``，无法验证时保持静默。"""
        payload = ModelGateway.parse_json(content)
        if payload is None:
            return ""
        try:
            return AgentModelOutput.model_validate(payload).answer
        except (ValueError, TypeError):
            return ""

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


    def _stream_messages(self, chunk: Any) -> list[Any]:
        """兼容 LangGraph 多种流事件外形，仅返回可能包含模型文本的消息。"""
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
        """从状态快照中取最后一条 AI 消息，用于无增量的恢复场景。"""
        for message in reversed(messages):
            if isinstance(message, (AIMessage, AIMessageChunk)):
                return message_text(message.content)
        return ""

    @staticmethod
    def _merge_stream_text(previous: str, incoming: str) -> tuple[str, str]:
        """同时接受增量分片和累计式 LangGraph 消息。"""
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
        """将最终或降级回答按固定大小拆分，避免单个 SSE 事件阻塞客户端刷新。"""
        for index in range(0, len(answer), size):
            await emit("token", {"delta": answer[index:index + size]})
            # 让 SSE 消费者逐个刷新队列中的分片，避免将回退答案作为一个大响应输出。
            await asyncio.sleep(0)

    async def _load_prompt(self) -> str:
        """优先读取已激活的受管提示词，缺失时回退到本地只读提示词文件。"""
        if self.prompts is not None:
            try:
                return await self.prompts.active_content("agent")
            except LookupError:
                pass
        if not self.settings.prompt_path.is_file():
            raise FileNotFoundError(f"Agent 提示词不存在：{self.settings.prompt_path}")
        return self.settings.prompt_path.read_text(encoding="utf-8")

    def invalidate_prompt(self, prompt_key: str) -> None:
        """失效 Agent 提示词对应的图缓存，使下次创建使用最新受管版本。"""
        if prompt_key.strip() == "agent":
            self._agents.clear()

    def _degraded_answer(
        self, request: AgentMessageRequest, trusted: TrustedContext, thread_id: str,
        compacted: bool, executions: list[ToolExecution] | None = None, status: str = "degraded",
    ) -> AgentMessageResponse:
        """在模型链耗尽时，以可信资源和检索状态生成明确标记的降级响应。"""
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
