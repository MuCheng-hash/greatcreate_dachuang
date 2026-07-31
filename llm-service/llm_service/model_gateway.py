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
from .settings import LlmModelTarget, ModelConfig, Settings


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
        kwargs: dict[str, Any] = {
            "model": target.model,
            "api_key": target.api_key,
            "timeout": self.settings.llm_timeout_seconds,
            "max_retries": self.settings.llm_max_retries,
            "temperature": self.settings.llm_temperature,
            "stream_usage": True,
        }
        if target.base_url:
            kwargs["base_url"] = target.base_url
        if target.provider.strip().lower() == "ollama":
            kwargs["reasoning_effort"] = "none"
            kwargs["max_tokens"] = self.settings.llm_max_output_tokens
        return ChatOpenAI(**kwargs)

    @property
    def model(self) -> ChatOpenAI | None:
        """兼容旧调用方的模型访问属性。"""
        return self.chat_model

    def model_configs(self) -> tuple[ModelConfig, ...]:
        return tuple(
            ModelConfig(
                provider=target.provider,
                model=target.model,
                base_url=target.base_url,
                api_key=target.api_key,
                fallback_level=target.fallback_level,
            )
            for target, _model in self.chat_models
        )

    @staticmethod
    def model_id(target: LlmModelTarget) -> str:
        return f"{target.role}:{target.provider}:{target.model}"

    def model_catalog(self) -> list[dict[str, Any]]:
        return [
            {
                "id": self.model_id(target),
                "displayName": target.model,
                "provider": target.provider,
                "model": target.model,
                "isDefault": index == 0,
            }
            for index, (target, _model) in enumerate(self.chat_models)
        ]

    def model_configs_for(self, model_id: str | None = None) -> tuple[ModelConfig, ...]:
        configs = self.model_configs()
        if not model_id:
            return configs
        selected_index = next(
            (
                index
                for index, (target, _model) in enumerate(self.chat_models)
                if self.model_id(target) == model_id
            ),
            None,
        )
        if selected_index is None:
            raise ValueError("unknown modelId")
        return (configs[selected_index],) + tuple(
            config for index, config in enumerate(configs) if index != selected_index
        )

    def build_model(self, config: ModelConfig) -> ChatOpenAI:
        for target, model in self.chat_models:
            if target.model == config.model and target.fallback_level == config.fallback_level:
                return model
        target = LlmModelTarget(
            role="fallback" if config.fallback_level == 1 else (
                "lightweight" if config.fallback_level >= 2 else "primary"
            ),
            provider=config.provider,
            model=config.model,
            api_url=config.base_url,
            api_key=config.api_key,
            fallback_level=config.fallback_level,
        )
        model = self._build_model(target)
        self.chat_models.append((target, model))
        return model

    async def generate_json(
        self,
        prompt: str,
        trace_context: LlmTraceContext | None = None,
        validator: Callable[[dict[str, Any]], bool] | None = None,
        model_id: str | None = None,
    ) -> dict[str, Any] | None:
        result, _metadata = await self.generate_json_with_metadata(
            prompt, trace_context, validator, model_id
        )
        return result

    async def generate_json_with_metadata(
        self,
        prompt: str,
        trace_context: LlmTraceContext | None = None,
        validator: Callable[[dict[str, Any]], bool] | None = None,
        model_id: str | None = None,
    ) -> tuple[dict[str, Any] | None, dict[str, Any]]:
        context = trace_context or LlmTraceContext(feature="unclassified")
        attempts: list[dict[str, Any]] = []
        attempts_chain = self._chat_models_for(model_id)
        for index, (target, model) in enumerate(attempts_chain):
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
            self._fallback(context, target, index, error_type, attempts_chain)
        self.alerts.exhausted(context, attempts or [{"status": "not_configured"}])
        return None, {}

    async def stream_text(self, prompt: str, trace_context: LlmTraceContext | None = None, model_id: str | None = None):
        async for event_name, data in self.stream_json_events(prompt, trace_context, model_id=model_id):
            if event_name == "token":
                yield str(data.get("delta") or "")

    async def stream_json_events(
        self,
        prompt: str,
        trace_context: LlmTraceContext | None = None,
        validator: Callable[[dict[str, Any]], bool] | None = None,
        model_id: str | None = None,
    ):
        context = trace_context or LlmTraceContext(feature="unclassified-stream")
        attempts: list[dict[str, Any]] = []
        attempts_chain = self._chat_models_for(model_id)
        for index, (target, model) in enumerate(attempts_chain):
            attempt_context = self._attempt_context(context, target)
            yield "attempt", self._target_data(target)
            stream_buffer = ""
            error_type = "invalid_response"
            try:
                async for chunk in model.astream(
                    self._messages(prompt),
                    config=self._trace_config(attempt_context, target, validator),
                ):
                    text = message_text(chunk.content)
                    if text:
                        stream_buffer, delta = self._merge_stream_text(stream_buffer, text)
                        if delta:
                            yield "token", {"delta": delta, **self._target_data(target)}
                parsed = self.parse_json(stream_buffer)
                if parsed is not None and (validator is None or validator(parsed)):
                    yield "complete", {"result": parsed, **self._target_data(target)}
                    return
                error_type = "schema_validation" if parsed is not None else "json_parse"
            except Exception as exc:
                error_type = classify_llm_error(exc)
            attempts.append(self._attempt(target, error_type))
            next_target = self._next_target(index, attempts_chain)
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

    @staticmethod
    def _merge_stream_text(previous: str, incoming: str) -> tuple[str, str]:
        """Normalize providers that emit either deltas or cumulative content.

        OpenAI-compatible providers normally emit deltas, while some gateway
        adapters forward the full content seen so far.  The public stream
        contract is always a delta, so cumulative snapshots must not be sent
        twice to the browser.
        """
        if not incoming:
            return previous, ""
        if not previous:
            return incoming, incoming
        if incoming.startswith(previous):
            return incoming, incoming[len(previous):]
        if incoming == previous or previous.endswith(incoming):
            return previous, ""
        return previous + incoming, incoming

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
        self, context: LlmTraceContext, target: LlmModelTarget, index: int, error_type: str,
        chain: list[tuple[LlmModelTarget, ChatOpenAI]],
    ) -> None:
        next_target = self._next_target(index, chain)
        if next_target is not None:
            self.alerts.fallback(
                context, target.model, next_target.model, error_type,
                next_target.fallback_level,
            )

    @staticmethod
    def _next_target(index: int, chain: list[tuple[LlmModelTarget, ChatOpenAI]]) -> LlmModelTarget | None:
        next_index = index + 1
        return chain[next_index][0] if next_index < len(chain) else None

    def _chat_models_for(self, model_id: str | None) -> list[tuple[LlmModelTarget, ChatOpenAI]]:
        if not model_id:
            return list(self.chat_models)
        selected_index = next(
            (index for index, (target, _model) in enumerate(self.chat_models) if self.model_id(target) == model_id),
            None,
        )
        if selected_index is None:
            raise ValueError("unknown modelId")
        selected = self.chat_models[selected_index]
        return [selected, *(item for index, item in enumerate(self.chat_models) if index != selected_index)]

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
        """Extract the first valid JSON object from a model response.

        Providers do not all honor the same structured-output behavior. Some
        return a JSON object directly, while others wrap it in Markdown or a
        short explanatory sentence. Keep the result contract strict (a JSON
        object) but tolerate that harmless presentation difference.
        """
        if not isinstance(content, str):
            return None

        normalized = content.strip()
        if not normalized:
            return None

        decoder = json.JSONDecoder()
        for start, character in enumerate(normalized):
            if character != "{":
                continue
            try:
                parsed, _end = decoder.raw_decode(normalized, start)
            except json.JSONDecodeError:
                continue
            if isinstance(parsed, dict):
                return parsed
        return None
