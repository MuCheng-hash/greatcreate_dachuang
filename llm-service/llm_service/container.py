from __future__ import annotations

import uuid
from dataclasses import dataclass

from fastapi import Request

from .business_tool_client import BusinessToolClient
from .actions import AgentActionRepository
from .checkpointing import CheckpointManager
from .database import Database, SchemaMigrator
from .health import HealthService
from .history_index import (
    ConversationHistoryRetriever,
    ConversationHistoryWorker,
    ConversationVectorStore,
    HistoryEmbeddingClient,
    HistoryIndexRepository,
)
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
    action_repository: AgentActionRepository
    checkpoints: CheckpointManager
    memory_repository: MemoryRepository
    observability: LlmObservability
    alerts: FallbackAlertManager
    model_gateway: ModelGateway
    prompts: PromptManager
    runtime: AgentRuntime
    health: HealthService
    business_tool_client: BusinessToolClient
    history_worker: ConversationHistoryWorker | None
    history_retriever: ConversationHistoryRetriever | None
    history_closeables: tuple[object, ...]


def build_container(
    settings: Settings,
    observability: LlmObservability | None = None,
    alerts: FallbackAlertManager | None = None,
    history_worker: ConversationHistoryWorker | None = None,
    history_retriever: ConversationHistoryRetriever | None = None,
) -> AppContainer:
    database = Database(settings)
    migrator = SchemaMigrator(database, settings.migration_dsn)
    repository = ConversationRepository(database)
    turn_repository = AgentTurnRepository(database)
    action_repository = AgentActionRepository(
        database, settings.agent_action_confirmation_minutes
    )
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
        write_tools_enabled=settings.agent_write_tools_enabled,
    )
    history_closeables: tuple[object, ...] = ()
    if settings.history_retrieval_configured:
        # When a test injects both services no outbound client is needed.  If it
        # injects only the worker, the default retriever owns the extra clients.
        if history_worker is None or history_retriever is None:
            embedding_client = HistoryEmbeddingClient(
                settings.embedding_api_url,
                settings.embedding_api_key,
                settings.embedding_model,
                settings.embedding_dimensions,
            )
            vector_store = ConversationVectorStore(
                settings.agent_history_qdrant_url,
                settings.agent_history_qdrant_api_key,
                settings.agent_history_vector_collection,
            )
            if history_worker is None:
                history_worker = ConversationHistoryWorker(
                    HistoryIndexRepository(database),
                    embedding_client,
                    vector_store,
                    f"agent-history-index:{uuid.uuid4().hex}",
                    batch_size=settings.agent_history_index_batch_size,
                    max_attempts=settings.agent_history_index_max_attempts,
                )
            else:
                history_closeables = (embedding_client, vector_store)
            if history_retriever is None:
                history_retriever = ConversationHistoryRetriever(
                    repository,
                    embedding_client,
                    vector_store,
                    enabled=True,
                    limit=settings.agent_history_retrieval_limit,
                    character_limit=settings.agent_history_retrieval_character_limit,
                    min_score=settings.agent_history_retrieval_min_score,
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
        action_repository,
        history_retriever,
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
        action_repository=action_repository,
        checkpoints=checkpoints,
        memory_repository=memory_repository,
        observability=observability,
        alerts=alerts,
        model_gateway=model_gateway,
        prompts=prompts,
        runtime=runtime,
        health=health,
        business_tool_client=business_tool_client,
        history_worker=history_worker,
        history_retriever=history_retriever,
        history_closeables=history_closeables,
    )


def get_container(request: Request) -> AppContainer:
    return request.app.state.container
