from __future__ import annotations

import json
from dataclasses import replace
from typing import Any, Callable

from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from .observability import (
    FallbackAlertManager,
    LlmObservability,
    LlmTraceContext,
    classify_llm_error,
)
from .settings import LlmModelTarget, ModelConfig, Settings


def message_text(content: Any) -> str:
    """提取模型消息中可展示的文本，兼容字符串和分段内容两种提供方格式。"""
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
    """统一模型链调用、结构化输出校验、观测与降级通知。

    提示词内容由调用方提供且不在此处持久化；网关只按配置顺序尝试模型。失败时
    记录可观测的原因并切换后备模型，所有候选耗尽时返回空结果而不合成回答。
    """
    def __init__(
        self,
        settings: Settings,
        observability: LlmObservability | None = None,
        alerts: FallbackAlertManager | None = None,
    ):
        """依据运行配置构建模型链，并注入可选的追踪与降级告警组件。"""
        self.settings = settings
        self.observability = observability
        self.alerts = alerts or FallbackAlertManager(settings.llm_alert_webhook_url)
        self.chat_models = [
            # 模型链顺序即降级顺序，不能按 provider 名称重新排序。
            (target, self._build_model(target)) for target in settings.model_chain
        ]
        self.chat_model = self.chat_models[0][1] if self.chat_models else None

    def _build_model(self, target: LlmModelTarget) -> ChatOpenAI:
        """将单个可信模型配置转换为 LangChain 客户端，不记录其 API 密钥。"""
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
        """导出当前模型链的不可变配置视图，供 Agent 选择和展示使用。"""
        return tuple(
            ModelConfig(
                provider=target.provider,
                model=target.model,
                base_url=target.base_url,
                api_key=target.api_key,
                fallback_level=target.fallback_level,
                supports_json_object=target.supports_json_object,
                supports_json_schema=target.supports_json_schema,
                supports_structured_tool_output=target.supports_structured_tool_output,
            )
            for target, _model in self.chat_models
        )

    @staticmethod
    def model_id(target: LlmModelTarget) -> str:
        """生成前端选择和调用追踪共用的稳定模型标识。"""
        return f"{target.role}:{target.provider}:{target.model}"

    def model_catalog(self) -> list[dict[str, Any]]:
        """返回可公开展示的模型能力目录，不包含地址或认证信息。"""
        return [
            {
                "id": self.model_id(target),
                "displayName": target.model,
                "provider": target.provider,
                "model": target.model,
                "isDefault": index == 0,
                "supportsJsonObject": target.supports_json_object,
                "supportsJsonSchema": target.supports_json_schema,
                "supportsStructuredToolOutput": target.supports_structured_tool_output,
            }
            for index, (target, _model) in enumerate(self.chat_models)
        ]

    def model_configs_for(self, model_id: str | None = None) -> tuple[ModelConfig, ...]:
        """返回选定模型优先、其余模型按原顺序降级的尝试链。"""
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
            raise ValueError("未知的 modelId")
        return (configs[selected_index],) + tuple(
            config for index, config in enumerate(configs) if index != selected_index
        )

    def build_model(self, config: ModelConfig) -> ChatOpenAI:
        """复用已构建模型，或为运行时配置延迟创建客户端。

        仅模型身份和降级层级相同才复用，避免把不同后备层的观测语义混在一起。
        """
        for target, model in self.chat_models:
            if target.model == config.model and target.fallback_level == config.fallback_level:
                # 复用时必须同时匹配降级层级，防止相同模型承担错误的观测角色。
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
            supports_json_object=config.supports_json_object,
            supports_json_schema=config.supports_json_schema,
            supports_structured_tool_output=config.supports_structured_tool_output,
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
        response_schema: type[BaseModel] | None = None,
    ) -> dict[str, Any] | None:
        """生成并校验完整 JSON，忽略用于诊断的模型元数据。"""
        result, _metadata = await self.generate_json_with_metadata(
            prompt, trace_context, validator, model_id, response_schema
        )
        return result

    async def generate_json_with_metadata(
        self,
        prompt: str,
        trace_context: LlmTraceContext | None = None,
        validator: Callable[[dict[str, Any]], bool] | None = None,
        model_id: str | None = None,
        response_schema: type[BaseModel] | None = None,
    ) -> tuple[dict[str, Any] | None, dict[str, Any]]:
        """按模型链请求完整结构化 JSON，并返回成功模型的公开元数据。

        ``validator`` 负责业务层语义校验；解析失败、协议不支持和调用异常均触发
        后备模型。全部失败时告警并返回 ``(None, {})``，由调用方选择业务回退。
        """
        context = trace_context or LlmTraceContext(feature="unclassified")
        attempts: list[dict[str, Any]] = []
        attempts_chain = self._chat_models_for(model_id)
        for index, (target, model) in enumerate(attempts_chain):
            # 每次尝试使用独立追踪元数据，避免后备模型覆盖首选模型的失败证据。
            attempt_context = self._attempt_context(context, target)
            config = self._trace_config(attempt_context, target, validator)
            error_type = "invalid_response"
            try:
                if not target.supports_json_object and not (
                    response_schema is not None and target.supports_json_schema
                ):
                    # 不具备协议能力的模型不应被尝试调用，直接转到下一后备项。
                    error_type = "structured_output_unsupported"
                    raise StructuredOutputUnsupported(target.model)
                if response_schema is not None and target.supports_json_schema:
                    response = await model.with_structured_output(response_schema).ainvoke(
                        self._messages(prompt), config=config
                    )
                    parsed = self._structured_response_dict(response)
                else:
                    response = await self._json_mode_model(model).ainvoke(
                        self._messages(prompt), config=config
                    )
                    parsed = self.parse_json(message_text(response.content))
                if parsed is not None and (validator is None or validator(parsed)):
                    # 业务校验通过后才把 JSON 交给上游，避免结构正确但语义错误的数据流入状态机。
                    return parsed, self._target_data(target)
                error_type = "schema_validation" if parsed is not None else "json_mode_protocol_violation"
            except StructuredOutputUnsupported:
                # 能力不匹配不是服务异常，仍记录一次尝试以便观察模型链配置问题。
                pass
            except Exception as exc:
                error_type = classify_llm_error(exc)
            attempts.append(self._attempt(target, error_type))
            # 后备切换统一由本方法完成，调用方只需处理全部耗尽后的业务降级。
            await self._fallback(context, target, index, error_type, attempts_chain)
        await self.alerts.exhausted(context, attempts or [{"status": "not_configured"}])
        return None, {}

    async def stream_text(self, prompt: str, trace_context: LlmTraceContext | None = None, model_id: str | None = None):
        """将结构化流中的文本增量转为简单文本流，忽略尝试和完成控制事件。"""
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
        """流式生成 JSON，同时暴露尝试、回退、文本增量和完成状态。

        对外 ``token`` 始终是增量，防止累计式提供方重复推送。解析或校验失败后会
        发送 ``fallback`` 并切换模型；耗尽后发送 ``exhausted``，不会输出伪造 JSON。
        """
        context = trace_context or LlmTraceContext(feature="unclassified-stream")
        attempts: list[dict[str, Any]] = []
        attempts_chain = self._chat_models_for(model_id)
        for index, (target, model) in enumerate(attempts_chain):
            attempt_context = self._attempt_context(context, target)
            yield "attempt", self._target_data(target)
            stream_buffer = ""
            error_type = "invalid_response"
            try:
                if not target.supports_json_object:
                    error_type = "structured_output_unsupported"
                    raise StructuredOutputUnsupported(target.model)
                async for chunk in self._json_mode_model(model).astream(
                    self._messages(prompt),
                    config=self._trace_config(attempt_context, target, validator),
                ):
                    text = message_text(chunk.content)
                    if text:
                        # 提供方可能给出累计快照或增量，先规范化再将真正增量交给 SSE。
                        stream_buffer, delta = self._merge_stream_text(stream_buffer, text)
                        if delta:
                            yield "token", {"delta": delta, **self._target_data(target)}
                parsed = self.parse_json(stream_buffer)
                if parsed is not None and (validator is None or validator(parsed)):
                    yield "complete", {"result": parsed, **self._target_data(target)}
                    return
                error_type = "schema_validation" if parsed is not None else "json_mode_protocol_violation"
            except StructuredOutputUnsupported:
                pass
            except Exception as exc:
                error_type = classify_llm_error(exc)
            attempts.append(self._attempt(target, error_type))
            next_target = self._next_target(index, attempts_chain)
            if next_target is not None:
                # 流中已发出的 token 不会被后备模型覆盖；上层按事件记录尝试边界。
                await self.alerts.fallback(
                    context, target.model, next_target.model, error_type,
                    next_target.fallback_level,
                )
                yield "fallback", {
                    "failedModel": target.model,
                    "nextModel": next_target.model,
                    "errorType": error_type,
                    "fallbackLevel": next_target.fallback_level,
                }
        await self.alerts.exhausted(context, attempts or [{"status": "not_configured"}])
        yield "exhausted", {"attempts": attempts}

    @staticmethod
    def _merge_stream_text(previous: str, incoming: str) -> tuple[str, str]:
        """规范化同时可能返回增量内容或累计内容的模型提供方。

        OpenAI 兼容提供方通常返回增量，而某些网关适配器会转发截至当前的完整内容。
        对外流协议始终只输出增量，因此累计快照不能被重复发送给浏览器。
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
        """在观测组件可用时创建本次模型调用的回调配置。"""
        if self.observability is None or trace_context is None:
            return None
        callback = self.observability.callback(
            trace_context, target.provider, target.model, validator
        )
        return {"callbacks": [callback]}

    def _attempt_context(
        self, context: LlmTraceContext, target: LlmModelTarget
    ) -> LlmTraceContext:
        """为单次尝试附加模型角色和降级层级，保留原调用链追踪信息。"""
        return replace(context, metadata={
            **context.metadata,
            "modelRole": target.role,
            "fallbackLevel": target.fallback_level,
        })

    async def _fallback(
        self, context: LlmTraceContext, target: LlmModelTarget, index: int, error_type: str,
        chain: list[tuple[LlmModelTarget, ChatOpenAI]],
    ) -> None:
        """在存在后备模型时发送一次降级告警；告警失败由告警组件自行隔离。"""
        next_target = self._next_target(index, chain)
        if next_target is not None:
            await self.alerts.fallback(
                context, target.model, next_target.model, error_type,
                next_target.fallback_level,
            )

    @staticmethod
    def _next_target(index: int, chain: list[tuple[LlmModelTarget, ChatOpenAI]]) -> LlmModelTarget | None:
        next_index = index + 1
        return chain[next_index][0] if next_index < len(chain) else None

    def _chat_models_for(self, model_id: str | None) -> list[tuple[LlmModelTarget, ChatOpenAI]]:
        """选择请求指定的首选模型，并保留其他已配置模型作为后备链。"""
        if not model_id:
            return list(self.chat_models)
        selected_index = next(
            (index for index, (target, _model) in enumerate(self.chat_models) if self.model_id(target) == model_id),
            None,
        )
        if selected_index is None:
            raise ValueError("未知的 modelId")
        selected = self.chat_models[selected_index]
        # 用户选择只调整首选项，不删除其余后备模型，保持故障时仍可完成请求。
        return [selected, *(item for index, item in enumerate(self.chat_models) if index != selected_index)]

    @staticmethod
    def _messages(prompt: str) -> list[tuple[str, str]]:
        """构造固定 JSON 协议约束和调用方提示词，系统约束始终位于用户内容之前。"""
        return [
            ("system", "Return exactly one JSON object. Do not use Markdown, prose, duplicate keys, or fields outside the requested schema."),
            ("user", prompt),
        ]

    @staticmethod
    def _target_data(target: LlmModelTarget) -> dict[str, Any]:
        """导出可观测的模型能力元数据；刻意不包含 API 密钥和服务地址。"""
        return {
            "provider": target.provider,
            "model": target.model,
            "modelRole": target.role,
            "fallbackLevel": target.fallback_level,
            "supportsJsonObject": target.supports_json_object,
            "supportsJsonSchema": target.supports_json_schema,
            "supportsStructuredToolOutput": target.supports_structured_tool_output,
        }

    @staticmethod
    def _attempt(target: LlmModelTarget, error_type: str) -> dict[str, Any]:
        """构造单次失败尝试的无敏感审计摘要。"""
        return {
            **ModelGateway._target_data(target),
            "status": "failed",
            "errorType": error_type,
        }

    @staticmethod
    def parse_json(content: str) -> dict[str, Any] | None:
        """解码完整 JSON 对象，不尝试进行文本修复。

        保守失败可让调用方触发模型降级，避免从不完整或夹杂自然语言的输出中猜测
        结构化字段，进而把错误数据写入后续业务流程。
        """
        if not isinstance(content, str):
            return None

        normalized = content.strip()
        if not normalized:
            return None

        try:
            parsed = json.loads(normalized)
        except (TypeError, ValueError, json.JSONDecodeError):
            return None
        return parsed if isinstance(parsed, dict) else None

    @staticmethod
    def _json_mode_model(model: ChatOpenAI) -> Any:
        """绑定 JSON 对象响应约束；是否支持由调用处按模型能力预先判断。"""
        return model.bind(response_format={"type": "json_object"})

    @staticmethod
    def _structured_response_dict(response: Any) -> dict[str, Any] | None:
        """将 Pydantic 结构化响应统一为字典，不接受其他对象猜测转换。"""
        if isinstance(response, BaseModel):
            return response.model_dump(by_alias=True)
        return response if isinstance(response, dict) else None


class StructuredOutputUnsupported(RuntimeError):
    """当前模型缺少请求所需的 JSON 模式或 JSON Schema 能力。"""
    pass
