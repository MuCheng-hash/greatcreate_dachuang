from __future__ import annotations

from pathlib import Path
import json
import hmac
import sqlite3
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import PlainTextResponse, StreamingResponse

from .container import AppContainer, build_container
from .legacy_api import router as legacy_router
from .legacy import (
    build_map_answer,
    build_resource_discovery_classification,
    build_structured_teaching_plan,
    stream_structured_teaching_plan,
)
from .observability import FallbackAlertManager, LlmObservability
from .repository import ThreadNotFoundError, ThreadScopeError
from .routes import health_router
from .runtime import AgentRuntime
from .schemas import AgentMessageRequest, AgentMessageResponse, StoredMessage, ThreadCreateRequest, ThreadResponse
from .settings import Settings, get_settings


def _thread_response(runtime: AgentRuntime, record: Any, include_messages: bool = True) -> ThreadResponse:
    messages = []
    if include_messages:
        for item in runtime.repository.list_messages(record.thread_id):
            messages.append(StoredMessage(
                id=item["id"], role=item["role"], content=item["content"],
                createdAt=item["created_at"], metadata=item["metadata"],
            ))
    return ThreadResponse(
        threadId=record.thread_id, ownerId=record.owner_id, scopeType=record.scope_type,
        scopeId=record.scope_id, status=record.status, summary=record.summary,
        createdAt=record.created_at, updatedAt=record.updated_at, messages=messages,
    )


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
    app = FastAPI(title="Red Culture Stateful Agent", version="2.0.0")
    app.state.container = container
    app.state.settings = settings
    app.state.runtime = runtime
    app.state.model = model
    app.state.prompts = prompts
    app.state.observability = observability
    app.state.alerts = alerts

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

    if settings.allowed_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.allowed_origins,
            allow_credentials=True,
            allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
            allow_headers=[
                "Authorization", "Content-Type", "X-Prompt-Admin-Token",
                "X-Observability-Admin-Token",
            ],
        )

    @app.get("/metrics", response_class=PlainTextResponse)
    async def metrics() -> str:
        return observability.prometheus_metrics()

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
        return observability.traces(
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
        _admin: None = Depends(require_observability_admin),
    ) -> dict[str, Any]:
        return observability.summary({
            "user_id": user_id, "session_id": session_id, "feature": feature,
            "model": model_name, "status": status, "trace_id": trace_id,
            "started_after": started_after, "started_before": started_before,
        })

    @app.post("/agent/threads", response_model=ThreadResponse, status_code=201)
    async def create_thread(request: ThreadCreateRequest) -> ThreadResponse:
        record = runtime.create_thread(request.owner_id, request.scope_type, request.scope_id)
        return _thread_response(runtime, record, include_messages=False)

    @app.get("/agent/threads/{thread_id}", response_model=ThreadResponse)
    async def get_thread(
        thread_id: str,
        owner_id: str = Query(alias="ownerId"),
        scope_type: str | None = Query(default=None, alias="scopeType"),
        scope_id: str | int | None = Query(default=None, alias="scopeId"),
    ) -> ThreadResponse:
        try:
            record = repository.require_thread(thread_id, owner_id, scope_type, scope_id)
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc
        return _thread_response(runtime, record)

    @app.post("/agent/threads/{thread_id}/messages", response_model=AgentMessageResponse)
    async def send_thread_message(thread_id: str, request: AgentMessageRequest) -> AgentMessageResponse:
        if request.thread_id and request.thread_id != thread_id:
            raise HTTPException(status_code=400, detail="threadId does not match URL")
        request.thread_id = thread_id
        try:
            return await runtime.handle(request)
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc

    @app.post("/agent/messages", response_model=AgentMessageResponse)
    async def send_message(request: AgentMessageRequest) -> AgentMessageResponse:
        try:
            return await runtime.handle(request)
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc

    @app.post("/agent/threads/{thread_id}/archive", response_model=ThreadResponse)
    async def archive_thread(thread_id: str, owner_id: str = Query(alias="ownerId")) -> ThreadResponse:
        try:
            repository.archive_thread(thread_id, owner_id)
            record = repository.get_thread(thread_id, owner_id)
        except ThreadScopeError:
            raise HTTPException(status_code=404, detail="thread not found")
        except ThreadNotFoundError as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc
        return _thread_response(runtime, record)

    # Legacy deterministic routes. They deliberately remain one-shot and do not share Agent state.
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

    @app.get("/admin/prompts/{prompt_key}/versions")
    async def list_prompt_versions(
        prompt_key: str, _admin: None = Depends(require_prompt_admin)
    ) -> list[dict[str, Any]]:
        return prompts.list_versions(prompt_key)

    @app.get("/admin/prompts/{prompt_key}/versions/{version}")
    async def get_prompt_version(
        prompt_key: str, version: str, _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        try:
            return prompts.get_version(prompt_key, version)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.post("/admin/prompts/{prompt_key}/versions", status_code=201)
    async def create_prompt_version(
        prompt_key: str, payload: dict[str, Any], _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        try:
            return prompts.create_version(
                prompt_key,
                str(payload.get("version") or ""),
                str(payload.get("content") or ""),
                str(payload.get("createdBy") or "admin"),
                str(payload.get("notes") or ""),
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="prompt version already exists") from exc

    @app.post("/admin/prompts/{prompt_key}/versions/{version}/activate")
    async def activate_prompt_version(
        prompt_key: str, version: str, _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        try:
            return prompts.activate_version(prompt_key, version)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get("/admin/prompts/{prompt_key}/experiment")
    async def get_prompt_experiment(
        prompt_key: str, _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        return prompts.get_experiment(prompt_key)

    @app.put("/admin/prompts/{prompt_key}/experiment")
    async def configure_prompt_experiment(
        prompt_key: str, payload: dict[str, Any], _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        try:
            return prompts.configure_experiment(
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
        return prompts.metrics(prompt_key)

    @app.post("/admin/prompt-runs/{run_id}/feedback")
    async def prompt_run_feedback(
        run_id: str, payload: dict[str, Any], _admin: None = Depends(require_prompt_admin)
    ) -> dict[str, Any]:
        try:
            return prompts.add_feedback(
                run_id, float(payload.get("qualityScore")), str(payload.get("feedback") or "")
            )
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.post("/llm/resource-discovery/classify")
    async def classify_resource_discovery(payload: dict[str, Any]) -> dict[str, Any]:
        return await build_resource_discovery_classification(payload, model)

    app.include_router(legacy_router)
    app.include_router(health_router)
    return app
