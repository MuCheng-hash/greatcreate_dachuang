from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    service_name: str = "red-culture-agent-service"
    database_path: Path = Path("data/agent-state.sqlite3")
    allowed_origins: list[str] = Field(default_factory=list)

    llm_api_url: str = ""
    llm_api_key: str = ""
    llm_model: str = "qwen-plus"
    llm_timeout_seconds: float = 20.0
    llm_max_retries: int = 1
    llm_temperature: float = 0.2

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

    @property
    def model_configured(self) -> bool:
        return bool(self.llm_api_key and self.llm_api_url and self.llm_model)

    @property
    def openai_base_url(self) -> str:
        value = self.llm_api_url.rstrip("/")
        suffix = "/chat/completions"
        return value[: -len(suffix)] if value.endswith(suffix) else value


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
