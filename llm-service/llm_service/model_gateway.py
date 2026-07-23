from __future__ import annotations

import json
import logging
from typing import Any

from langchain_openai import ChatOpenAI

from .settings import ModelConfig, Settings


LOGGER = logging.getLogger("llm.model_gateway")


def message_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
        return "".join(parts)
    return str(content or "")


class ModelGateway:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._models: dict[tuple[str, int], ChatOpenAI] = {}

    @property
    def chat_model(self) -> ChatOpenAI | None:
        """Compatibility view used by structured legacy workflows."""
        for config in self.settings.model_chain():
            if not config.configured():
                continue
            try:
                return self.build_model(config)
            except Exception:
                LOGGER.exception(
                    "model_initialization_failed",
                    extra={"provider": config.provider, "model": config.model},
                )
        return None

    def model_configs(self) -> tuple[ModelConfig, ...]:
        return self.settings.model_chain()

    def build_model(self, config: ModelConfig) -> ChatOpenAI:
        cache_key = (config.model, config.fallback_level)
        if cache_key not in self._models:
            self._models[cache_key] = self._build_model(config)
        return self._models[cache_key]

    def _build_model(self, config: ModelConfig) -> ChatOpenAI:
        base_url = self._resolve_base_url(config.base_url)
        kwargs: dict[str, Any] = {
            "model": config.model,
            "api_key": config.api_key,
            "timeout": self.settings.llm_timeout_seconds,
            "max_retries": self.settings.llm_max_retries,
            "temperature": self.settings.llm_temperature,
        }
        if base_url:
            kwargs["base_url"] = base_url
        if config.provider.strip().lower() == "ollama":
            # Qwen3 enables a long thinking phase by default. Ollama's
            # OpenAI-compatible endpoint accepts reasoning_effort=none to
            # disable it for interactive fallback responses.
            kwargs["reasoning_effort"] = "none"
            kwargs["max_tokens"] = self.settings.llm_max_output_tokens
        return ChatOpenAI(
            **kwargs,
        )

    async def generate_json(self, prompt: str) -> dict[str, Any] | None:
        for config in self.settings.model_chain():
            if not config.configured():
                continue
            try:
                response = await self.build_model(config).ainvoke(
                    [
                        ("system", "Return one valid JSON object only. Do not wrap it in Markdown."),
                        ("user", prompt),
                    ]
                )
                content = message_text(response.content).strip()
                if content.startswith("```"):
                    content = content.strip("`")
                    if content.startswith("json"):
                        content = content[4:].lstrip()
                parsed = json.loads(content)
                if isinstance(parsed, dict):
                    return parsed
                raise ValueError("model response is not a JSON object")
            except (ValueError, TypeError, json.JSONDecodeError):
                LOGGER.warning(
                    "structured_model_response_invalid",
                    extra={
                        "provider": config.provider,
                        "model": config.model,
                        "fallbackLevel": config.fallback_level,
                    },
                )
            except Exception:
                LOGGER.exception(
                    "structured_model_call_failed",
                    extra={
                        "provider": config.provider,
                        "model": config.model,
                        "fallbackLevel": config.fallback_level,
                    },
                )
        return None

    def _resolve_base_url(self, configured_url: str | None) -> str | None:
        value = (configured_url or self.settings.llm_api_url or self.settings.llm_base_url).rstrip("/")
        if not value:
            return None
        suffix = "/chat/completions"
        return value[: -len(suffix)] if value.endswith(suffix) else value
