from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from ..container import AppContainer, get_container


router = APIRouter(tags=["health"])


@router.get("/health/live")
async def live(container: AppContainer = Depends(get_container)) -> dict[str, Any]:
    return container.health.live()


@router.get("/health/ready")
async def ready(container: AppContainer = Depends(get_container)) -> JSONResponse:
    is_ready, payload = await container.health.ready()
    return JSONResponse(payload, status_code=200 if is_ready else 503)


@router.get("/health")
async def compatibility_health(
    container: AppContainer = Depends(get_container),
) -> JSONResponse:
    is_ready, payload = await container.health.ready()
    payload.update({
        "agentRuntime": "langchain-langgraph",
        "llmObservability": "sqlite-traces",
        "agentModelConfigured": container.settings.model_configured,
    })
    return JSONResponse(payload, status_code=200 if is_ready else 503)

