from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from pydantic import AliasChoices, Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


@dataclass(frozen=True)
class ModelConfig:
    """One model in the unified primary/fallback chain."""

    provider: str
    model: str
    base_url: str
    api_key: str
    fallback_level: int = 0

    def configured(self) -> bool:
        # Ollama also accepts a placeholder key through the OpenAI-compatible
        # adapter, so a model and key are the only required values here.
        return bool(self.model and self.api_key)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        extra="ignore",
        populate_by_name=True,
    )

    service_name: str = "red-culture-agent-service"
    database_path: Path = Path("data/agent-state.sqlite3")
    allowed_origins: list[str] = Field(default_factory=list)
    internal_service_token: str = Field(
        default="red-culture-agent-development-token-change-me",
        validation_alias=AliasChoices("INTERNAL_SERVICE_TOKEN", "AGENT_INTERNAL_SERVICE_TOKEN"),
    )
    prompt_version: str = "v1"

    llm_api_url: str = ""
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = "qwen-plus"
    llm_timeout_seconds: float = 20.0
    llm_max_retries: int = 1
    llm_temperature: float = 0.2
    llm_max_output_tokens: int = 512

    primary_provider: str = Field(
        default="openai-compatible",
        validation_alias=AliasChoices("AGENT_PRIMARY_PROVIDER"),
    )
    primary_model: str = Field(
        default="qwen-plus",
        validation_alias=AliasChoices("AGENT_PRIMARY_MODEL", "LLM_MODEL"),
    )
    primary_base_url: str = Field(
        default="",
        validation_alias=AliasChoices("AGENT_PRIMARY_BASE_URL", "LLM_API_URL", "LLM_BASE_URL"),
    )
    primary_api_key: str = Field(
        default="",
        validation_alias=AliasChoices("AGENT_PRIMARY_API_KEY", "LLM_API_KEY"),
    )
    fallback_provider: str = Field(
        default="",
        validation_alias=AliasChoices("AGENT_FALLBACK_PROVIDER"),
    )
    fallback_model: str = Field(
        default="",
        validation_alias=AliasChoices("AGENT_FALLBACK_MODEL"),
    )
    fallback_base_url: str = Field(
        default="",
        validation_alias=AliasChoices("AGENT_FALLBACK_BASE_URL"),
    )
    fallback_api_key: str = Field(
        default="",
        validation_alias=AliasChoices("AGENT_FALLBACK_API_KEY"),
    )

    agent_max_tool_rounds: int = 6
    agent_context_token_budget: int = 6000
    agent_recent_message_count: int = 10
    agent_summary_character_limit: int = 3000
    agent_tool_output_character_limit: int = 5000

    @field_validator("allowed_origins", mode="before")
    @classmethod
    def parse_origins(cls, value: object) -> object:
        if isinstance(value, str):
            return [item.strip() for item in value.split(",") if item.strip()]
        return value

    @model_validator(mode="after")
    def normalize_model_chain(self) -> "Settings":
        provider = self.primary_provider.strip().lower()
        if provider == "bailian" and not self.primary_base_url:
            self.primary_base_url = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        if provider == "ollama":
            self.primary_base_url = self.primary_base_url or "http://127.0.0.1:11434/v1"
            self.primary_api_key = self.primary_api_key or "ollama"

        fallback_provider = self.fallback_provider.strip().lower()
        if fallback_provider == "ollama":
            self.fallback_base_url = self.fallback_base_url or "http://127.0.0.1:11434/v1"
            self.fallback_api_key = self.fallback_api_key or "ollama"
        return self

    @property
    def model_configured(self) -> bool:
        return self.primary_model_configured()

    @property
    def openai_base_url(self) -> str:
        value = self.llm_api_url.rstrip("/")
        suffix = "/chat/completions"
        return value[: -len(suffix)] if value.endswith(suffix) else value

    @property
    def prompt_path(self) -> Path:
        return Path(__file__).resolve().parent.parent / "prompts" / "agent" / self.prompt_version / "system.md"

    def model_chain(self) -> tuple[ModelConfig, ...]:
        primary = ModelConfig(
            provider=self.primary_provider or "openai-compatible",
            model=self.primary_model,
            base_url=self.primary_base_url or self.llm_api_url or self.llm_base_url,
            api_key=self.primary_api_key or self.llm_api_key,
            fallback_level=0,
        )
        models = [primary]
        if self.fallback_model and self.fallback_model != self.primary_model:
            models.append(
                ModelConfig(
                    provider=self.fallback_provider or "openai-compatible",
                    model=self.fallback_model,
                    base_url=self.fallback_base_url,
                    api_key=self.fallback_api_key,
                    fallback_level=1,
                )
            )
        return tuple(models)

    def primary_model_configured(self) -> bool:
        return self.model_chain()[0].configured()

    def fallback_model_configured(self) -> bool:
        chain = self.model_chain()
        return len(chain) > 1 and chain[1].configured()


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
