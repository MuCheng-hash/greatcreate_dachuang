from __future__ import annotations

from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

from fastapi.testclient import TestClient

from llm_service.api import create_app
from llm_service.observability import LlmObservability, LlmTraceContext
from llm_service.settings import Settings


class LlmObservabilityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = TemporaryDirectory()
        self.database_path = Path(self.temporary.name) / "state.sqlite3"
        self.store = LlmObservability(
            self.database_path,
            {"qwen-plus": {"input": 1.0, "output": 2.0}},
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_summary_tracks_tokens_cost_percentiles_and_errors(self) -> None:
        context = LlmTraceContext(feature="teaching-plan", user_id="account:7", session_id="s-1")
        first = self.store.start_call(context, "bailian", "qwen-plus")
        self.store.finish_call(first, 100, 1000, 500, True)
        second = self.store.start_call(context, "bailian", "qwen-plus")
        self.store.finish_call(second, 900, 2000, 1000, False, "invalid_response", "json_parse")
        third = self.store.start_call(context, "bailian", "qwen-plus")
        self.store.finish_call(third, 500, None, None, False, "failed", "timeout", "timed out")

        summary = self.store.summary({"user_id": "account:7"})

        self.assertEqual(3, summary["calls"])
        self.assertEqual(round(1 / 3, 4), summary["successRate"])
        self.assertEqual(round(1 / 3, 4), summary["validJsonRate"])
        self.assertEqual({"average": 500.0, "p50": 500, "p95": 900, "p99": 900}, summary["latencyMs"])
        self.assertEqual(3000, summary["tokens"]["input"])
        self.assertEqual(1500, summary["tokens"]["output"])
        self.assertEqual(0.006, summary["costUsd"])
        self.assertEqual(1, summary["unpricedCalls"])
        self.assertEqual({"json_parse": 1, "timeout": 1}, summary["errors"])
        self.assertEqual("account:7", summary["breakdown"]["byUser"][0]["userId"])
        self.assertEqual("s-1", summary["breakdown"]["bySession"][0]["sessionId"])
        self.assertEqual("teaching-plan", summary["breakdown"]["byFeature"][0]["feature"])
        self.assertEqual("account:7", summary["groups"][0]["userId"])
        self.assertEqual("s-1", summary["groups"][0]["sessionId"])

    def test_callback_records_provider_usage_and_json_validity(self) -> None:
        handler = self.store.callback(
            LlmTraceContext(feature="resource-discovery", user_id="school:3"),
            "bailian",
            "qwen-plus",
        )
        handler.on_chat_model_start({}, [[]], run_id="run-1")
        message = SimpleNamespace(
            content='{"results":[]}',
            usage_metadata={"input_tokens": 12, "output_tokens": 4},
        )
        generation = SimpleNamespace(message=message, text="")
        response = SimpleNamespace(generations=[[generation]], llm_output={})
        handler.on_llm_new_token("{", run_id="run-1")
        handler.on_llm_end(response, run_id="run-1")

        traces = self.store.traces({"feature": "resource-discovery"})

        self.assertEqual(1, len(traces))
        self.assertEqual("completed", traces[0]["status"])
        self.assertTrue(traces[0]["validJson"])
        self.assertEqual(12, traces[0]["inputTokens"])
        self.assertEqual(4, traces[0]["outputTokens"])
        self.assertEqual("provider", traces[0]["tokenSource"])
        self.assertIsNotNone(traces[0]["firstTokenLatencyMs"])

    def test_admin_api_is_protected_and_metrics_have_no_user_labels(self) -> None:
        settings = Settings(
            database_path=self.database_path,
            observability_admin_token="observe-secret",
            llm_model_pricing={"qwen-plus": {"input": 1, "output": 2}},
        )
        app = create_app(settings, self.store)
        client = TestClient(app)
        call_id = self.store.start_call(
            LlmTraceContext(feature="teaching-plan", user_id="account:9", session_id="private-session"),
            "bailian",
            "qwen-plus",
        )
        self.store.finish_call(call_id, 42, 10, 5, True)

        self.assertEqual(401, client.get("/admin/observability/summary").status_code)
        response = client.get(
            "/admin/observability/summary?userId=account%3A9",
            headers={"X-Observability-Admin-Token": "observe-secret"},
        )
        self.assertEqual(200, response.status_code)
        self.assertEqual(1, response.json()["calls"])

        metrics = client.get("/metrics").text
        self.assertIn('llm_calls_total{feature="teaching-plan"', metrics)
        self.assertNotIn("account:9", metrics)
        self.assertNotIn("private-session", metrics)

    def test_agent_tool_call_is_successful_without_json_validation(self) -> None:
        handler = self.store.callback(
            LlmTraceContext(feature="agent-runtime", expected_json=True),
            "bailian",
            "qwen-plus",
        )
        handler.on_chat_model_start({}, [[]], run_id="tool-run")
        message = SimpleNamespace(
            content="", tool_calls=[{"name": "search", "args": {}}],
            usage_metadata={"input_tokens": 8, "output_tokens": 2},
        )
        response = SimpleNamespace(
            generations=[[SimpleNamespace(message=message, text="")]], llm_output={}
        )

        handler.on_llm_end(response, run_id="tool-run")

        trace = self.store.traces({"feature": "agent-runtime"})[0]
        self.assertEqual("completed", trace["status"])
        self.assertIsNone(trace["validJson"])


if __name__ == "__main__":
    unittest.main()
