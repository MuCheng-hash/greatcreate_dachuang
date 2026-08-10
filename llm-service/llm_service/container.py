from __future__ import annotations

from dataclasses import dataclass

from fastapi import Request

from .business_tool_client import BusinessToolClient
from .checkpointing import CheckpointManager
from .database import Database, SchemaMigrator
from .health import HealthService
from .model_gateway import ModelGateway
from .observability import FallbackAlertManager, LlmObservability
from .prompt_manager import PromptManager
from .repository import ConversationRepository
from .runtime import AgentRuntime
from .settings import Settings
from .turns import AgentTurnRepository
from .user_memory import MemoryContentPolicy, MemoryRepository


@dataclass(frozen=True, slots=True)
class AppContainer:
    settings: Settings
    database: Database
    migrator: SchemaMigrator
    repository: ConversationRepository
    turn_repository: AgentTurnRepository
    checkpoints: CheckpointManager
    memory_repository: MemoryRepository
    observability: LlmObservability
    alerts: FallbackAlertManager
    model_gateway: ModelGateway
    prompts: PromptManager
    runtime: AgentRuntime
    health: HealthService
    business_tool_client: BusinessToolClient


def build_container(
    settings: Settings,
    observability: LlmObservability | None = None,
    alerts: FallbackAlertManager | None = None,
) -> AppContainer:
    database = Database(settings)
    migrator = SchemaMigrator(database, settings.migration_dsn)
    repository = ConversationRepository(database)
    turn_repository = AgentTurnRepository(database)
    checkpoints = CheckpointManager(database)
    memory_repository = MemoryRepository(
        database,
        content_policy=MemoryContentPolicy(
            settings.agent_memory_content_character_limit
        ),
        pending_days=settings.agent_memory_pending_days,
        task_days=settings.agent_memory_task_days,
        recycle_bin_days=settings.agent_memory_recycle_bin_days,
    )
    observability = observability or LlmObservability(
        database, settings.llm_model_pricing
    )
    alerts = alerts or FallbackAlertManager(settings.llm_alert_webhook_url)
    model_gateway = ModelGateway(settings, observability, alerts)
    prompts = PromptManager(
        database, settings.prompt_root, settings.agent_prompt_version
    )
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
        turn_repository,
        checkpoints,
    )
    health = HealthService(
        settings,
        database,
        migrator,
        prompts,
        business_tool_client,
        checkpoints,
    )
    return AppContainer(
        settings=settings,
        database=database,
        migrator=migrator,
        repository=repository,
        turn_repository=turn_repository,
        checkpoints=checkpoints,
        memory_repository=memory_repository,
        observability=observability,
        alerts=alerts,
        model_gateway=model_gateway,
        prompts=prompts,
        runtime=runtime,
        health=health,
        business_tool_client=business_tool_client,
    )


def get_container(request: Request) -> AppContainer:
    return request.app.state.container
