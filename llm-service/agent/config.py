from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from llm_service.settings import Settings


@dataclass(frozen=True)
class AgentModelConfig:
    """One independently configurable model in the Agent fallback chain."""

    provider: str = "openai-compatible"
    model: str = ""
    base_url: str = ""
    api_key: str = ""
    fallback_level: int = 0

    def configured(self) -> bool:
        # An empty base URL is valid for the legacy OpenAI-compatible default
        # endpoint. Provider-specific endpoints are still supplied through the
        # corresponding AGENT_*_BASE_URL setting.
        return bool(self.model and self.api_key)


@dataclass(frozen=True)
class AgentSettings:
    host: str = "127.0.0.1"
    port: int = 5050
    internal_business_base_url: str = "http://127.0.0.1:8080"
    internal_service_token: str = ""
    llm_api_url: str = ""
    llm_base_url: str = ""
    llm_api_key: str = ""
    primary_model: str = "qwen-plus"
    fallback_model: str = ""
    llm_timeout_seconds: float = 20.0
    max_iterations: int = 4
    max_history_messages: int = 20
    prompt_version: str = "v1"
    allowed_origins: tuple[str, ...] = ("http://localhost:8080", "http://127.0.0.1:8080")
    primary_provider: str = "openai-compatible"
    primary_base_url: str = ""
    primary_api_key: str = ""
    fallback_provider: str = ""
    fallback_base_url: str = ""
    fallback_api_key: str = ""
    lightweight_provider: str = ""
    lightweight_model: str = ""
    lightweight_base_url: str = ""
    lightweight_api_key: str = ""

    @classmethod
    def from_env(cls) -> "AgentSettings":
        from llm_service.settings import load_settings

        return cls.from_settings(load_settings())

    @classmethod
    def from_settings(cls, settings: "Settings") -> "AgentSettings":
        legacy_api_url = settings.llm_api_url.strip()
        legacy_base_url = settings.openai_base_url.strip()
        legacy_key = settings.llm_api_key.strip()
        primary_provider = (
            settings.agent_primary_provider.strip()
            or settings.primary_provider.strip()
            or settings.llm_provider.strip()
            or "openai-compatible"
        )
        primary_model = (
            settings.agent_primary_model.strip()
            or settings.primary_model.strip()
            or settings.llm_model.strip()
        )
        primary_base_url = (
            settings.agent_primary_base_url.strip()
            or settings.primary_base_url.strip()
            or legacy_api_url
            or legacy_base_url
        )
        if not primary_base_url and primary_provider.lower() == "bailian":
            primary_base_url = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        fallback_provider = (
            settings.agent_fallback_provider.strip()
            or settings.fallback_provider.strip()
            or settings.llm_fallback_provider.strip()
        )
        fallback_model = (
            settings.agent_fallback_model.strip()
            or settings.fallback_model.strip()
            or settings.llm_fallback_model.strip()
        )
        fallback_base_url = (
            settings.agent_fallback_base_url.strip()
            or settings.fallback_base_url.strip()
            or settings.llm_fallback_api_url.strip()
        )
        fallback_api_key = (
            settings.agent_fallback_api_key.strip()
            or settings.fallback_api_key.strip()
            or settings.llm_fallback_api_key.strip()
        )
        if fallback_provider.lower() == "ollama":
            fallback_base_url = fallback_base_url or "http://127.0.0.1:11434/v1"
            fallback_api_key = fallback_api_key or "ollama"
        lightweight_provider = settings.agent_lightweight_provider.strip() or "ollama"
        lightweight_model = (
            settings.agent_lightweight_model.strip()
            or settings.llm_lightweight_model.strip()
        )
        lightweight_base_url = (
            settings.agent_lightweight_base_url.strip()
            or settings.llm_lightweight_api_url.strip()
        )
        lightweight_api_key = (
            settings.agent_lightweight_api_key.strip()
            or settings.llm_lightweight_api_key.strip()
        )
        if lightweight_provider.lower() == "ollama" and lightweight_model:
            lightweight_base_url = lightweight_base_url or "http://127.0.0.1:11434/v1"
            lightweight_api_key = lightweight_api_key or "ollama"
        return cls(
            host=settings.host,
            port=settings.port,
            internal_business_base_url=settings.internal_business_base_url,
            internal_service_token=settings.internal_service_token,
            llm_api_url=legacy_api_url,
            llm_base_url=legacy_base_url,
            llm_api_key=legacy_key,
            primary_model=primary_model,
            fallback_model=fallback_model,
            llm_timeout_seconds=settings.llm_timeout_seconds,
            max_iterations=max(1, min(settings.agent_max_iterations, 8)),
            max_history_messages=max(4, min(settings.agent_max_history_messages, 40)),
            prompt_version=settings.agent_prompt_version,
            allowed_origins=tuple(settings.allowed_origins),
            primary_provider=primary_provider,
            primary_base_url=primary_base_url,
            primary_api_key=settings.agent_primary_api_key.strip() or legacy_key,
            fallback_provider=fallback_provider,
            fallback_base_url=fallback_base_url,
            fallback_api_key=fallback_api_key,
            lightweight_provider=lightweight_provider,
            lightweight_model=lightweight_model,
            lightweight_base_url=lightweight_base_url,
            lightweight_api_key=lightweight_api_key,
        )

    def ready(self) -> bool:
        return bool(self.internal_business_base_url)

    def model_chain(self) -> tuple[AgentModelConfig, ...]:
        primary = AgentModelConfig(
            provider=self.primary_provider or "openai-compatible",
            model=self.primary_model,
            base_url=self.primary_base_url or self.llm_api_url or self.llm_base_url,
            api_key=self.primary_api_key or self.llm_api_key,
            fallback_level=0,
        )
        models = [primary]
        if self.fallback_model and self.fallback_model != self.primary_model:
            models.append(
                AgentModelConfig(
                    provider=self.fallback_provider or "openai-compatible",
                    model=self.fallback_model,
                    base_url=self.fallback_base_url,
                    api_key=self.fallback_api_key,
                    fallback_level=1,
                )
            )
        if self.lightweight_model and self.lightweight_model not in {item.model for item in models}:
            models.append(
                AgentModelConfig(
                    provider=self.lightweight_provider or "ollama",
                    model=self.lightweight_model,
                    base_url=self.lightweight_base_url,
                    api_key=self.lightweight_api_key,
                    fallback_level=2,
                )
            )
        return tuple(models)

    def model_config_for(self, model_name: str) -> AgentModelConfig:
        for config in self.model_chain():
            if config.model == model_name:
                return config
        primary = self.model_chain()[0]
        return AgentModelConfig(
            provider=primary.provider,
            model=model_name,
            base_url=primary.base_url,
            api_key=primary.api_key,
            fallback_level=primary.fallback_level,
        )

    def primary_model_configured(self) -> bool:
        return self.model_chain()[0].configured()

    def fallback_model_configured(self) -> bool:
        return any(item.fallback_level == 1 and item.configured() for item in self.model_chain())

    def lightweight_model_configured(self) -> bool:
        return any(item.fallback_level == 2 and item.configured() for item in self.model_chain())
