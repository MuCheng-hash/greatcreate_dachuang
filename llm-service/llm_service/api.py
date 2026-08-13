from __future__ import annotations

import asyncio
import json
import hmac
import logging
import secrets
import base64
import anyio
import httpx
from contextlib import asynccontextmanager, suppress
from typing import Any, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import PlainTextResponse, StreamingResponse
from psycopg import InterfaceError, OperationalError
from psycopg_pool import PoolTimeout

from .container import AppContainer, build_container
from .actions import ActionConflictError, AgentActionRecord
from .legacy import (
    build_map_answer,
    build_structured_teaching_plan,
    stream_structured_teaching_plan,
)
from .observability import FallbackAlertManager, LlmObservability
from .model_gateway import message_text
from .prompt_manager import PromptVersionExistsError
from .repository import ThreadNotFoundError, ThreadScopeError
from .routes import health_router
from .runtime import ActionConfirmationRequired, AgentRuntime
from .schemas import (
    AgentMessageRequest,
    AgentMessageResponse,
    AgentActionDecisionRequest,
    AgentActionResponse,
    MemoryConflictPreviewResponse,
    MemoryCreateRequest,
    MemoryItem,
    MemoryResolutionRequest,
    MemorySettingResponse,
    MemorySettingUpdateRequest,
    MemoryUpdateRequest,
    StoredMessage,
    ThreadCreateRequest,
    TurnCancelResponse,
    TurnRecoveryResponse,
    ThreadResponse,
    ThreadSummaryResponse,
)
from .settings import Settings, get_settings
from .turns import TurnConflictError, TurnLeaseLostError
from .user_memory import (
    MemoryConflictError,
    MemoryNotFoundError,
    MemoryRecord,
    MemoryStateError,
    MemoryValidationError,
)


LOGGER = logging.getLogger("llm.stateful_agent.api")


class DisconnectAwareStreamingResponse(StreamingResponse):
    """Monitor ASGI disconnects even while an SSE producer is idle.

    Starlette's ASGI 2.4 path discovers disconnects only on the next failed
    response write. Agent model/tool calls can be silent for many seconds, so
    the upstream task must also listen for ``http.disconnect`` concurrently.
    """

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await super().__call__(scope, receive, send)
            return

        async with anyio.create_task_group() as task_group:
            async def stream() -> None:
                try:
                    await self.stream_response(send)
                except OSError:
                    # A failed send is the transport-level equivalent of the
                    # disconnect message handled by the sibling task.
                    pass
                finally:
                    task_group.cancel_scope.cancel()

            task_group.start_soon(stream)
            await self.listen_for_disconnect(receive)
            task_group.cancel_scope.cancel()

        if self.background is not None:
            await self.background()


async def _thread_response(
    runtime: AgentRuntime, record: Any, include_messages: bool = True
) -> ThreadResponse:
    messages = []
    if include_messages:
        for item in await runtime.repository.list_messages(record.thread_id):
            messages.append(StoredMessage(
                id=item["id"], role=item["role"], content=item["content"],
                createdAt=item["created_at"], metadata=item["metadata"],
            ))
    return ThreadResponse(
        threadId=record.thread_id, ownerId=record.owner_id, scopeType=record.scope_type,
        scopeId=record.scope_id, status=record.status, summary=record.summary,
        createdAt=record.created_at, updatedAt=record.updated_at, messages=messages,
    )


def _memory_response(record: MemoryRecord) -> MemoryItem:
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


def _memory_conflict_preview_response(preview: Any) -> MemoryConflictPreviewResponse:
    return MemoryConflictPreviewResponse(
        candidate=_memory_response(preview.candidate),
        conflicts=[_memory_response(item) for item in preview.conflicts],
        duplicate=preview.duplicate,
    )


def _raise_memory_http_error(exc: Exception) -> None:
    if isinstance(exc, MemoryConflictError):
        preview = _memory_conflict_preview_response(exc.preview)
        raise HTTPException(
            status_code=409,
            detail={
                "code": "memory_conflict",
                "message": str(exc),
                "preview": preview.model_dump(mode="json", by_alias=True),
            },
        ) from exc
    if isinstance(exc, MemoryNotFoundError):
        raise HTTPException(status_code=404, detail="memory not found") from exc
    if isinstance(exc, MemoryStateError):
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    if isinstance(exc, MemoryValidationError):
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    raise exc


def create_app(
    settings: Settings | None = None,
    observability: LlmObservability | None = None,
    alerts: FallbackAlertManager | None = None,
    container: AppContainer | None = None,
) -> FastAPI:
    if container is None:
        settings = settings or get_settings()
        container = build_container(settings, observability, alerts)
    elif settings is not None and settings is not container.settings:
        raise ValueError("settings and container.settings must reference the same object")
    settings = container.settings
    repository = container.repository
    observability = container.observability
    alerts = container.alerts
    model = container.model_gateway
    prompts = container.prompts
    runtime = container.runtime
    memory_repository = container.memory_repository
    database = container.database
    migrator = container.migrator
    checkpoints = container.checkpoints
    turn_repository = container.turn_repository
    action_repository = container.action_repository

    def action_response(action: AgentActionRecord) -> AgentActionResponse:
        title = f"确认执行 {action.tool_name}"
        summary = "该操作会修改业务数据，请确认是否继续。"
        return AgentActionResponse(
            actionId=action.action_id,
            clientTurnId=action.client_turn_id,
            threadId=action.thread_id,
            toolName=action.tool_name,
            title=title,
            summary=summary,
            arguments=action.sanitized_arguments,
            riskLevel=action.risk_level,
            status=action.status,
            expiresAt=action.expires_at,
            resultSummary=action.result_summary,
            resourceReference=action.resource_reference,
            errorCode=action.error_code,
        )

    async def memory_cleanup_loop() -> None:
        interval = max(1, settings.agent_memory_cleanup_interval_seconds)
        while True:
            await asyncio.sleep(interval)
            try:
                await memory_repository.cleanup_expired()
            except Exception:
                LOGGER.exception("agent_memory_cleanup_failed")

    async def checkpoint_cleanup_once() -> None:
        turn_ids = await turn_repository.claim_checkpoint_cleanup(
            settings.agent_checkpoint_retention_days,
            settings.agent_checkpoint_cleanup_batch_size,
        )
        for turn_id in turn_ids:
            deleted = False
            try:
                await checkpoints.delete_thread(turn_id)
                deleted = True
            except Exception:
                LOGGER.exception(
                    "agent_checkpoint_cleanup_failed",
                    extra={"turnId": turn_id},
                )
            finally:
                await turn_repository.finish_checkpoint_cleanup(
                    turn_id, deleted=deleted
                )

    async def checkpoint_cleanup_loop() -> None:
        interval = max(
            60, settings.agent_checkpoint_cleanup_interval_seconds
        )
        while True:
            await asyncio.sleep(interval)
            try:
                await checkpoint_cleanup_once()
            except Exception:
                LOGGER.exception("agent_checkpoint_cleanup_loop_failed")

    async def action_cleanup_once() -> None:
        await action_repository.expire_pending()
        await action_repository.redact_finished(
            settings.agent_action_payload_retention_days,
            settings.agent_action_cleanup_batch_size,
        )

    async def action_cleanup_loop() -> None:
        interval = max(60, settings.agent_action_cleanup_interval_seconds)
        while True:
            await asyncio.sleep(interval)
            try:
                await action_cleanup_once()
            except Exception:
                LOGGER.exception("agent_action_cleanup_failed")

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        cleanup_task: asyncio.Task[None] | None = None
        checkpoint_cleanup_task: asyncio.Task[None] | None = None
        action_cleanup_task: asyncio.Task[None] | None = None
        database_open = False
        try:
            await database.open()
            database_open = True
            if settings.app_env == "dev":
                await migrator.migrate()
                await checkpoints.setup(settings.migration_dsn)
            else:
                await migrator.validate()
                await checkpoints.validate()
            await prompts.initialize()
            await alerts.start()
            await memory_repository.cleanup_expired()
            await checkpoint_cleanup_once()
            await action_cleanup_once()
            cleanup_task = asyncio.create_task(
                memory_cleanup_loop(), name="agent-memory-cleanup"
            )
            application.state.memory_cleanup_task = cleanup_task
            checkpoint_cleanup_task = asyncio.create_task(
                checkpoint_cleanup_loop(), name="agent-checkpoint-cleanup"
            )
            application.state.checkpoint_cleanup_task = checkpoint_cleanup_task
            action_cleanup_task = asyncio.create_task(
                action_cleanup_loop(), name="agent-action-cleanup"
            )
            application.state.action_cleanup_task = action_cleanup_task
            yield
        finally:
            if action_cleanup_task is not None:
                action_cleanup_task.cancel()
                with suppress(asyncio.CancelledError):
                    await action_cleanup_task
            if checkpoint_cleanup_task is not None:
                checkpoint_cleanup_task.cancel()
                with suppress(asyncio.CancelledError):
                    await checkpoint_cleanup_task
            if cleanup_task is not None:
                cleanup_task.cancel()
                with suppress(asyncio.CancelledError):
                    await cleanup_task
            await container.business_tool_client.aclose()
            await alerts.close()
            if database_open:
                await database.close()

    app = FastAPI(
        title="Red Culture Stateful Agent",
        version="2.0.0",
        lifespan=lifespan,
    )
    app.state.container = container
    app.state.settings = settings
    app.state.runtime = runtime
    app.state.memory_repository = memory_repository
    app.state.model = model
    app.state.prompts = prompts
    app.state.observability = observability
    app.state.alerts = alerts

    async def database_unavailable(_request: Any, exc: Exception) -> Response:
        LOGGER.error(
            "postgresql_unavailable",
            extra={"errorType": type(exc).__name__},
        )
        return Response(
            content=json.dumps(
                {"detail": "database temporarily unavailable"},
                ensure_ascii=False,
            ),
            status_code=503,
            media_type="application/json",
        )

    for database_error in (OperationalError, InterfaceError, PoolTimeout):
        app.add_exception_handler(database_error, database_unavailable)

    async def require_internal_agent_token(
        token: str | None = Header(default=None, alias="X-Agent-Service-Token"),
    ) -> None:
        expected = settings.internal_service_token.strip()
        if not expected:
            raise HTTPException(status_code=503, detail="AGENT_INTERNAL_SERVICE_TOKEN is not configured")
        if not secrets.compare_digest(token or "", expected):
            raise HTTPException(status_code=401, detail="agent service token is invalid")

    async def require_model_gateway_key(
        token: str | None = Header(default=None, alias="X-Model-Gateway-Key"),
    ) -> None:
        expected = settings.internal_service_token.strip()
        if not expected or not secrets.compare_digest(token or "", expected):
            raise HTTPException(status_code=401, detail="model gateway key is invalid")

    @app.post("/internal/vision/analyze", dependencies=[Depends(require_model_gateway_key)])
    async def analyze_image(payload: dict[str, Any]) -> dict[str, Any]:
        model_name = str(payload.get("model") or settings.vision_model).strip()
        image = str(payload.get("imageBase64") or "")
        if not model_name or not image:
            raise HTTPException(status_code=422, detail="model and imageBase64 are required")
        vision = next((item for target, item in model.chat_models if target.model == model_name), None)
        if vision is None:
            raise HTTPException(status_code=422, detail="vision model is not configured")
        result = await vision.ainvoke([{"role": "user", "content": [
            {"type": "text", "text": "请用中文客观描述图片中的场景、文字、人物、地点和结构信息，返回一段可用于知识库检索的描述。"},
            {"type": "image_url", "image_url": {"url": "data:image/png;base64," + image}},
        ]}])
        return {"description": message_text(result.content), "model": model_name}

    @app.post("/internal/embeddings/hybrid", dependencies=[Depends(require_model_gateway_key)])
    async def hybrid_embeddings(payload: dict[str, Any]) -> dict[str, Any]:
        texts = payload.get("texts")
        if not isinstance(texts, list) or not settings.embedding_api_url:
            raise HTTPException(status_code=503, detail="embedding gateway is not configured")
        async with httpx.AsyncClient(timeout=90) as client:
            response = await client.post(settings.embedding_api_url.rstrip("/") + "/embeddings", headers={"Authorization": "Bearer " + settings.embedding_api_key}, json={"model": payload.get("model") or settings.embedding_model, "input": texts, "dimensions": settings.embedding_dimensions})
        response.raise_for_status()
        items = []
        for item, text in zip(response.json().get("data", []), texts):
            tokens = {}
            for token in str(text).lower().split(): tokens[token] = tokens.get(token, 0) + 1
            items.append({"dense": item["embedding"], "sparse": {"indices": list(range(len(tokens))), "values": list(tokens.values())}})
        return {"items": items, "model": payload.get("model") or settings.embedding_model}

    async def require_prompt_admin(x_prompt_admin_token: str = Header(default="")) -> None:
        if not settings.prompt_admin_token:
            raise HTTPException(status_code=503, detail="PROMPT_ADMIN_TOKEN is not configured")
        if not hmac.compare_digest(x_prompt_admin_token, settings.prompt_admin_token):
            raise HTTPException(status_code=401, detail="invalid prompt admin token")

    async def require_observability_admin(
        x_observability_admin_token: str = Header(default=""),
    ) -> None:
        if not settings.observability_token:
            raise HTTPException(status_code=503, detail="OBSERVABILITY_ADMIN_TOKEN is not configured")
        if not hmac.compare_digest(x_observability_admin_token, settings.observability_token):
            raise HTTPException(status_code=401, detail="invalid observability admin token")

    def validate_model_selection(request: AgentMessageRequest) -> None:
        try:
            model.model_configs_for(request.model_id)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail="unknown modelId") from exc

    def raise_turn_conflict(exc: TurnConflictError) -> None:
        raise HTTPException(
            status_code=409,
            detail={"code": exc.code, "message": str(exc)},
        ) from exc

    if settings.allowed_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.allowed_origins,
            allow_credentials=True,
            allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
            allow_headers=[
                "Authorization", "Content-Type", "X-Agent-Service-Token",
                "X-Prompt-Admin-Token", "X-Observability-Admin-Token",
            ],
        )

    @app.get(
        "/models",
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def list_models() -> dict[str, list[dict[str, Any]]]:
        return {"models": model.model_catalog()}

    @app.get(
        "/agent/memory-settings",
        response_model=MemorySettingResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def get_memory_setting(
        owner_id: str = Query(alias="ownerId"),
        scope_type: Literal["SCHOOL", "REGION", "RESOURCE"] = Query(alias="scopeType"),
        scope_id: str = Query(alias="scopeId"),
    ) -> MemorySettingResponse:
        try:
            record = await memory_repository.get_setting(
                owner_id, scope_type, scope_id
            )
        except MemoryValidationError as exc:
            _raise_memory_http_error(exc)
        return MemorySettingResponse(
            available=settings.agent_memory_enabled,
            enabled=record.enabled,
            effectiveEnabled=settings.agent_memory_enabled and record.enabled,
            createdAt=record.created_at,
            updatedAt=record.updated_at,
        )

    @app.put(
        "/agent/memory-settings",
        response_model=MemorySettingResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def update_memory_setting(
        request: MemorySettingUpdateRequest,
    ) -> MemorySettingResponse:
        try:
            record = await memory_repository.update_setting(
                request.owner_id,
                request.scope_type,
                request.scope_id,
                request.enabled,
            )
        except MemoryValidationError as exc:
            _raise_memory_http_error(exc)
        return MemorySettingResponse(
            available=settings.agent_memory_enabled,
            enabled=record.enabled,
            effectiveEnabled=settings.agent_memory_enabled and record.enabled,
            createdAt=record.created_at,
            updatedAt=record.updated_at,
        )

    @app.get(
        "/agent/memories",
        response_model=list[MemoryItem],
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def list_memories(
        owner_id: str = Query(alias="ownerId"),
        scope_type: Literal["SCHOOL", "REGION", "RESOURCE"] = Query(alias="scopeType"),
        scope_id: str = Query(alias="scopeId"),
        status: Literal["pending", "active", "deleted"] | None = Query(default=None),
        memory_type: Literal["PROFILE", "TASK"] | None = Query(
            default=None, alias="memoryType"
        ),
        limit: int = Query(default=200, ge=1, le=500),
    ) -> list[MemoryItem]:
        try:
            await memory_repository.maybe_cleanup()
            records = await memory_repository.list_memories(
                owner_id,
                scope_type,
                scope_id,
                status=status,
                memory_type=memory_type,
                limit=limit,
            )
        except MemoryValidationError as exc:
            _raise_memory_http_error(exc)
        return [_memory_response(record) for record in records]

    @app.post(
        "/agent/memories",
        response_model=MemoryItem,
        status_code=201,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def create_memory(request: MemoryCreateRequest) -> MemoryItem:
        try:
            record = await memory_repository.create_memory(
                request.owner_id,
                request.scope_type,
                request.scope_id,
                memory_type=request.memory_type,
                field_key=request.field_key,
                content=request.content,
                status=request.status,
                source=request.source,
                source_thread_id=request.source_thread_id,
                confidence=request.confidence,
                replace_conflicts=request.replace_conflicts,
            )
        except (MemoryConflictError, MemoryValidationError) as exc:
            _raise_memory_http_error(exc)
        return _memory_response(record)

    @app.get(
        "/agent/memories/{memory_id}/confirmation-preview",
        response_model=MemoryConflictPreviewResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def confirmation_preview(
        memory_id: str,
        owner_id: str = Query(alias="ownerId"),
        scope_type: Literal["SCHOOL", "REGION", "RESOURCE"] = Query(alias="scopeType"),
        scope_id: str = Query(alias="scopeId"),
    ) -> MemoryConflictPreviewResponse:
        try:
            preview = await memory_repository.confirmation_preview(
                owner_id, scope_type, scope_id, memory_id
            )
        except (MemoryNotFoundError, MemoryValidationError) as exc:
            _raise_memory_http_error(exc)
        return _memory_conflict_preview_response(preview)

    @app.patch(
        "/agent/memories/{memory_id}",
        response_model=MemoryItem,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def update_memory(
        memory_id: str,
        request: MemoryUpdateRequest,
        owner_id: str = Query(alias="ownerId"),
        scope_type: Literal["SCHOOL", "REGION", "RESOURCE"] = Query(alias="scopeType"),
        scope_id: str = Query(alias="scopeId"),
    ) -> MemoryItem:
        updates: dict[str, Any] = {}
        if "content" in request.model_fields_set:
            updates["content"] = request.content
        if "memory_type" in request.model_fields_set:
            updates["memory_type"] = request.memory_type
        if "field_key" in request.model_fields_set:
            updates["field_key"] = request.field_key
        try:
            record = await memory_repository.update_memory(
                owner_id,
                scope_type,
                scope_id,
                memory_id,
                replace_conflicts=request.replace_conflicts,
                **updates,
            )
        except (MemoryConflictError, MemoryNotFoundError, MemoryStateError, MemoryValidationError) as exc:
            _raise_memory_http_error(exc)
        return _memory_response(record)

    @app.post(
        "/agent/memories/{memory_id}/confirm",
        response_model=MemoryItem,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def confirm_memory(
        memory_id: str,
        request: MemoryResolutionRequest | None = None,
        owner_id: str = Query(alias="ownerId"),
        scope_type: Literal["SCHOOL", "REGION", "RESOURCE"] = Query(alias="scopeType"),
        scope_id: str = Query(alias="scopeId"),
    ) -> MemoryItem:
        try:
            record = await memory_repository.confirm_memory(
                owner_id,
                scope_type,
                scope_id,
                memory_id,
                replace_conflicts=request.replace_conflicts if request else False,
            )
        except (MemoryConflictError, MemoryNotFoundError, MemoryStateError, MemoryValidationError) as exc:
            _raise_memory_http_error(exc)
        return _memory_response(record)

    @app.delete(
        "/agent/memories/{memory_id}",
        response_model=MemoryItem,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def delete_memory(
        memory_id: str,
        owner_id: str = Query(alias="ownerId"),
        scope_type: Literal["SCHOOL", "REGION", "RESOURCE"] = Query(alias="scopeType"),
        scope_id: str = Query(alias="scopeId"),
    ) -> MemoryItem:
        try:
            record = await memory_repository.delete_memory(
                owner_id, scope_type, scope_id, memory_id
            )
        except (MemoryNotFoundError, MemoryValidationError) as exc:
            _raise_memory_http_error(exc)
        return _memory_response(record)

    @app.post(
        "/agent/memories/{memory_id}/restore",
        response_model=MemoryItem,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def restore_memory(
        memory_id: str,
        request: MemoryResolutionRequest | None = None,
        owner_id: str = Query(alias="ownerId"),
        scope_type: Literal["SCHOOL", "REGION", "RESOURCE"] = Query(alias="scopeType"),
        scope_id: str = Query(alias="scopeId"),
    ) -> MemoryItem:
        try:
            record = await memory_repository.restore_memory(
                owner_id,
                scope_type,
                scope_id,
                memory_id,
                replace_conflicts=request.replace_conflicts if request else False,
            )
        except (MemoryConflictError, MemoryNotFoundError, MemoryStateError, MemoryValidationError) as exc:
            _raise_memory_http_error(exc)
        return _memory_response(record)

    @app.delete(
        "/agent/memories/{memory_id}/permanent",
        status_code=204,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def permanently_delete_memory(
        memory_id: str,
        owner_id: str = Query(alias="ownerId"),
        scope_type: Literal["SCHOOL", "REGION", "RESOURCE"] = Query(alias="scopeType"),
        scope_id: str = Query(alias="scopeId"),
    ) -> Response:
        try:
            await memory_repository.permanent_delete(
                owner_id, scope_type, scope_id, memory_id
            )
        except (MemoryNotFoundError, MemoryStateError, MemoryValidationError) as exc:
            _raise_memory_http_error(exc)
        return Response(status_code=204)

    @app.get("/metrics", response_class=PlainTextResponse)
    async def metrics() -> str:
        return await observability.prometheus_metrics()

    @app.get("/admin/observability/traces")
    async def llm_traces(
        user_id: str | None = Query(default=None, alias="userId"),
        session_id: str | None = Query(default=None, alias="sessionId"),
        feature: str | None = None,
        model_name: str | None = Query(default=None, alias="model"),
        status: str | None = None,
        trace_id: str | None = Query(default=None, alias="traceId"),
        started_after: str | None = Query(default=None, alias="startedAfter"),
        started_before: str | None = Query(default=None, alias="startedBefore"),
        limit: int = Query(default=100, ge=1, le=500),
        offset: int = Query(default=0, ge=0),
        _admin: None = Depends(require_observability_admin),
    ) -> list[dict[str, Any]]:
        return await observability.traces(
            {
                "user_id": user_id, "session_id": session_id, "feature": feature,
                "model": model_name, "status": status, "trace_id": trace_id,
                "started_after": started_after, "started_before": started_before,
            },
            limit,
            offset,
        )

    @app.get("/admin/observability/summary")
    async def llm_summary(
        user_id: str | None = Query(default=None, alias="userId"),
        session_id: str | None = Query(default=None, alias="sessionId"),
        feature: str | None = None,
        model_name: str | None = Query(default=None, alias="model"),
        status: str | None = None,
        trace_id: str | None = Query(default=None, alias="traceId"),
        started_after: str | None = Query(default=None, alias="startedAfter"),
        started_before: str | None = Query(default=None, alias="startedBefore"),
        include_question_metrics: bool = Query(default=False, alias="includeQuestionMetrics"),
        _admin: None = Depends(require_observability_admin),
    ) -> dict[str, Any]:
        summary = await observability.summary({
            "user_id": user_id, "session_id": session_id, "feature": feature,
            "model": model_name, "status": status, "trace_id": trace_id,
            "started_after": started_after, "started_before": started_before,
        })
        if include_question_metrics:
            summary["completedQuestionCount"] = (
                await repository.count_completed_formal_account_chat_turns()
            )
        return summary

    @app.get("/admin/observability/tool-traces")
    async def tool_traces(
        tool_name: str | None = Query(default=None, alias="toolName"),
        status: str | None = Query(default=None),
        limit: int = Query(default=50, ge=1, le=100),
        _admin: None = Depends(require_observability_admin),
    ) -> list[dict[str, Any]]:
        return await repository.list_tool_audits(tool_name, status, limit)

    @app.get("/admin/memory-metrics")
    async def memory_metrics(
        _admin: None = Depends(require_observability_admin),
    ) -> dict[str, Any]:
        return await memory_repository.aggregate_metrics()

    @app.post(
        "/agent/threads", response_model=ThreadResponse, status_code=201,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def create_thread(request: ThreadCreateRequest) -> ThreadResponse:
        record = await runtime.create_thread(
            request.owner_id, request.scope_type, request.scope_id
        )
        return await _thread_response(runtime, record, include_messages=False)

    @app.get(
        "/agent/threads", response_model=list[ThreadSummaryResponse],
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def list_threads(
        owner_id: str = Query(alias="ownerId"),
        task_type: str = Query(default="CHAT", alias="taskType"),
        scope_type: str | None = Query(default=None, alias="scopeType"),
        scope_id: str | int | None = Query(default=None, alias="scopeId"),
        limit: int = Query(default=50, ge=1, le=100),
        status: Literal["active", "archived"] = Query(default="active"),
    ) -> list[ThreadSummaryResponse]:
        return [
            ThreadSummaryResponse(
                threadId=item.thread_id, scopeType=item.scope_type, scopeId=item.scope_id,
                title=item.title, preview=item.preview, messageCount=item.message_count,
                createdAt=item.created_at, updatedAt=item.updated_at,
            )
            for item in await repository.list_threads(
                owner_id, task_type, scope_type, scope_id, limit, status
            )
        ]

    @app.get(
        "/agent/messages/recovery/{client_turn_id}", response_model=TurnRecoveryResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def recover_assistant_message(
        client_turn_id: str,
        owner_id: str = Query(alias="ownerId"),
        scope_type: str = Query(alias="scopeType"),
        scope_id: str | int = Query(alias="scopeId"),
    ) -> TurnRecoveryResponse:
        try:
            turn = await turn_repository.get(
                client_turn_id,
                owner_id=owner_id,
                scope_type=scope_type,
                scope_id=scope_id,
            )
        except PermissionError as exc:
            LOGGER.info(
                "agent_turn_recovery_scope_miss",
                extra={"errorType": type(exc).__name__},
            )
            return TurnRecoveryResponse(
                found=False, clientTurnId=client_turn_id
            )
        if turn is None:
            return TurnRecoveryResponse(
                found=False, clientTurnId=client_turn_id
            )
        messages = await repository.list_messages_for_turn(turn.turn_id)
        pending_action = await action_repository.pending_for_turn(turn.turn_id)
        assistant = next(
            (item for item in messages if item["role"] == "assistant"), None
        )
        stored = (
            StoredMessage(
                id=assistant["id"],
                role=assistant["role"],
                content=assistant["content"],
                createdAt=assistant["created_at"],
                metadata=assistant["metadata"],
            )
            if assistant is not None
            else None
        )
        return TurnRecoveryResponse(
            found=turn.status == "completed" and stored is not None,
            clientTurnId=client_turn_id,
            threadId=turn.thread_id,
            message=stored if turn.status == "completed" else None,
            turnStatus=turn.status,
            retryable=turn.retryable,
            partialMessage=stored if turn.status != "completed" else None,
            pendingAction=(
                action_response(pending_action) if pending_action is not None else None
            ),
        )

    @app.get(
        "/agent/actions/{action_id}",
        response_model=AgentActionResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def get_agent_action(
        action_id: str,
        owner_id: str = Query(alias="ownerId"),
        scope_type: str = Query(alias="scopeType"),
        scope_id: str | int = Query(alias="scopeId"),
    ) -> AgentActionResponse:
        try:
            action = await action_repository.get_for_scope(
                action_id, owner_id, scope_type, scope_id
            )
        except PermissionError as exc:
            raise HTTPException(status_code=404, detail="action not found") from exc
        if action is None:
            raise HTTPException(status_code=404, detail="action not found")
        return action_response(action)

    @app.post(
        "/agent/actions/{action_id}/decision",
        response_model=AgentActionResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def decide_agent_action(
        action_id: str, request: AgentActionDecisionRequest
    ) -> AgentActionResponse:
        try:
            action = await action_repository.decide(
                action_id=action_id,
                decision=request.decision,
                owner_id=request.owner_id,
                scope_type=request.scope_type,
                scope_id=request.scope_id,
            )
        except (LookupError, PermissionError) as exc:
            raise HTTPException(status_code=404, detail="action not found") from exc
        except ActionConflictError as exc:
            raise HTTPException(
                status_code=409,
                detail={"code": exc.code, "message": str(exc)},
            ) from exc
        return action_response(action)

    @app.post(
        "/agent/turns/{client_turn_id}/cancel",
        response_model=TurnCancelResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def cancel_turn(
        client_turn_id: str,
        owner_id: str = Query(alias="ownerId"),
        scope_type: str = Query(alias="scopeType"),
        scope_id: str | int = Query(alias="scopeId"),
    ) -> TurnCancelResponse:
        try:
            existing = await turn_repository.get(
                client_turn_id,
                owner_id=owner_id,
                scope_type=scope_type,
                scope_id=scope_id,
            )
            if existing is not None:
                pending = await action_repository.pending_for_turn(existing.turn_id)
                if pending is not None:
                    await action_repository.decide(
                        action_id=pending.action_id,
                        decision="reject",
                        owner_id=owner_id,
                        scope_type=scope_type,
                        scope_id=scope_id,
                    )
            turn = await runtime.cancel_turn(
                client_turn_id, owner_id, scope_type, scope_id
            )
        except (LookupError, PermissionError) as exc:
            raise HTTPException(status_code=404, detail="turn not found") from exc
        return TurnCancelResponse(
            clientTurnId=client_turn_id,
            threadId=turn.thread_id,
            turnStatus=turn.status,
            cancellationRequested=(
                turn.cancel_requested_at is not None
                or turn.status == "cancelled"
            ),
        )

    @app.get(
        "/agent/threads/{thread_id}", response_model=ThreadResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def get_thread(
        thread_id: str,
        owner_id: str = Query(alias="ownerId"),
        scope_type: str | None = Query(default=None, alias="scopeType"),
        scope_id: str | int | None = Query(default=None, alias="scopeId"),
    ) -> ThreadResponse:
        try:
            record = await repository.get_thread(
                thread_id, owner_id, scope_type, scope_id
            )
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc
        return await _thread_response(runtime, record)

    @app.post(
        "/agent/threads/{thread_id}/messages", response_model=AgentMessageResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def send_thread_message(thread_id: str, request: AgentMessageRequest) -> AgentMessageResponse:
        validate_model_selection(request)
        if request.thread_id and request.thread_id != thread_id:
            raise HTTPException(status_code=400, detail="threadId does not match URL")
        request.thread_id = thread_id
        try:
            return await runtime.handle(request)
        except ActionConfirmationRequired as exc:
            raise HTTPException(
                status_code=409,
                detail={
                    "code": "action_confirmation_required",
                    "pendingAction": action_response(exc.action).model_dump(
                        by_alias=True, mode="json"
                    ),
                },
            ) from exc
        except TurnConflictError as exc:
            raise_turn_conflict(exc)
        except TurnLeaseLostError as exc:
            raise HTTPException(
                status_code=409,
                detail={"code": "turn_in_progress", "message": str(exc)},
            ) from exc
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc
        except MemoryValidationError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @app.post(
        "/agent/messages", response_model=AgentMessageResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def send_message(request: AgentMessageRequest) -> AgentMessageResponse:
        validate_model_selection(request)
        try:
            return await runtime.handle(request)
        except ActionConfirmationRequired as exc:
            raise HTTPException(
                status_code=409,
                detail={
                    "code": "action_confirmation_required",
                    "pendingAction": action_response(exc.action).model_dump(
                        by_alias=True, mode="json"
                    ),
                },
            ) from exc
        except TurnConflictError as exc:
            raise_turn_conflict(exc)
        except TurnLeaseLostError as exc:
            raise HTTPException(
                status_code=409,
                detail={"code": "turn_in_progress", "message": str(exc)},
            ) from exc
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc
        except MemoryValidationError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @app.post(
        "/agent/messages/stream", response_class=StreamingResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def stream_message(request: AgentMessageRequest) -> StreamingResponse:
        validate_model_selection(request)
        if request.thread_id:
            try:
                await repository.require_thread(
                    request.thread_id, request.owner_id, request.scope_type, request.scope_id
                )
            except (ThreadNotFoundError, ThreadScopeError) as exc:
                raise HTTPException(status_code=404, detail="thread not found") from exc
        try:
            event_stream = await runtime.start_stream(request)
        except TurnConflictError as exc:
            raise_turn_conflict(exc)
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc
        return DisconnectAwareStreamingResponse(
            event_stream,
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no",
            },
        )

    @app.post(
        "/agent/threads/{thread_id}/archive", response_model=ThreadResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def archive_thread(
        thread_id: str,
        owner_id: str = Query(alias="ownerId"),
        scope_type: str | None = Query(default=None, alias="scopeType"),
        scope_id: str | int | None = Query(default=None, alias="scopeId"),
    ) -> ThreadResponse:
        try:
            await repository.archive_thread(
                thread_id, owner_id, scope_type, scope_id
            )
            record = await repository.get_thread(thread_id, owner_id)
        except ThreadScopeError:
            raise HTTPException(status_code=404, detail="thread not found")
        except ThreadNotFoundError as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc
        return await _thread_response(runtime, record)

    @app.post(
        "/agent/threads/{thread_id}/restore", response_model=ThreadResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def restore_thread(
        thread_id: str,
        owner_id: str = Query(alias="ownerId"),
        scope_type: str | None = Query(default=None, alias="scopeType"),
        scope_id: str | int | None = Query(default=None, alias="scopeId"),
    ) -> ThreadResponse:
        try:
            await repository.restore_thread(
                thread_id, owner_id, scope_type, scope_id
            )
            record = await repository.get_thread(
                thread_id, owner_id, scope_type, scope_id
            )
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc
        return await _thread_response(runtime, record)

    @app.get("/admin/prompts/{prompt_key}/versions")
    async def list_prompt_versions(
        prompt_key: str, _admin: None = Depends(require_prompt_admin)
    ) -> list[dict[str, Any]]:
        return await prompts.list_versions(prompt_key)

    @app.get("/admin/prompts/{prompt_key}/versions/{version}")
    async def get_prompt_version(
        prompt_key: str, version: str, _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        try:
            return await prompts.get_version(prompt_key, version)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.post("/admin/prompts/{prompt_key}/versions", status_code=201)
    async def create_prompt_version(
        prompt_key: str, payload: dict[str, Any], _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        try:
            return await prompts.create_version(
                prompt_key,
                str(payload.get("version") or ""),
                str(payload.get("content") or ""),
                str(payload.get("createdBy") or "admin"),
                str(payload.get("notes") or ""),
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except PromptVersionExistsError as exc:
            raise HTTPException(status_code=409, detail="prompt version already exists") from exc

    @app.post("/admin/prompts/{prompt_key}/versions/{version}/activate")
    async def activate_prompt_version(
        prompt_key: str, version: str, _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        try:
            result = await prompts.activate_version(prompt_key, version)
            runtime.invalidate_prompt(prompt_key)
            return result
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get("/admin/prompts/{prompt_key}/experiment")
    async def get_prompt_experiment(
        prompt_key: str, _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        return await prompts.get_experiment(prompt_key)

    @app.put("/admin/prompts/{prompt_key}/experiment")
    async def configure_prompt_experiment(
        prompt_key: str, payload: dict[str, Any], _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        try:
            return await prompts.configure_experiment(
                prompt_key,
                str(payload.get("experimentKey") or ""),
                payload.get("variants") or [],
                bool(payload.get("active")),
            )
        except (ValueError, LookupError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/admin/prompts/{prompt_key}/metrics")
    async def prompt_metrics(
        prompt_key: str, _admin: None = Depends(require_prompt_admin)
    ) -> list[dict[str, Any]]:
        return await prompts.metrics(prompt_key)

    @app.post("/admin/prompt-runs/{run_id}/feedback")
    async def prompt_run_feedback(
        run_id: str, payload: dict[str, Any], _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        try:
            return await prompts.add_feedback(
                run_id, float(payload.get("qualityScore")), str(payload.get("feedback") or "")
            )
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    # 兼容历史接口，统一由旧接口模块承载，避免影响新的 Stateful Agent 协议。
    @app.post("/llm/town/explain")
    async def explain_town(payload: dict[str, Any]) -> dict[str, Any]:
        return build_map_answer(payload, school_mode=False, ask_mode=False)

    @app.post("/llm/town/ask")
    async def ask_town(payload: dict[str, Any]) -> dict[str, Any]:
        return build_map_answer(payload, school_mode=False, ask_mode=True)

    @app.post("/llm/school/explain")
    async def explain_school(payload: dict[str, Any]) -> dict[str, Any]:
        return build_map_answer(payload, school_mode=True, ask_mode=False)

    @app.post("/llm/school/ask")
    async def ask_school(payload: dict[str, Any]) -> dict[str, Any]:
        return build_map_answer(payload, school_mode=True, ask_mode=True)

    @app.post("/llm/teaching-plan/generate")
    async def generate_teaching_plan(payload: dict[str, Any]) -> dict[str, Any]:
        return await build_structured_teaching_plan(payload, model, prompts)

    @app.post("/llm/teaching-plan/generate/stream")
    async def stream_teaching_plan(payload: dict[str, Any]) -> StreamingResponse:
        async def events():
            async for event_name, data in stream_structured_teaching_plan(payload, model, prompts):
                yield f"event: {event_name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

        return StreamingResponse(
            events(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    app.include_router(health_router)
    return app
