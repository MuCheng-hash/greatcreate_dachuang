from __future__ import annotations

import json
from typing import Any

from langchain_openai import ChatOpenAI

from .settings import Settings


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
        self.chat_model = self._build_model() if settings.model_configured else None

    def _build_model(self) -> ChatOpenAI:
        return ChatOpenAI(
            model=self.settings.llm_model,
            api_key=self.settings.llm_api_key,
            base_url=self.settings.openai_base_url,
            timeout=self.settings.llm_timeout_seconds,
            max_retries=self.settings.llm_max_retries,
            temperature=self.settings.llm_temperature,
        )

    async def generate_json(self, prompt: str) -> dict[str, Any] | None:
        if self.chat_model is None:
            return None
        try:
            response = await self.chat_model.ainvoke(
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
            return parsed if isinstance(parsed, dict) else None
        except (ValueError, TypeError, json.JSONDecodeError):
            return None
        except Exception:
            return None
