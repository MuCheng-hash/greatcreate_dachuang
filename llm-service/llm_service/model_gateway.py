from __future__ import annotations

import json
from dataclasses import replace
from typing import Any, Callable

from langchain_openai import ChatOpenAI

from .observability import (
    FallbackAlertManager,
    LlmObservability,
    LlmTraceContext,
    classify_llm_error,
)
from .settings import LlmModelTarget, Settings


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
    def __init__(
        self,
        settings: Settings,
        observability: LlmObservability | None = None,
        alerts: FallbackAlertManager | None = None,
    ):
        self.settings = settings
        self.observability = observability
        self.alerts = alerts or FallbackAlertManager(settings.llm_alert_webhook_url)
        self.chat_models = [
            (target, self._build_model(target)) for target in settings.model_chain
        ]
        self.chat_model = self.chat_models[0][1] if self.chat_models else None

    def _build_model(self, target: LlmModelTarget) -> ChatOpenAI:
        return ChatOpenAI(
            model=target.model,
            api_key=target.api_key,
            base_url=target.base_url,
            timeout=self.settings.llm_timeout_seconds,
            max_retries=self.settings.llm_max_retries,
            temperature=self.settings.llm_temperature,
            stream_usage=True,
        )

    async def generate_json(
        self,
        prompt: str,
        trace_context: LlmTraceContext | None = None,
        validator: Callable[[dict[str, Any]], bool] | None = None,
    ) -> dict[str, Any] | None:
        result, _metadata = await self.generate_json_with_metadata(
            prompt, trace_context, validator
        )
        return result

    async def generate_json_with_metadata(
        self,
        prompt: str,
        trace_context: LlmTraceContext | None = None,
        validator: Callable[[dict[str, Any]], bool] | None = None,
    ) -> tuple[dict[str, Any] | None, dict[str, Any]]:
        context = trace_context or LlmTraceContext(feature="unclassified")
        attempts: list[dict[str, Any]] = []
        for index, (target, model) in enumerate(self.chat_models):
            attempt_context = self._attempt_context(context, target)
            config = self._trace_config(attempt_context, target, validator)
            error_type = "invalid_response"
            try:
                response = await model.ainvoke(self._messages(prompt), config=config)
                parsed = self.parse_json(message_text(response.content))
                if parsed is not None and (validator is None or validator(parsed)):
                    return parsed, self._target_data(target)
                error_type = "schema_validation" if parsed is not None else "json_parse"
            except Exception as exc:
                error_type = classify_llm_error(exc)
            attempts.append(self._attempt(target, error_type))
            self._fallback(context, target, index, error_type)
        self.alerts.exhausted(context, attempts or [{"status": "not_configured"}])
        return None, {}

    async def stream_text(self, prompt: str, trace_context: LlmTraceContext | None = None):
        async for event_name, data in self.stream_json_events(prompt, trace_context):
            if event_name == "token":
                yield str(data.get("delta") or "")

    async def stream_json_events(
        self,
        prompt: str,
        trace_context: LlmTraceContext | None = None,
        validator: Callable[[dict[str, Any]], bool] | None = None,
    ):
        context = trace_context or LlmTraceContext(feature="unclassified-stream")
        attempts: list[dict[str, Any]] = []
        for index, (target, model) in enumerate(self.chat_models):
            attempt_context = self._attempt_context(context, target)
            yield "attempt", self._target_data(target)
            parts: list[str] = []
            error_type = "invalid_response"
            try:
                async for chunk in model.astream(
                    self._messages(prompt),
                    config=self._trace_config(attempt_context, target, validator),
                ):
                    text = message_text(chunk.content)
                    if text:
                        parts.append(text)
                        yield "token", {"delta": text, **self._target_data(target)}
                parsed = self.parse_json("".join(parts))
                if parsed is not None and (validator is None or validator(parsed)):
                    yield "complete", {"result": parsed, **self._target_data(target)}
                    return
                error_type = "schema_validation" if parsed is not None else "json_parse"
            except Exception as exc:
                error_type = classify_llm_error(exc)
            attempts.append(self._attempt(target, error_type))
            next_target = self._next_target(index)
            if next_target is not None:
                self.alerts.fallback(
                    context, target.model, next_target.model, error_type,
                    next_target.fallback_level,
                )
                yield "fallback", {
                    "failedModel": target.model,
                    "nextModel": next_target.model,
                    "errorType": error_type,
                    "fallbackLevel": next_target.fallback_level,
                }
        self.alerts.exhausted(context, attempts or [{"status": "not_configured"}])
        yield "exhausted", {"attempts": attempts}

    def _trace_config(
        self,
        trace_context: LlmTraceContext | None,
        target: LlmModelTarget,
        validator: Callable[[dict[str, Any]], bool] | None = None,
    ) -> dict[str, Any] | None:
        if self.observability is None or trace_context is None:
            return None
        callback = self.observability.callback(
            trace_context, target.provider, target.model, validator
        )
        return {"callbacks": [callback]}

    def _attempt_context(
        self, context: LlmTraceContext, target: LlmModelTarget
    ) -> LlmTraceContext:
        return replace(context, metadata={
            **context.metadata,
            "modelRole": target.role,
            "fallbackLevel": target.fallback_level,
        })

    def _fallback(
        self, context: LlmTraceContext, target: LlmModelTarget, index: int, error_type: str
    ) -> None:
        next_target = self._next_target(index)
        if next_target is not None:
            self.alerts.fallback(
                context, target.model, next_target.model, error_type,
                next_target.fallback_level,
            )

    def _next_target(self, index: int) -> LlmModelTarget | None:
        next_index = index + 1
        return self.chat_models[next_index][0] if next_index < len(self.chat_models) else None

    @staticmethod
    def _messages(prompt: str) -> list[tuple[str, str]]:
        return [
            ("system", "Return one valid JSON object only. Do not wrap it in Markdown."),
            ("user", prompt),
        ]

    @staticmethod
    def _target_data(target: LlmModelTarget) -> dict[str, Any]:
        return {
            "provider": target.provider,
            "model": target.model,
            "modelRole": target.role,
            "fallbackLevel": target.fallback_level,
        }

    @staticmethod
    def _attempt(target: LlmModelTarget, error_type: str) -> dict[str, Any]:
        return {
            **ModelGateway._target_data(target),
            "status": "failed",
            "errorType": error_type,
        }

    @staticmethod
    def parse_json(content: str) -> dict[str, Any] | None:
        normalized = content.strip()
        if normalized.startswith("```"):
            normalized = normalized.strip("`")
            if normalized.startswith("json"):
                normalized = normalized[4:].lstrip()
        try:
            parsed = json.loads(normalized)
        except (ValueError, TypeError, json.JSONDecodeError):
            return None
        return parsed if isinstance(parsed, dict) else None
