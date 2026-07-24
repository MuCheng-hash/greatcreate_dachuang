from __future__ import annotations

import asyncio
import uvicorn

from llm_service.api import create_app
from llm_service.bootstrap import create_application
from llm_service.container import build_container
from llm_service.model_gateway import ModelGateway
from llm_service.legacy_api import (
    LLM_API_KEY,
    LLM_API_URL,
    LLM_BASE_URL,
    LLM_MODEL,
    build_agent_answer,
    build_agent_answer_fallback,
    build_agent_answer_prompt,
    build_ask_answer,
    build_explain_answer,
    build_school_ask_answer,
    build_school_explain_answer,
    build_teaching_plan_fallback,
    compact_list,
    parse_model_json,
)
from llm_service.observability import FallbackAlertManager, LlmObservability, LlmTraceContext
from llm_service.settings import get_settings


settings = get_settings()
app = create_app(settings)
service_settings = get_settings()
container = build_container(service_settings)
settings = container.legacy_agent_runtime.settings
observability: LlmObservability = container.observability
alerts: FallbackAlertManager = container.alerts
agent_runtime = container.legacy_agent_runtime


def resolve_llm_api_url() -> str | None:
    configured_url = (LLM_API_URL or LLM_BASE_URL).strip()
    if not configured_url:
        return None
    normalized = configured_url.rstrip("/")
    return normalized if normalized.endswith("/chat/completions") else normalized + "/chat/completions"


def call_openai_compatible(
    prompt: str, trace_context: LlmTraceContext | None = None
) -> dict | None:
    trace_context = trace_context or LlmTraceContext(feature="legacy-agent-answer")
    router_settings = service_settings.model_copy(update={
        "llm_api_url": resolve_llm_api_url() or service_settings.llm_api_url,
        "llm_api_key": LLM_API_KEY or service_settings.llm_api_key,
        "llm_model": LLM_MODEL or service_settings.llm_model,
    })
    router = ModelGateway(router_settings, observability, alerts)
    return asyncio.run(router.generate_json(
        prompt,
        trace_context,
        lambda value: bool(str(value.get("answer") or "").strip()),
    ))


app = create_application(container=container)


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=5050, workers=1)
    uvicorn.run(app, host=service_settings.host, port=service_settings.port, workers=1)
