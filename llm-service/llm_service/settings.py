from __future__ import annotations

import json
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from pydantic import AliasChoices, Field, field_validator, model_validator
from pydantic_settings import (
    BaseSettings,
    PydanticBaseSettingsSource,
    SettingsConfigDict,
    TomlConfigSettingsSource,
)


CONFIG_ROOT = Path(__file__).resolve().parent.parent / "config"
VALID_ENVIRONMENTS = {"dev", "staging", "prod"}


@dataclass(frozen=True, slots=True)
class LlmModelTarget:
    role: str
    provider: str
    model: str
    api_url: str
    api_key: str
    fallback_level: int

    @property
    def configured(self) -> bool:
        return bool(self.model and self.api_url and self.api_key)

    @property
    def base_url(self) -> str:
        value = self.api_url.rstrip("/")
        suffix = "/chat/completions"
        return value[: -len(suffix)] if value.endswith(suffix) else value


@dataclass(frozen=True, slots=True)
class ModelConfig:
    """兼容 stateful Agent runtime 使用的模型配置视图。"""

    provider: str
    model: str
    base_url: str
    api_key: str
    fallback_level: int = 0

    def configured(self) -> bool:
        return bool(self.model and self.api_key)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", extra="ignore", populate_by_name=True
    )

    app_env: str = "dev"
    service_name: str = "red-culture-agent-service"
    host: str = Field("127.0.0.1", validation_alias=AliasChoices("host", "LLM_HOST"))
    port: int = Field(5050, validation_alias=AliasChoices("port", "LLM_PORT"))
    database_path: Path = Path("data/agent-state.sqlite3")
    prompt_root: Path = Path(__file__).resolve().parent.parent / "prompts"
    prompt_admin_token: str = ""
    observability_admin_token: str = ""
    llm_model_pricing: dict[str, dict[str, float]] = Field(default_factory=dict)
    llm_alert_webhook_url: str = ""
    allowed_origins: list[str] = Field(
        default_factory=list,
        validation_alias=AliasChoices("allowed_origins", "LLM_ALLOWED_ORIGINS"),
    )

    llm_api_url: str = ""
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = "qwen-plus"
    llm_provider: str = "openai-compatible"
    llm_fallback_api_url: str = ""
    llm_fallback_api_key: str = ""
    llm_fallback_model: str = ""
    llm_fallback_provider: str = "openai-compatible"
    llm_lightweight_api_url: str = ""
    llm_lightweight_api_key: str = ""
    llm_lightweight_model: str = ""
    llm_lightweight_provider: str = "ollama"
    llm_timeout_seconds: float = 20.0
    llm_max_retries: int = 1
    llm_temperature: float = 0.2
    llm_max_output_tokens: int = 512

    internal_business_base_url: str = "http://127.0.0.1:8080"
    internal_service_token: str = Field(
        "", validation_alias=AliasChoices("internal_service_token", "AGENT_INTERNAL_SERVICE_TOKEN")
    )
    business_health_path: str = "/internal/agent/tools/health"
    business_health_required: bool = False
    health_check_timeout_seconds: float = 2.0
    require_llm_model: bool = False

    agent_primary_provider: str = ""
    agent_primary_model: str = ""
    agent_primary_base_url: str = ""
    agent_primary_api_key: str = ""
    agent_fallback_provider: str = ""
    agent_fallback_model: str = ""
    agent_fallback_base_url: str = ""
    agent_fallback_api_key: str = ""
    agent_lightweight_provider: str = "ollama"
    agent_lightweight_model: str = ""
    agent_lightweight_base_url: str = ""
    agent_lightweight_api_key: str = ""
    agent_max_iterations: int = 4
    agent_max_history_messages: int = 20
    agent_prompt_version: str = "v1"

    # 兼容早期 AGENT_* 配置对象和测试构造方式，统一模型链时会与上面的
    # llm_*、agent_* 配置按优先级合并。
    primary_provider: str = ""
    primary_model: str = ""
    primary_base_url: str = ""
    primary_api_key: str = ""
    fallback_provider: str = ""
    fallback_model: str = ""
    fallback_base_url: str = ""
    fallback_api_key: str = ""

    agent_max_tool_rounds: int = 6
    agent_context_token_budget: int = 6000
    agent_recent_message_count: int = 10
    agent_summary_character_limit: int = 3000
    agent_tool_output_character_limit: int = 5000

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        environment = os.getenv("APP_ENV", "dev").strip().lower() or "dev"
        sources: list[PydanticBaseSettingsSource] = [
            init_settings,
            env_settings,
            dotenv_settings,
        ]
        override_path = os.getenv("APP_CONFIG_FILE", "").strip()
        if override_path:
            sources.append(TomlConfigSettingsSource(settings_cls, Path(override_path)))
        sources.extend((
            TomlConfigSettingsSource(settings_cls, CONFIG_ROOT / f"{environment}.toml"),
            TomlConfigSettingsSource(settings_cls, CONFIG_ROOT / "base.toml"),
            file_secret_settings,
        ))
        return tuple(sources)

    @field_validator("allowed_origins", mode="before")
    @classmethod
    def parse_origins(cls, value: object) -> object:
        if isinstance(value, str):
            return [item.strip() for item in value.split(",") if item.strip()]
        return value

    @field_validator("llm_model_pricing", mode="before")
    @classmethod
    def parse_model_pricing(cls, value: object) -> object:
        if isinstance(value, str):
            return json.loads(value) if value.strip() else {}
        return value

    @field_validator("app_env")
    @classmethod
    def validate_environment(cls, value: str) -> str:
        normalized = value.strip().lower()
        if normalized not in VALID_ENVIRONMENTS:
            allowed = ", ".join(sorted(VALID_ENVIRONMENTS))
            raise ValueError(f"APP_ENV must be one of: {allowed}")
        return normalized

    @field_validator("internal_business_base_url")
    @classmethod
    def normalize_business_url(cls, value: str) -> str:
        return value.strip().rstrip("/")

    @field_validator("business_health_path")
    @classmethod
    def normalize_health_path(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("BUSINESS_HEALTH_PATH cannot be empty")
        return normalized if normalized.startswith("/") else f"/{normalized}"

    @model_validator(mode="after")
    def validate_deployment_profile(self) -> "Settings":
        if self.app_env != "prod":
            return self
        if "*" in self.allowed_origins:
            raise ValueError("wildcard CORS is not allowed when APP_ENV=prod")
        if str(self.database_path).strip() == ":memory:":
            raise ValueError("an in-memory database is not allowed when APP_ENV=prod")
        if not self.prompt_admin_token or not self.observability_admin_token:
            raise ValueError("admin tokens are required when APP_ENV=prod")
        if self.require_llm_model and not self.model_configured:
            raise ValueError("at least one LLM model must be configured when REQUIRE_LLM_MODEL=true")
        return self

    @property
    def observability_token(self) -> str:
        return self.observability_admin_token or self.prompt_admin_token

    @property
    def model_configured(self) -> bool:
        return any(target.configured for target in self.model_chain)

    @property
    def model_chain(self) -> tuple[LlmModelTarget, ...]:
        primary_provider = (
            self.agent_primary_provider.strip()
            or self.primary_provider.strip()
            or self.llm_provider.strip()
            or "openai-compatible"
        )
        primary_model = (
            self.agent_primary_model.strip()
            or self.primary_model.strip()
            or self.llm_model.strip()
        )
        primary_api_url = (
            self.agent_primary_base_url.strip()
            or self.primary_base_url.strip()
            or self.llm_api_url.strip()
            or self.llm_base_url.strip()
        )
        primary_api_key = (
            self.agent_primary_api_key.strip()
            or self.primary_api_key.strip()
            or self.llm_api_key.strip()
        )
        if primary_provider.lower() == "bailian" and not primary_api_url:
            primary_api_url = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        if primary_provider.lower() == "ollama":
            primary_api_url = primary_api_url or "http://127.0.0.1:11434/v1"
            primary_api_key = primary_api_key or "ollama"

        fallback_provider = (
            self.agent_fallback_provider.strip()
            or self.fallback_provider.strip()
            or self.llm_fallback_provider.strip()
            or "openai-compatible"
        )
        fallback_model = (
            self.agent_fallback_model.strip()
            or self.fallback_model.strip()
            or self.llm_fallback_model.strip()
        )
        fallback_api_url = (
            self.agent_fallback_base_url.strip()
            or self.fallback_base_url.strip()
            or self.llm_fallback_api_url.strip()
        )
        fallback_api_key = (
            self.agent_fallback_api_key.strip()
            or self.fallback_api_key.strip()
            or self.llm_fallback_api_key.strip()
        )
        if fallback_provider.lower() == "ollama":
            fallback_api_url = fallback_api_url or "http://127.0.0.1:11434/v1"
            fallback_api_key = fallback_api_key or "ollama"

        lightweight_provider = (
            self.agent_lightweight_provider.strip()
            or self.llm_lightweight_provider.strip()
            or "ollama"
        )
        lightweight_model = (
            self.agent_lightweight_model.strip() or self.llm_lightweight_model.strip()
        )
        lightweight_api_url = (
            self.agent_lightweight_base_url.strip()
            or self.llm_lightweight_api_url.strip()
        )
        lightweight_api_key = (
            self.agent_lightweight_api_key.strip()
            or self.llm_lightweight_api_key.strip()
        )
        if lightweight_provider.lower() == "ollama" and lightweight_model:
            lightweight_api_url = lightweight_api_url or "http://127.0.0.1:11434/v1"
            lightweight_api_key = lightweight_api_key or "ollama"

        targets = (
            LlmModelTarget(
                "primary", primary_provider, primary_model,
                primary_api_url, primary_api_key, 0,
            ),
            LlmModelTarget(
                "fallback", fallback_provider, fallback_model,
                fallback_api_url, fallback_api_key, 1,
            ),
            LlmModelTarget(
                "lightweight", lightweight_provider, lightweight_model,
                lightweight_api_url, lightweight_api_key,
                2,
            ),
        )
        result: list[LlmModelTarget] = []
        seen: set[tuple[str, str]] = set()
        for target in targets:
            identity = (target.base_url, target.model)
            if target.configured and identity not in seen:
                result.append(target)
                seen.add(identity)
        return tuple(result)

    @property
    def openai_base_url(self) -> str:
        value = (self.llm_api_url or self.llm_base_url).rstrip("/")
        suffix = "/chat/completions"
        return value[: -len(suffix)] if value.endswith(suffix) else value

    @property
    def prompt_path(self) -> Path:
        return self.prompt_root / "agent" / self.agent_prompt_version / "system.md"

    def primary_model_configured(self) -> bool:
        return any(target.fallback_level == 0 and target.configured for target in self.model_chain)

    def fallback_model_configured(self) -> bool:
        return any(target.fallback_level == 1 and target.configured for target in self.model_chain)

    def lightweight_model_configured(self) -> bool:
        return any(target.fallback_level == 2 and target.configured for target in self.model_chain)


def load_settings() -> Settings:
    override_path = os.getenv("APP_CONFIG_FILE", "").strip()
    if override_path and not Path(override_path).is_file():
        raise ValueError(f"APP_CONFIG_FILE does not exist: {override_path}")
    return Settings()


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return load_settings()
