from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware

from .legacy import (
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
    app = FastAPI(title="Red Culture Stateful Agent", version="2.0.0")
    app.state.settings = settings
    app.state.runtime = runtime
    app.state.model = model

    if settings.allowed_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.allowed_origins,
            allow_credentials=True,
            allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type"],
        )

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return {
            "status": "ok",
            "service": settings.service_name,
            "agentModelConfigured": settings.model_configured,
            "agentRuntime": "langchain-langgraph",
        }

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
        return await build_structured_teaching_plan(payload, model)

    @app.post("/llm/resource-discovery/classify")
    async def classify_resource_discovery(payload: dict[str, Any]) -> dict[str, Any]:
        return await build_resource_discovery_classification(payload, model)

    return app
