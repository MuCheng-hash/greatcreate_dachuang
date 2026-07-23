from __future__ import annotations

import secrets
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import ValidationError

from agent.config import AgentSettings as LegacyAgentSettings
from agent.runtime import AgentRuntime as LegacyAgentRuntime

from .legacy import (
    build_agent_answer,
    build_map_answer,
    build_resource_discovery_classification,
    build_structured_teaching_plan,
)
from .model_gateway import ModelGateway
from .repository import ConversationRepository, ThreadNotFoundError, ThreadScopeError
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


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    repository = ConversationRepository(settings.database_path)
    model = ModelGateway(settings)
    runtime = AgentRuntime(settings, repository, model)
    legacy_runtime = LegacyAgentRuntime(LegacyAgentSettings.from_env())
    app = FastAPI(title="Red Culture Stateful Agent", version="2.0.0")
    app.state.settings = settings
    app.state.runtime = runtime
    app.state.model = model
    app.state.legacy_runtime = legacy_runtime

    async def require_internal_agent_token(
        token: str | None = Header(default=None, alias="X-Agent-Service-Token"),
    ) -> None:
        expected = settings.internal_service_token.strip()
        if expected and not secrets.compare_digest(token or "", expected):
            raise HTTPException(status_code=401, detail="agent service token is invalid")

    if settings.allowed_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.allowed_origins,
            allow_credentials=True,
            allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type", "X-Agent-Service-Token"],
        )

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return {
            "status": "ok",
            "service": settings.service_name,
            "agentModelConfigured": settings.model_configured,
            "agentRuntime": "langchain-langgraph",
        }

    @app.get("/health/live")
    async def health_live() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/health/ready")
    async def health_ready() -> dict[str, Any]:
        chain = settings.model_chain()
        primary = chain[0]
        fallback = chain[1] if len(chain) > 1 else None
        return {
            "status": "ready" if settings.internal_service_token else "ready",
            "businessServiceBaseUrl": "configured",
            "agentModelConfigured": settings.primary_model_configured(),
            "primaryProvider": primary.provider,
            "primaryModel": primary.model,
            "fallbackModelConfigured": settings.fallback_model_configured(),
            "fallbackProvider": fallback.provider if fallback else None,
            "fallbackModel": fallback.model if fallback else None,
        }

    @app.post(
        "/agent/threads", response_model=ThreadResponse, status_code=201,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def create_thread(request: ThreadCreateRequest) -> ThreadResponse:
        record = runtime.create_thread(request.owner_id, request.scope_type, request.scope_id)
        return _thread_response(runtime, record, include_messages=False)

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
            record = repository.require_thread(thread_id, owner_id, scope_type, scope_id)
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc
        return _thread_response(runtime, record)

    @app.post(
        "/agent/threads/{thread_id}/messages", response_model=AgentMessageResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def send_thread_message(thread_id: str, request: AgentMessageRequest) -> AgentMessageResponse:
        if request.thread_id and request.thread_id != thread_id:
            raise HTTPException(status_code=400, detail="threadId does not match URL")
        request.thread_id = thread_id
        try:
            return await runtime.handle(request)
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc

    @app.post(
        "/agent/messages", response_model=AgentMessageResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def send_message(request: AgentMessageRequest) -> AgentMessageResponse:
        try:
            return await runtime.handle(request)
        except (ThreadNotFoundError, ThreadScopeError) as exc:
            raise HTTPException(status_code=404, detail="thread not found") from exc

    @app.post(
        "/agent/messages/stream", response_class=StreamingResponse,
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def stream_message(request: AgentMessageRequest) -> StreamingResponse:
        if request.thread_id:
            try:
                repository.require_thread(
                    request.thread_id, request.owner_id, request.scope_type, request.scope_id
                )
            except (ThreadNotFoundError, ThreadScopeError) as exc:
                raise HTTPException(status_code=404, detail="thread not found") from exc
        return StreamingResponse(
            runtime.stream_events(request),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no",
            },
        )

    @app.post("/llm/agent/answer")
    async def answer_agent(payload: dict[str, Any] | None = None) -> dict[str, Any]:
        return await build_agent_answer(payload or {}, model)

    @app.post(
        "/llm/agent/run",
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def run_legacy_agent(payload: dict[str, Any] | None = None) -> dict[str, Any]:
        try:
            return legacy_runtime.run(payload or {})
        except ValidationError as exception:
            raise HTTPException(status_code=422, detail=exception.errors()) from exception
        except ValueError as exception:
            raise HTTPException(status_code=409, detail=str(exception)) from exception

    @app.post(
        "/llm/agent/stream",
        dependencies=[Depends(require_internal_agent_token)],
    )
    async def stream_legacy_agent(payload: dict[str, Any] | None = None) -> StreamingResponse:
        return StreamingResponse(
            legacy_runtime.stream_events(payload or {}),
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
        return await build_structured_teaching_plan(payload, model)

    @app.post("/llm/resource-discovery/classify")
    async def classify_resource_discovery(payload: dict[str, Any]) -> dict[str, Any]:
        return await build_resource_discovery_classification(payload, model)

    return app
