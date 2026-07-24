from __future__ import annotations

from dataclasses import dataclass

from fastapi import Request

from agent.config import AgentSettings
from agent.runtime import AgentRuntime as LegacyAgentRuntime

from .health import HealthService
from .model_gateway import ModelGateway
from .observability import FallbackAlertManager, LlmObservability
from .prompt_manager import PromptManager
from .repository import ConversationRepository
from .runtime import AgentRuntime
from .settings import Settings


@dataclass(frozen=True, slots=True)
class AppContainer:
    settings: Settings
    repository: ConversationRepository
    observability: LlmObservability
    alerts: FallbackAlertManager
    model_gateway: ModelGateway
    prompts: PromptManager
    runtime: AgentRuntime
    legacy_agent_runtime: LegacyAgentRuntime
    health: HealthService


def build_container(
    settings: Settings,
    observability: LlmObservability | None = None,
    alerts: FallbackAlertManager | None = None,
) -> AppContainer:
    repository = ConversationRepository(settings.database_path)
    observability = observability or LlmObservability(
        settings.database_path, settings.llm_model_pricing
    )
    alerts = alerts or FallbackAlertManager(settings.llm_alert_webhook_url)
    model_gateway = ModelGateway(settings, observability, alerts)
    prompts = PromptManager(settings.database_path, settings.prompt_root)
    runtime = AgentRuntime(settings, repository, model_gateway, observability, alerts, prompts)
    legacy_agent_runtime = LegacyAgentRuntime(
        AgentSettings.from_settings(settings), observability, alerts
    )
    health = HealthService(settings, prompts)
    return AppContainer(
        settings=settings,
        repository=repository,
        observability=observability,
        alerts=alerts,
        model_gateway=model_gateway,
        prompts=prompts,
        runtime=runtime,
        legacy_agent_runtime=legacy_agent_runtime,
        health=health,
    )


def get_container(request: Request) -> AppContainer:
    return request.app.state.container
