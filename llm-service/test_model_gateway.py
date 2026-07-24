from __future__ import annotations

import unittest
from unittest.mock import Mock, patch

from llm_service.model_gateway import ModelGateway
from llm_service.observability import FallbackAlertManager, LlmTraceContext
from llm_service.settings import Settings


class FakeResponse:
    def __init__(self, content: str):
        self.content = content


class FakeModel:
    def __init__(self, content: str = "", error: Exception | None = None):
        self.content = content
        self.error = error

    async def ainvoke(self, _messages, config=None):
        if self.error:
            raise self.error
        return FakeResponse(self.content)

    async def astream(self, _messages, config=None):
        if self.error:
            raise self.error
        midpoint = max(1, len(self.content) // 2)
        yield FakeResponse(self.content[:midpoint])
        yield FakeResponse(self.content[midpoint:])


def router_settings() -> Settings:
    return Settings(
        _env_file=None,
        llm_api_url="https://primary.example/v1",
        llm_api_key="primary-key",
        llm_model="gpt-4",
        llm_fallback_api_url="https://fallback.example/v1",
        llm_fallback_api_key="fallback-key",
        llm_fallback_model="gpt-3.5",
        llm_lightweight_api_url="http://127.0.0.1:11434/v1",
        llm_lightweight_model="qwen3:8b",
    )


class ModelGatewayFallbackTest(unittest.IsolatedAsyncioTestCase):
    async def test_uses_primary_then_fallback_then_lightweight(self) -> None:
        models = [
            FakeModel(error=TimeoutError("primary timed out")),
            FakeModel("not-json"),
            FakeModel('{"answer":"local model"}'),
        ]
        alerts = Mock(spec=FallbackAlertManager)
        with patch.object(ModelGateway, "_build_model", side_effect=models):
            gateway = ModelGateway(router_settings(), alerts=alerts)

        result, metadata = await gateway.generate_json_with_metadata(
            "prompt",
            LlmTraceContext(feature="test", user_id="account:1", session_id="s-1"),
        )

        self.assertEqual("local model", result["answer"])
        self.assertEqual("qwen3:8b", metadata["model"])
        self.assertEqual(2, metadata["fallbackLevel"])
        self.assertEqual(2, alerts.fallback.call_count)
        alerts.exhausted.assert_not_called()

    async def test_exhausted_chain_emits_alert(self) -> None:
        models = [FakeModel("invalid"), FakeModel("[]"), FakeModel(error=OSError("offline"))]
        alerts = Mock(spec=FallbackAlertManager)
        context = LlmTraceContext(feature="teaching-plan", trace_id="trace-1")
        with patch.object(ModelGateway, "_build_model", side_effect=models):
            gateway = ModelGateway(router_settings(), alerts=alerts)

        result = await gateway.generate_json("prompt", context)

        self.assertIsNone(result)
        attempts = alerts.exhausted.call_args.args[1]
        self.assertEqual(["gpt-4", "gpt-3.5", "qwen3:8b"], [item["model"] for item in attempts])
        self.assertEqual(["json_parse", "json_parse", "network"], [item["errorType"] for item in attempts])

    async def test_stream_emits_reset_boundary_before_next_model(self) -> None:
        models = [FakeModel("invalid"), FakeModel('{"answer":"fallback"}'), FakeModel("unused")]
        alerts = Mock(spec=FallbackAlertManager)
        with patch.object(ModelGateway, "_build_model", side_effect=models):
            gateway = ModelGateway(router_settings(), alerts=alerts)

        events = [event async for event in gateway.stream_json_events("prompt")]

        names = [name for name, _data in events]
        self.assertIn("fallback", names)
        self.assertEqual("complete", names[-1])
        self.assertLess(names.index("token"), names.index("complete"))
        fallback = next(data for name, data in events if name == "fallback")
        self.assertEqual("gpt-4", fallback["failedModel"])
        self.assertEqual("gpt-3.5", fallback["nextModel"])
        self.assertEqual(1, events[-1][1]["fallbackLevel"])


if __name__ == "__main__":
    unittest.main()
