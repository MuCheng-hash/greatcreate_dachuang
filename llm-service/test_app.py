from __future__ import annotations

import asyncio
import json
from pathlib import Path
from unittest.mock import AsyncMock

from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage

from llm_service.api import create_app
from llm_service.repository import ConversationRepository
from llm_service.schemas import AgentMessageRequest, TrustedContext
from llm_service.settings import Settings
from llm_service.tools import ToolRuntimeContext, bind_tool_runtime, reset_tool_runtime, search_approved_resources


def settings_for(tmp_path: Path, **overrides) -> Settings:
    return Settings(
        _env_file=None,
        database_path=tmp_path / "agent.sqlite3",
        internal_service_token=overrides.pop("internal_service_token", "test-agent-secret"),
        llm_api_url="",
        llm_api_key="",
        fallback_provider=overrides.pop("fallback_provider", ""),
        fallback_model=overrides.pop("fallback_model", ""),
        fallback_base_url=overrides.pop("fallback_base_url", ""),
        fallback_api_key=overrides.pop("fallback_api_key", ""),
        agent_context_token_budget=overrides.pop("agent_context_token_budget", 1000),
        agent_recent_message_count=overrides.pop("agent_recent_message_count", 6),
        **overrides,
    )


def build_client(settings: Settings) -> TestClient:
    return TestClient(
        create_app(settings),
        headers={"X-Agent-Service-Token": settings.internal_service_token},
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


def test_unified_tasks_and_old_routes_are_removed(tmp_path: Path):
    settings = settings_for(tmp_path)
    with build_client(settings) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["agentRuntime"] == "langchain-langgraph"

        plan = client.post(
            "/agent/messages",
            json=message_payload(
                taskType="TEACHING_PLAN",
                taskPayload={
                    "theme": "家乡红色文化",
                    "grade": "四年级",
                    "activityType": "CLASSROOM",
                    "durationMinutes": 40,
                    "practiceRequired": False,
                },
                message="请生成结构化教学方案。",
            ),
        )
        assert plan.status_code == 200
        assert plan.json()["taskType"] == "TEACHING_PLAN"
        assert plan.json()["teachingPlan"]["generationStatus"] == "degraded"

        classification = client.post(
            "/agent/messages",
            json=message_payload(
                taskType="RESOURCE_DISCOVERY",
                taskPayload={"candidates": [{"providerPlaceId": "A1", "name": "候选地点"}]},
                message="请分析候选地点。",
            ),
        )
        assert classification.status_code == 200
        assert classification.json()["resourceDiscovery"]["analysisStatus"] == "unavailable"

        for old_path in (
            "/llm/agent/answer",
            "/llm/agent/run",
            "/llm/agent/stream",
            "/llm/town/explain",
            "/llm/town/ask",
            "/llm/school/explain",
            "/llm/school/ask",
            "/llm/teaching-plan/generate",
            "/llm/teaching-plan/generate/stream",
            "/llm/resource-discovery/classify",
        ):
            assert client.post(old_path, json={}).status_code == 404


def test_validation_rejects_missing_owner_and_unknown_scope(tmp_path: Path):
    with build_client(settings_for(tmp_path)) as client:
        missing = client.post("/agent/messages", json={"message": "你好"})
        assert missing.status_code == 422
        invalid = client.post("/agent/messages", json=message_payload(scopeType="OTHER"))
        assert invalid.status_code == 422


def test_new_thread_and_multiturn_persistence(tmp_path: Path):
    with build_client(settings_for(tmp_path)) as client:
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


def test_stateful_stream_emits_events_and_persists_final_response(tmp_path: Path):
    with build_client(settings_for(tmp_path)) as client:
        response = client.post("/agent/messages/stream", json=message_payload())

        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        assert "event: run.started" in response.text
        assert "event: model.failed" in response.text
        assert "event: token" in response.text
        assert "event: final" in response.text
        assert "event: done" in response.text

        final_block = next(
            block for block in response.text.strip().split("\n\n")
            if block.startswith("event: final")
        )
        final_data = json.loads(final_block.split("data: ", 1)[1])
        thread_id = final_data["threadId"]

        stored = client.get(
            f"/agent/threads/{thread_id}",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        ).json()
        assert [item["role"] for item in stored["messages"]] == ["user", "assistant"]
        assert stored["messages"][-1]["content"] == final_data["response"]["answer"]


def test_teaching_plan_and_resource_discovery_streams_use_unified_protocol(tmp_path: Path):
    with build_client(settings_for(tmp_path)) as client:
        plan_response = client.post(
            "/agent/messages/stream",
            json=message_payload(
                taskType="TEACHING_PLAN",
                taskPayload={"theme": "家乡文化", "grade": "四年级"},
                message="请生成教学方案。",
            ),
        )
        assert plan_response.status_code == 200
        assert "event: run.started" in plan_response.text
        assert "event: token" in plan_response.text
        assert "event: final" in plan_response.text
        plan_final = next(
            block for block in plan_response.text.split("\n\n") if block.startswith("event: final")
        )
        plan_data = json.loads(plan_final.split("data: ", 1)[1])
        assert plan_data["response"]["taskType"] == "TEACHING_PLAN"
        assert plan_data["response"]["teachingPlan"]["theme"] == "家乡文化"

        discovery_response = client.post(
            "/agent/messages/stream",
            json=message_payload(
                taskType="RESOURCE_DISCOVERY",
                taskPayload={"candidates": [{"providerPlaceId": "A1", "name": "候选地点"}]},
                message="请分析候选地点。",
            ),
        )
        assert discovery_response.status_code == 200
        assert "event: final" in discovery_response.text
        discovery_final = next(
            block for block in discovery_response.text.split("\n\n") if block.startswith("event: final")
        )
        discovery_data = json.loads(discovery_final.split("data: ", 1)[1])
        assert discovery_data["response"]["taskType"] == "RESOURCE_DISCOVERY"
        assert discovery_data["response"]["resourceDiscovery"]["results"] == []


def test_resource_discovery_filters_model_results_to_input_candidates(tmp_path: Path):
    settings = settings_for(tmp_path)
    application = create_app(settings)
    application.state.model.generate_json_with_metadata = AsyncMock(return_value=(
        {
            "analysisStatus": "completed",
            "message": "已完成分类",
            "results": [
                {"providerPlaceId": "A1", "resourceCategory": "red_culture", "confidence": 0.9},
                {"providerPlaceId": "forged", "resourceCategory": "red_culture", "confidence": 1.0},
            ],
        },
        {"provider": "bailian", "model": "qwen-plus", "fallbackLevel": 0},
    ))
    with TestClient(application, headers={"X-Agent-Service-Token": settings.internal_service_token}) as client:
        response = client.post(
            "/agent/messages",
            json=message_payload(
                taskType="RESOURCE_DISCOVERY",
                taskPayload={"candidates": [{"providerPlaceId": "A1", "name": "候选地点"}]},
                message="请分析候选地点。",
            ),
        )

    assert response.status_code == 200
    result = response.json()["resourceDiscovery"]
    assert result["analysisStatus"] == "completed"
    assert [item["providerPlaceId"] for item in result["results"]] == ["A1"]


def test_stateful_runtime_uses_configured_fallback_model(tmp_path: Path):
    settings = settings_for(
        tmp_path,
        primary_provider="bailian",
        primary_model="qwen-plus",
        primary_base_url="https://dashscope.example/v1",
        primary_api_key="primary-key",
        fallback_provider="ollama",
        fallback_model="qwen3:8b",
        fallback_base_url="http://127.0.0.1:11434/v1",
        fallback_api_key="ollama",
    )
    app = create_app(settings)
    runtime = app.state.runtime

    class FakeAgent:
        def __init__(self, content: str):
            self.content = content

        async def ainvoke(self, _input, config=None):
            return {"messages": [AIMessage(content=self.content)]}

    runtime._create_agent_for = lambda config: {
        "qwen-plus": FakeAgent("阿里云无效响应"),
        "qwen3:8b": FakeAgent('{"answer":"Ollama回答","citationIds":[]}'),
    }[config.model]

    response = asyncio.run(
        runtime.handle(AgentMessageRequest.model_validate(
            message_payload(message="主模型失败后继续")
        ))
    )

    assert response.answer == "Ollama回答"
    assert response.provider == "ollama"
    assert response.model == "qwen3:8b"
    assert response.fallback_level == 1
    assert response.generation_status == "completed"


def test_stateful_stream_reports_primary_failure_and_fallback_success(tmp_path: Path):
    settings = settings_for(
        tmp_path,
        primary_provider="bailian",
        primary_model="qwen-plus",
        primary_api_key="primary-key",
        fallback_provider="ollama",
        fallback_model="qwen3:8b",
        fallback_api_key="ollama",
    )
    app = create_app(settings)
    runtime = app.state.runtime

    class FakeAgent:
        def __init__(self, content: str):
            self.content = content

        async def ainvoke(self, _input, config=None):
            return {"messages": [AIMessage(content=self.content)]}

    runtime._create_agent_for = lambda config: {
        "qwen-plus": FakeAgent("阿里云无效响应"),
        "qwen3:8b": FakeAgent('{"answer":"Ollama流式回答","citationIds":[]}'),
    }[config.model]

    async def collect_events():
        return [event async for event in runtime.stream_events(
            AgentMessageRequest.model_validate(
                message_payload(message="流式主模型失败后继续", conversationId="fallback-stream")
            )
        )]

    events = asyncio.run(collect_events())
    names = [event.split("\n", 1)[0].removeprefix("event: ") for event in events]
    assert names.count("model.started") == 2
    assert "model.failed" in names
    assert "model.completed" in names
    final_block = next(event for event in events if event.startswith("event: final"))
    final_data = json.loads(final_block.split("data: ", 1)[1])
    assert final_data["response"]["provider"] == "ollama"
    assert final_data["response"]["fallbackLevel"] == 1


def test_stateful_stream_rejects_cross_owner_and_cross_scope_thread(tmp_path: Path):
    with build_client(settings_for(tmp_path)) as client:
        first = client.post("/agent/messages", json=message_payload()).json()
        thread_id = first["threadId"]

        cross_owner = client.post(
            "/agent/messages/stream",
            json=message_payload(ownerId="school-user:2", threadId=thread_id),
        )
        cross_scope = client.post(
            "/agent/messages/stream",
            json=message_payload(threadId=thread_id, scopeId=2),
        )

        assert cross_owner.status_code == 404
        assert cross_scope.status_code == 404


def test_agent_service_token_is_required_when_configured(tmp_path: Path):
    settings = settings_for(tmp_path, internal_service_token="internal-secret")
    with TestClient(create_app(settings)) as client:
        missing = client.post("/agent/messages", json=message_payload())
        accepted = client.post(
            "/agent/messages",
            headers={"X-Agent-Service-Token": "internal-secret"},
            json=message_payload(),
        )

        assert missing.status_code == 401
        assert accepted.status_code == 200


def test_agent_service_token_configuration_fails_closed(tmp_path: Path):
    settings = settings_for(tmp_path, internal_service_token="")
    with TestClient(create_app(settings)) as client:
        response = client.post("/agent/messages", json=message_payload())
        assert response.status_code == 503


def test_restart_recovery_uses_same_database(tmp_path: Path):
    settings = settings_for(tmp_path)
    with build_client(settings) as client:
        thread_id = client.post("/agent/messages", json=message_payload()).json()["threadId"]
    with build_client(settings) as restarted:
        response = restarted.post(
            "/agent/messages", json=message_payload(threadId=thread_id, message="继续")
        )
        assert response.status_code == 200
        assert response.json()["threadId"] == thread_id


def test_owner_and_scope_are_isolated(tmp_path: Path):
    with build_client(settings_for(tmp_path)) as client:
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
    with build_client(settings) as client:
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
    assert runtime.event_sink is None
    with repository._connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM agent_tool_audit").fetchone()[0] == 1


def test_tool_runtime_emits_started_and_completed_events(tmp_path: Path):
    repository = ConversationRepository(tmp_path / "tool-events.sqlite3")
    thread = repository.create_thread("owner", "SCHOOL", 1)
    context = TrustedContext(resources=[{"resource": {"resourceName": "甲纪念馆"}}])
    events = []
    runtime = ToolRuntimeContext(thread.thread_id, context, repository, 2000, event_sink=lambda name, data: events.append((name, data)))
    token = bind_tool_runtime(runtime)
    try:
        search_approved_resources.invoke({"query": "纪念馆", "limit": 1})
    finally:
        reset_tool_runtime(token)

    assert [name for name, _ in events] == ["tool.started", "tool.completed"]
    assert events[0][1]["toolName"] == "search_approved_resources"
    assert events[1][1]["status"] == "ok"


def test_model_output_filters_invented_citations(tmp_path: Path):
    runtime = create_app(settings_for(tmp_path)).state.runtime

    class FakeAgent:
        async def ainvoke(self, _input, config=None):
            return {"messages": [AIMessage(content=(
                '{"answer":"基于可信资料回答。","citationIds":["chunk:1","invented:9"],'
                '"relatedResources":["红色纪念馆"],"followUpQuestions":[]}'
            ))]}

    runtime._agent = FakeAgent()
    response = asyncio.run(runtime.handle(AgentMessageRequest.model_validate(message_payload())))
    assert response.status == "completed"
    assert [item.citation_id for item in response.citations] == ["chunk:1"]
