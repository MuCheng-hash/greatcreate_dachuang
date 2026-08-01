from __future__ import annotations

from dataclasses import dataclass

from fastapi import Request

from agent.config import AgentSettings
from agent.runtime import AgentRuntime as LegacyAgentRuntime

from .business_tool_client import BusinessToolClient
from .health import HealthService
from .model_gateway import ModelGateway
from .observability import FallbackAlertManager, LlmObservability
from .prompt_manager import PromptManager
from .repository import ConversationRepository
from .runtime import AgentRuntime
from .settings import Settings
from .user_memory import MemoryContentPolicy, MemoryRepository


@dataclass(frozen=True, slots=True)
class AppContainer:
    settings: Settings
    repository: ConversationRepository
    memory_repository: MemoryRepository
    observability: LlmObservability
    alerts: FallbackAlertManager
    model_gateway: ModelGateway
    prompts: PromptManager
    runtime: AgentRuntime
    legacy_agent_runtime: LegacyAgentRuntime
    health: HealthService
    business_tool_client: BusinessToolClient


def build_container(
    settings: Settings,
    observability: LlmObservability | None = None,
    alerts: FallbackAlertManager | None = None,
) -> AppContainer:
    repository = ConversationRepository(settings.database_path)
    memory_repository = MemoryRepository(
        settings.database_path,
        content_policy=MemoryContentPolicy(
            settings.agent_memory_content_character_limit
        ),
        pending_days=settings.agent_memory_pending_days,
        task_days=settings.agent_memory_task_days,
        recycle_bin_days=settings.agent_memory_recycle_bin_days,
    )
    observability = observability or LlmObservability(
        settings.database_path, settings.llm_model_pricing
    )
    alerts = alerts or FallbackAlertManager(settings.llm_alert_webhook_url)
    model_gateway = ModelGateway(settings, observability, alerts)
    prompts = PromptManager(settings.database_path, settings.prompt_root, settings.agent_prompt_version)
    business_tool_client = BusinessToolClient(
        settings.internal_business_base_url,
        settings.internal_service_token,
        settings.agent_tool_timeout_seconds,
    )
    runtime = AgentRuntime(
        settings,
        repository,
        model_gateway,
        observability,
        alerts,
        prompts,
        business_tool_client,
        memory_repository,
    )
    legacy_agent_runtime = LegacyAgentRuntime(
        AgentSettings.from_settings(settings), observability, alerts
    )
    health = HealthService(settings, prompts)
    return AppContainer(
        settings=settings,
        repository=repository,
        memory_repository=memory_repository,
        observability=observability,
        alerts=alerts,
        model_gateway=model_gateway,
        prompts=prompts,
        runtime=runtime,
        legacy_agent_runtime=legacy_agent_runtime,
        health=health,
        business_tool_client=business_tool_client,
    )


def get_container(request: Request) -> AppContainer:
    return request.app.state.container
