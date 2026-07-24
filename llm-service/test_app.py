from __future__ import annotations

import asyncio
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

import app
import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage

from llm_service.api import create_app
from llm_service.container import build_container
from llm_service import legacy_api
from llm_service.repository import ConversationRepository
from llm_service.schemas import AgentMessageRequest, TrustedContext
from llm_service.settings import LlmModelTarget, Settings
from llm_service.tools import ToolRuntimeContext, bind_tool_runtime, reset_tool_runtime, search_approved_resources


class AgentAnswerEndpointTest(unittest.TestCase):

    def setUp(self):
        self.client = TestClient(app.app)
        self.payload = {
            "question": "里庄小学附近有哪些红色资源？",
            "intent": "NEARBY_RESOURCE",
            "scope": {"scopeType": "SCHOOL", "scopeId": 1, "name": "里庄小学"},
            "businessContext": {
                "school": {"schoolName": "里庄小学"},
                "resources": [
                    {"resource": {"resourceName": "常安镇敬老院"}}
                ],
            },
            "retrievalResult": {
                "chunks": [{"citationId": "chunk:1", "text": "资源证据"}],
                "graphFacts": [{"citationId": "graph:1", "text": "图谱证据"}],
                "citationCandidates": [{"citationId": "chunk:1"}],
            },
        }

    @patch.object(legacy_api, "call_openai_compatible", return_value=None)
    def test_returns_degraded_fallback_with_valid_evidence_ids(self, _call):
        response = self.client.post("/llm/agent/answer", json=self.payload)

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["generationStatus"], "degraded")
        self.assertTrue(data["answer"])
        self.assertEqual(data["citationIds"], ["chunk:1", "graph:1"])

    @patch.object(legacy_api, "call_openai_compatible", return_value={
        "answer": "模型回答",
        "citationIds": ["chunk:1", "forged:citation"],
        "followUpQuestions": ["它适合哪个年级？"],
    })
    def test_filters_model_citations_to_known_evidence(self, _call):
        response = self.client.post("/llm/agent/answer", json=self.payload)

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["generationStatus"], "completed")
        self.assertEqual(data["citationIds"], ["chunk:1"])
        self.assertNotIn("forged:citation", data["citationIds"])

    def test_accepts_markdown_wrapped_json_from_compatible_model(self):
        parsed = app.parse_model_json('```json\n{"answer":"回答","citationIds":[]}\n```')

        self.assertEqual(parsed["answer"], "回答")

    def test_resolves_bailian_chat_completions_endpoint_from_base_url(self):
        with patch.object(app, "LLM_API_URL", ""), patch.object(
            app,
            "LLM_BASE_URL",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/",
        ):
            self.assertEqual(
                app.resolve_llm_api_url(),
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            )

    @patch.object(app, "LLM_API_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
    @patch.object(app, "LLM_API_KEY", "test-key")
    @patch.object(app, "ModelGateway")
    def test_calls_openai_compatible_through_model_router(self, gateway):
        gateway.return_value.generate_json = AsyncMock(return_value={"answer": "模型回答"})

        result = app.call_openai_compatible("请回答")

        self.assertEqual(result["answer"], "模型回答")
        router_settings = gateway.call_args.args[0]
        self.assertEqual(
            router_settings.llm_api_url,
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        )
        self.assertEqual(router_settings.llm_api_key, "test-key")


if __name__ == "__main__":
    unittest.main()


def settings_for(tmp_path: Path, **overrides) -> Settings:
    return Settings(
        database_path=tmp_path / "agent.sqlite3",
        llm_api_url="",
        llm_api_key="",
        agent_context_token_budget=overrides.pop("agent_context_token_budget", 1000),
        agent_recent_message_count=overrides.pop("agent_recent_message_count", 6),
        **overrides,
    )


def message_payload(**overrides):
    payload = {
        "ownerId": "school-user:1",
        "scopeType": "SCHOOL",
        "scopeId": 1,
        "message": "附近有哪些红色资源？",
        "context": {
            "school": {"schoolName": "里庄小学"},
            "resources": [{"resource": {"resourceName": "红色纪念馆"}}],
            "retrieval": {
                "retrievalStatus": "ok",
                "chunks": [{"citationId": "chunk:1", "title": "馆史", "text": "纪念馆资料"}],
            },
            "citationCandidates": [{"citationId": "chunk:1", "title": "馆史", "excerpt": "纪念馆资料"}],
        },
    }
    payload.update(overrides)
    return payload


def test_health_and_legacy_routes(tmp_path: Path):
    app = create_app(settings_for(tmp_path))
    with TestClient(app) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["agentRuntime"] == "langchain-langgraph"

        plan = client.post("/llm/teaching-plan/generate", json={"request": {"theme": "家乡红色文化"}})
        assert plan.status_code == 200
        assert plan.json()["generationStatus"] == "degraded"

        classification = client.post(
            "/llm/resource-discovery/classify",
            json={"candidates": [{"providerPlaceId": "A1", "name": "候选地点"}]},
        )
        assert classification.json()["analysisStatus"] == "unavailable"


def test_profile_configuration_is_overridden_by_environment(tmp_path: Path, monkeypatch):
    override = tmp_path / "service.toml"
    override.write_text("port = 6123\nllm_timeout_seconds = 7.0\n", encoding="utf-8")
    monkeypatch.setenv("APP_ENV", "dev")
    monkeypatch.setenv("APP_CONFIG_FILE", str(override))
    monkeypatch.setenv("LLM_TIMEOUT_SECONDS", "9.0")

    settings = Settings(_env_file=None)

    assert settings.app_env == "dev"
    assert settings.port == 6123
    assert settings.llm_timeout_seconds == 9.0


def test_production_profile_rejects_missing_admin_tokens(monkeypatch):
    monkeypatch.setenv("APP_ENV", "prod")
    monkeypatch.delenv("PROMPT_ADMIN_TOKEN", raising=False)
    monkeypatch.delenv("OBSERVABILITY_ADMIN_TOKEN", raising=False)

    with pytest.raises(ValueError, match="admin tokens"):
        Settings(_env_file=None)


def test_container_can_be_explicitly_injected_and_required_health_failure_is_503(tmp_path: Path):
    settings = settings_for(
        tmp_path,
        internal_business_base_url="",
        business_health_required=True,
    )
    container = build_container(settings)
    application = create_app(container=container)

    assert application.state.container is container
    with TestClient(application) as client:
        assert client.get("/health/live").status_code == 200
        readiness = client.get("/health/ready")
        assert readiness.status_code == 503
        payload = readiness.json()
        assert payload["dependencies"]["businessService"]["required"] is True
        assert payload["dependencies"]["businessService"]["status"] == "down"
        assert "test-key" not in str(payload)


def test_validation_rejects_missing_owner_and_unknown_scope(tmp_path: Path):
    with TestClient(create_app(settings_for(tmp_path))) as client:
        missing = client.post("/agent/messages", json={"message": "你好"})
        assert missing.status_code == 422
        invalid = client.post("/agent/messages", json=message_payload(scopeType="OTHER"))
        assert invalid.status_code == 422


def test_new_thread_and_multiturn_persistence(tmp_path: Path):
    with TestClient(create_app(settings_for(tmp_path))) as client:
        first = client.post("/agent/messages", json=message_payload())
        assert first.status_code == 200
        data = first.json()
        assert data["status"] == "degraded"
        thread_id = data["threadId"]

        second = client.post(
            "/agent/messages",
            json=message_payload(threadId=thread_id, message="它适合四年级吗？"),
        )
        assert second.status_code == 200
        assert second.json()["threadId"] == thread_id

        stored = client.get(
            f"/agent/threads/{thread_id}",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        ).json()
        assert [item["role"] for item in stored["messages"]] == ["user", "assistant", "user", "assistant"]


def test_restart_recovery_uses_same_database(tmp_path: Path):
    settings = settings_for(tmp_path)
    with TestClient(create_app(settings)) as client:
        thread_id = client.post("/agent/messages", json=message_payload()).json()["threadId"]
    with TestClient(create_app(settings)) as restarted:
        response = restarted.post(
            "/agent/messages", json=message_payload(threadId=thread_id, message="继续")
        )
        assert response.status_code == 200
        assert response.json()["threadId"] == thread_id


def test_owner_and_scope_are_isolated(tmp_path: Path):
    with TestClient(create_app(settings_for(tmp_path))) as client:
        thread_id = client.post("/agent/messages", json=message_payload()).json()["threadId"]
        cross_owner = client.post(
            "/agent/messages",
            json=message_payload(ownerId="school-user:2", threadId=thread_id),
        )
        assert cross_owner.status_code == 404
        cross_scope = client.post(
            "/agent/messages",
            json=message_payload(threadId=thread_id, scopeId=2),
        )
        assert cross_scope.status_code == 404


def test_context_is_compacted_but_raw_messages_remain(tmp_path: Path):
    settings = settings_for(tmp_path, agent_context_token_budget=60, agent_recent_message_count=2)
    with TestClient(create_app(settings)) as client:
        thread_id = None
        for number in range(4):
            response = client.post(
                "/agent/messages",
                json=message_payload(threadId=thread_id, message=(f"第{number}轮" + "很长的上下文" * 15)),
            )
            assert response.status_code == 200
            thread_id = response.json()["threadId"]
        assert response.json()["contextCompacted"] is True
        thread = client.get(f"/agent/threads/{thread_id}", params={"ownerId": "school-user:1"}).json()
        assert len(thread["messages"]) == 8
        assert thread["summary"]


def test_tool_registry_is_scoped_bounded_and_audited(tmp_path: Path):
    repository = ConversationRepository(tmp_path / "tools.sqlite3")
    thread = repository.create_thread("owner", "SCHOOL", 1)
    context = TrustedContext(resources=[
        {"resource": {"resourceName": "甲纪念馆"}},
        {"resource": {"resourceName": "乙文化站"}},
    ])
    runtime = ToolRuntimeContext(thread.thread_id, context, repository, 2000)
    token = bind_tool_runtime(runtime)
    try:
        output = search_approved_resources.invoke({"query": "纪念馆", "limit": 8})
    finally:
        reset_tool_runtime(token)
    assert "甲纪念馆" in output
    assert "乙文化站" not in output
    assert runtime.executions[0].name == "search_approved_resources"
    with repository._connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM agent_tool_audit").fetchone()[0] == 1


def test_model_output_filters_invented_citations(tmp_path: Path):
    app = create_app(settings_for(tmp_path))
    runtime = app.state.runtime

    class FakeAgent:
        async def ainvoke(self, _input, config=None):
            return {"messages": [AIMessage(content=(
                '{"answer":"基于可信资料回答。","citationIds":["chunk:1","invented:9"],'
                '"relatedResources":["红色纪念馆"],"followUpQuestions":[]}'
            ))]}

    runtime._agents = [(
        LlmModelTarget("primary", "test", "test-model", "http://test", "test-key", 0),
        FakeAgent(),
    )]
    response = asyncio.run(runtime.handle(AgentMessageRequest.model_validate(message_payload())))
    assert response.status == "completed"
    assert [item.citation_id for item in response.citations] == ["chunk:1"]
