from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage

from llm_service.api import create_app
from llm_service.repository import ConversationRepository
from llm_service.schemas import AgentMessageRequest, TrustedContext
from llm_service.settings import Settings
from llm_service.tools import ToolRuntimeContext, bind_tool_runtime, reset_tool_runtime, search_approved_resources


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

    runtime._agent = FakeAgent()
    response = asyncio.run(runtime.handle(AgentMessageRequest.model_validate(message_payload())))
    assert response.status == "completed"
    assert [item.citation_id for item in response.citations] == ["chunk:1"]
