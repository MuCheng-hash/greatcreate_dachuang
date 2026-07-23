from __future__ import annotations

import os
from dataclasses import dataclass


def _text(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def _float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


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

    @classmethod
    def from_env(cls) -> "AgentSettings":
        origins = tuple(
            origin.strip()
            for origin in _text(
                "LLM_ALLOWED_ORIGINS",
                "http://localhost:8080,http://127.0.0.1:8080",
            ).split(",")
            if origin.strip()
        )
        legacy_api_url = _text("LLM_API_URL")
        legacy_base_url = _text("LLM_BASE_URL")
        legacy_key = _text("LLM_API_KEY")
        primary_provider = _text("AGENT_PRIMARY_PROVIDER", "openai-compatible")
        primary_model = _text(
            "AGENT_PRIMARY_MODEL",
            _text("LLM_MODEL", "qwen-plus"),
        )
        primary_base_url = _text(
            "AGENT_PRIMARY_BASE_URL",
            legacy_api_url or legacy_base_url,
        )
        if not primary_base_url and primary_provider.lower() == "bailian":
            primary_base_url = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        fallback_provider = _text("AGENT_FALLBACK_PROVIDER")
        fallback_model = _text("AGENT_FALLBACK_MODEL")
        fallback_base_url = _text("AGENT_FALLBACK_BASE_URL")
        fallback_api_key = _text("AGENT_FALLBACK_API_KEY")
        if fallback_provider.lower() == "ollama":
            fallback_base_url = fallback_base_url or "http://127.0.0.1:11434/v1"
            fallback_api_key = fallback_api_key or "ollama"
        return cls(
            host=_text("LLM_HOST", "127.0.0.1"),
            port=_int("LLM_PORT", 5050),
            internal_business_base_url=_text(
                "INTERNAL_BUSINESS_BASE_URL",
                "http://127.0.0.1:8080",
            ).rstrip("/"),
            internal_service_token=_text("AGENT_INTERNAL_SERVICE_TOKEN")
            or "red-culture-agent-development-token-change-me",
            llm_api_url=legacy_api_url,
            llm_base_url=legacy_base_url,
            llm_api_key=legacy_key,
            primary_model=primary_model,
            fallback_model=fallback_model,
            llm_timeout_seconds=_float("LLM_TIMEOUT_SECONDS", 20.0),
            max_iterations=max(1, min(_int("AGENT_MAX_ITERATIONS", 4), 8)),
            max_history_messages=max(4, min(_int("AGENT_MAX_HISTORY_MESSAGES", 20), 40)),
            prompt_version=_text("AGENT_PROMPT_VERSION", "v1"),
            allowed_origins=origins,
            primary_provider=primary_provider,
            primary_base_url=primary_base_url,
            primary_api_key=_text("AGENT_PRIMARY_API_KEY", legacy_key),
            fallback_provider=fallback_provider,
            fallback_base_url=fallback_base_url,
            fallback_api_key=fallback_api_key,
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
        chain = self.model_chain()
        return len(chain) > 1 and chain[1].configured()
