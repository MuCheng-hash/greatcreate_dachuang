from __future__ import annotations

import json
import uuid
from pathlib import Path
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, AIMessageChunk
import pytest

from llm_service.api import create_app
from llm_service.container import build_container
from llm_service.planner import AgentPlan
from llm_service.runtime import AgentRuntime
from llm_service.model_gateway import ModelGateway
from llm_service.schemas import AgentMessageRequest, MemoryApplied, ToolExecution, TrustedContext
from llm_service.settings import Settings
from llm_service.structured_tasks import (
    IncrementalTeachingPlanParser,
    normalize_teaching_plan,
    teaching_plan_stream_text,
)
from llm_service.tools import ToolRuntimeContext, bind_tool_runtime, reset_tool_runtime, search_approved_resources
from postgres_test_support import (
    conversation_repository,
    run_async,
    settings_for_database,
    started_runtime,
    database_url_for_test,
)


def test_multimodal_request_builds_image_message(tmp_path: Path):
    request = AgentMessageRequest.model_validate(message_payload(
        message="请分析这张图片",
        attachments=[{
            "type": "image",
            "name": "resource.png",
            "mediaType": "image/png",
            "dataUrl": "data:image/png;base64,aGVsbG8taGVsbG8taGVsbG8=",
        }],
    ))
    settings = settings_for(tmp_path)
    repository = conversation_repository(settings)
    runtime = AgentRuntime(settings, repository.async_target)
    messages = runtime._build_messages(
        [{"role": "user", "content": request.message}], "",
        AgentPlan(request.message, tuple(), 1), request,
    )

    assert messages[-1].content[0] == {"type": "text", "text": "请分析这张图片"}
    assert messages[-1].content[1]["type"] == "image_url"
    assert messages[-1].content[1]["image_url"]["url"].startswith("data:image/png;base64,")


def test_controlled_query_rewrite_only_uses_contextual_reference_and_never_generates_facts(tmp_path: Path):
    settings = settings_for(tmp_path)
    repository = conversation_repository(settings)
    runtime = AgentRuntime(settings, repository.async_target)
    thread = repository.create_thread("account:1", "SCHOOL", 1)
    calls: list[str] = []

    class FakeModel:
        async def generate_json(self, prompt, validator=None, **_kwargs):
            calls.append(prompt)
            return {
                "searchQuery": "里庄小学成立多久？",
                "intent": "RESOURCE_EXPLANATION",
                "grade": "四年级",
                "theme": "校史",
                "confidence": 0.93,
            }

    runtime.model = FakeModel()
    contextual = AgentMessageRequest.model_validate(message_payload(message="这个学校成立多久？"))
    explicit = AgentMessageRequest.model_validate(message_payload(message="里庄小学成立多久？"))

    rewritten = run_async(runtime._controlled_query_rewrite(contextual, thread))
    unchanged = run_async(runtime._controlled_query_rewrite(explicit, thread))

    assert rewritten["status"] == "applied"
    assert rewritten["searchQuery"] == "里庄小学成立多久？"
    assert unchanged == {"status": "skipped", "searchQuery": "里庄小学成立多久？"}
    assert len(calls) == 1
    assert "不得生成任何新事实" in calls[0]


def test_authoritative_web_host_allows_only_configured_domain_or_its_subdomain() -> None:
    domains = ["www.gov.cn"]

    assert AgentRuntime._allowed_web_host("www.gov.cn", domains)
    assert AgentRuntime._allowed_web_host("news.www.gov.cn", domains)
    assert not AgentRuntime._allowed_web_host("www.gov.cn.example.org", domains)
    assert not AgentRuntime._allowed_web_host("example.org", domains)


def settings_for(tmp_path: Path, **overrides) -> Settings:
    return settings_for_database(
        tmp_path,
        internal_service_token=overrides.pop("internal_service_token", "test-agent-secret"),
        llm_api_url="",
        llm_api_key="",
        fallback_provider=overrides.pop("fallback_provider", ""),
        fallback_model=overrides.pop("fallback_model", ""),
        fallback_base_url=overrides.pop("fallback_base_url", ""),
        fallback_api_key=overrides.pop("fallback_api_key", ""),
        agent_context_token_budget=overrides.pop("agent_context_token_budget", 1000),
        agent_recent_message_count=overrides.pop("agent_recent_message_count", 6),
        business_health_required=overrides.pop("business_health_required", False),
        **overrides,
    )


def build_client(settings: Settings) -> TestClient:
    return TestClient(
        create_app(settings),
        headers={"X-Agent-Service-Token": settings.internal_service_token},
    )


def test_model_catalog_and_unknown_selection(tmp_path) -> None:
    settings = settings_for(
        tmp_path,
        llm_models=[{
            "id": "catalog", "provider": "test", "model": "catalog-model",
            "apiUrl": "https://models.example/v1", "apiKey": "secret",
        }],
    )
    with build_client(settings) as client:
        catalog_response = client.get("/models")
        invalid_response = client.post(
            "/agent/messages",
            json=message_payload(modelId="missing-model"),
        )

    assert catalog_response.status_code == 200
    catalog = catalog_response.json()["models"]
    assert catalog
    assert "apiKey" not in catalog[0]
    assert "baseUrl" not in catalog[0]
    assert invalid_response.status_code == 422


def message_payload(**overrides):
    payload = {
        "ownerId": "school-user:1",
        "scopeType": "SCHOOL",
        "scopeId": 1,
        "message": "附近有哪些红色资源？",
        "clientTurnId": str(uuid.uuid4()),
        "context": {
            "school": {"schoolName": "里庄小学"},
            "resources": [{"resource": {"resourceName": "红色纪念馆"}}],
            "retrieval": {
                "retrievalStatus": "ok",
                "chunks": [{
                    "citationId": "chunk:1", "title": "馆史", "text": "纪念馆资料",
                    "retrievalMethod": "vector+hybrid-rerank",
                }],
            },
            "citationCandidates": [{"citationId": "chunk:1", "title": "馆史", "excerpt": "纪念馆资料"}],
        },
    }
    payload.update(overrides)
    return payload


def test_unified_tasks_keep_compatible_routes_and_remove_old_agent_routes(tmp_path: Path):
    settings = settings_for(tmp_path)
    with build_client(settings) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["agentRuntime"] == "langchain-persistent-history"

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

        removed_paths = (
            "/llm/agent/answer",
            "/llm/agent/run",
            "/llm/agent/stream",
        )
        compatible_paths = (
            "/llm/town/explain",
            "/llm/town/ask",
            "/llm/school/explain",
            "/llm/school/ask",
            "/llm/teaching-plan/generate",
            "/llm/teaching-plan/generate/stream",
        )
        registered_paths = client.get("/openapi.json").json()["paths"]
        assert all(path not in registered_paths for path in removed_paths)
        assert all(path in registered_paths for path in compatible_paths)
        assert all(client.post(path, json={}).status_code == 404 for path in removed_paths)


def test_profile_configuration_is_overridden_by_environment(tmp_path: Path, monkeypatch):
    override = tmp_path / "service.toml"
    override.write_text("port = 6123\nllm_timeout_seconds = 7.0\n", encoding="utf-8")
    monkeypatch.setenv("APP_ENV", "dev")
    monkeypatch.setenv("APP_CONFIG_FILE", str(override))
    monkeypatch.setenv("LLM_TIMEOUT_SECONDS", "9.0")

    settings = Settings(
        _env_file=None,
        database_url=database_url_for_test(tmp_path),
    )

    assert settings.app_env == "dev"
    assert settings.port == 6123
    assert settings.llm_timeout_seconds == 9.0


def test_production_profile_rejects_missing_admin_tokens(monkeypatch):
    monkeypatch.setenv("APP_ENV", "prod")
    monkeypatch.delenv("PROMPT_ADMIN_TOKEN", raising=False)
    monkeypatch.delenv("OBSERVABILITY_ADMIN_TOKEN", raising=False)

    with pytest.raises(ValueError, match="admin tokens"):
        Settings(
            _env_file=None,
            database_url="postgresql://test:test@127.0.0.1/test",
        )


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
        assert payload["dependencies"]["checkpointer"]["status"] == "up"
        assert payload["dependencies"]["businessService"]["required"] is True
        assert payload["dependencies"]["businessService"]["status"] == "down"
        assert "test-key" not in str(payload)


def test_validation_rejects_missing_owner_and_unknown_scope(tmp_path: Path):
    with build_client(settings_for(tmp_path)) as client:
        missing = client.post("/agent/messages", json={"message": "你好"})
        assert missing.status_code == 422
        invalid = client.post("/agent/messages", json=message_payload(scopeType="OTHER"))
        assert invalid.status_code == 422


def test_conversation_history_is_filtered_restored_isolated_and_archived(tmp_path: Path):
    settings = settings_for(tmp_path)
    repository = conversation_repository(settings)
    older = repository.create_thread("school-user:1", "SCHOOL", 1)
    repository.append_message(
        older.thread_id, "user", "  第一段   历史问题  ", {"taskType": "CHAT"}
    )
    repository.append_message(older.thread_id, "assistant", "第一段历史回答")
    newer = repository.create_thread("school-user:1", "SCHOOL", 1)
    repository.append_message(
        newer.thread_id, "user", "第二段历史问题", {"taskType": "CHAT"}
    )
    repository.append_message(newer.thread_id, "assistant", "第二段历史回答")
    teaching = repository.create_thread("school-user:1", "SCHOOL", 1)
    repository.append_message(
        teaching.thread_id, "user", "教学方案", {"taskType": "TEACHING_PLAN"}
    )
    foreign = repository.create_thread("school-user:2", "SCHOOL", 1)
    repository.append_message(
        foreign.thread_id, "user", "其他用户问题", {"taskType": "CHAT"}
    )

    with build_client(settings) as client:
        history = client.get(
            "/agent/threads",
            params={"ownerId": "school-user:1", "taskType": "CHAT", "scopeType": "SCHOOL", "scopeId": 1},
        )
        detail = client.get(
            f"/agent/threads/{newer.thread_id}",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        )
        forbidden = client.get(
            f"/agent/threads/{foreign.thread_id}",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        )
        archived = client.post(
            f"/agent/threads/{newer.thread_id}/archive",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        )
        archived_history = client.get(
            "/agent/threads",
            params={
                "ownerId": "school-user:1",
                "taskType": "CHAT",
                "scopeType": "SCHOOL",
                "scopeId": 1,
                "status": "archived",
            },
        )
        archived_detail = client.get(
            f"/agent/threads/{newer.thread_id}",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        )
        archived_message = client.post(
            "/agent/messages",
            json=message_payload(threadId=newer.thread_id, message="归档后不应继续追问"),
        )
        cross_scope_detail = client.get(
            f"/agent/threads/{newer.thread_id}",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 2},
        )
        cross_scope_restore = client.post(
            f"/agent/threads/{newer.thread_id}/restore",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 2},
        )
        restored = client.post(
            f"/agent/threads/{newer.thread_id}/restore",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        )
        continued = client.post(
            "/agent/messages",
            json=message_payload(threadId=newer.thread_id, message="恢复后继续追问"),
        )
        after_archive = client.get(
            "/agent/threads",
            params={"ownerId": "school-user:1", "taskType": "CHAT", "scopeType": "SCHOOL", "scopeId": 1},
        )

    assert history.status_code == 200
    assert [item["threadId"] for item in history.json()] == [newer.thread_id, older.thread_id]
    assert history.json()[1]["title"] == "第一段 历史问题"
    assert history.json()[0]["preview"] == "第二段历史回答"
    assert history.json()[0]["messageCount"] == 2
    assert [message["role"] for message in detail.json()["messages"]] == ["user", "assistant"]
    assert forbidden.status_code == 404
    assert archived.status_code == 200
    assert archived_history.status_code == 200
    assert [item["threadId"] for item in archived_history.json()] == [newer.thread_id]
    assert archived_detail.status_code == 200
    assert archived_detail.json()["status"] == "archived"
    assert archived_message.status_code == 404
    assert cross_scope_detail.status_code == 404
    assert cross_scope_restore.status_code == 404
    assert restored.status_code == 200
    assert restored.json()["status"] == "active"
    assert continued.status_code == 200
    assert continued.json()["threadId"] == newer.thread_id
    assert [item["threadId"] for item in after_archive.json()] == [newer.thread_id, older.thread_id]


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
    assert data["followUpQuestions"]
    assert stored["messages"][1]["metadata"]["followUpQuestions"] == data["followUpQuestions"]
    snapshot = stored["messages"][1]["metadata"]["responseSnapshot"]
    assert snapshot["schemaVersion"] == 1
    assert snapshot["retrievalMethods"] == ["vector+hybrid-rerank"]
    assert snapshot["generationStatus"] == data["generationStatus"]


def test_client_turn_id_is_required_by_fastapi(tmp_path: Path):
    payload = message_payload()
    payload.pop("clientTurnId")

    with build_client(settings_for(tmp_path)) as client:
        response = client.post("/agent/messages", json=payload)

    assert response.status_code == 422
    assert any(
        item["loc"][-1] == "clientTurnId"
        and item["type"] == "missing"
        for item in response.json()["detail"]
    )


def test_client_turn_id_persists_and_recovers_only_within_owner_scope(tmp_path: Path):
    client_turn_id = "turn-recovery-1"
    with build_client(settings_for(tmp_path)) as client:
        response = client.post(
            "/agent/messages",
            json=message_payload(clientTurnId=client_turn_id),
        )
        assert response.status_code == 200
        thread_id = response.json()["threadId"]

        detail = client.get(
            f"/agent/threads/{thread_id}",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        )
        recovered = client.get(
            f"/agent/messages/recovery/{client_turn_id}",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        )
        missing = client.get(
            "/agent/messages/recovery/turn-missing",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        )
        cross_owner = client.get(
            f"/agent/messages/recovery/{client_turn_id}",
            params={"ownerId": "school-user:2", "scopeType": "SCHOOL", "scopeId": 1},
        )
        cross_scope = client.get(
            f"/agent/messages/recovery/{client_turn_id}",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 2},
        )
        completed_cancel = client.post(
            f"/agent/turns/{client_turn_id}/cancel",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        )
        cross_owner_cancel = client.post(
            f"/agent/turns/{client_turn_id}/cancel",
            params={"ownerId": "school-user:2", "scopeType": "SCHOOL", "scopeId": 1},
        )

    assert detail.status_code == 200
    stored_messages = detail.json()["messages"]
    assert stored_messages[0]["metadata"]["clientTurnId"] == client_turn_id
    assert stored_messages[1]["metadata"]["clientTurnId"] == client_turn_id
    assert recovered.status_code == 200
    assert recovered.json()["found"] is True
    assert recovered.json()["threadId"] == thread_id
    assert recovered.json()["message"]["role"] == "assistant"
    assert recovered.json()["message"]["metadata"]["clientTurnId"] == client_turn_id
    assert missing.json() == {
        "found": False,
        "clientTurnId": "turn-missing",
        "threadId": None,
        "message": None,
        "turnStatus": None,
        "retryable": False,
        "partialMessage": None,
    }
    assert cross_owner.json()["found"] is False
    assert cross_scope.json()["found"] is False
    assert completed_cancel.status_code == 200
    assert completed_cancel.json() == {
        "clientTurnId": client_turn_id,
        "threadId": thread_id,
        "turnStatus": "completed",
        "cancellationRequested": False,
    }
    assert cross_owner_cancel.status_code == 404


def test_unknown_greeting_creates_and_persists_thread(tmp_path: Path):
    question = "你好，你可以做什么？"
    with build_client(settings_for(tmp_path)) as client:
        response = client.post("/agent/messages", json=message_payload(message=question))

        assert response.status_code == 200
        data = response.json()
        assert data["threadId"]
        assert data["status"] == "degraded"

        detail = client.get(
            f"/agent/threads/{data['threadId']}",
            params={"ownerId": "school-user:1", "scopeType": "SCHOOL", "scopeId": 1},
        )

    assert detail.status_code == 200
    assert [item["role"] for item in detail.json()["messages"]] == ["user", "assistant"]
    assert detail.json()["messages"][0]["content"] == question
    assert detail.json()["messages"][1]["metadata"]["followUpQuestions"] == data["followUpQuestions"]


def test_follow_up_questions_filter_meta_prompts_and_fill_teacher_tasks():
    follow_ups = AgentRuntime._follow_up_questions(
        [
            "您需要查询哪些本土思政教育资源？",
            "请介绍适合小学生的本土思政教育资源。",
            "您是否需要特定年级的思政教学建议？",
        ],
        ["常安镇敬老院"],
        "请介绍本校周边资源。",
        "小学生",
        "思政教育",
    )

    assert "您需要查询哪些本土思政教育资源？" not in follow_ups
    assert "您是否需要特定年级的思政教学建议？" not in follow_ups
    assert follow_ups[0] == "请介绍适合小学生的本土思政教育资源。"
    assert "请说明“常安镇敬老院”适合哪些年级。" in follow_ups
    assert len(follow_ups) == 4


def test_stateful_stream_emits_events_and_persists_final_response(tmp_path: Path):
    with build_client(settings_for(tmp_path)) as client:
        response = client.post(
            "/agent/messages/stream", json=message_payload(clientTurnId="turn-stream-1")
        )

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
    assert final_data["response"]["followUpQuestions"]
    assert stored["messages"][0]["metadata"]["clientTurnId"] == "turn-stream-1"
    assert stored["messages"][-1]["metadata"]["clientTurnId"] == "turn-stream-1"
    assert stored["messages"][-1]["metadata"]["followUpQuestions"] == final_data["response"]["followUpQuestions"]
    assert stored["messages"][-1]["metadata"]["responseSnapshot"]["schemaVersion"] == 1


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
        assert "event: token" not in plan_response.text
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


def test_incremental_teaching_plan_patches_arrive_before_complete_without_raw_json():
    parser = IncrementalTeachingPlanParser()
    patches = []
    chunks = [
        '{"generationStatus":"succ',
        'ess","theme":"家乡文化","activityFlow":[{"time":"0-20分钟","content":"校内集合',
        '并完成导入"}],"objectives":["认识身边资源"]}',
    ]
    for chunk in chunks:
        patches.extend(parser.feed(chunk))

    assert patches[0] == {"generationStatus": "completed"}
    assert patches[1] == {"theme": "家乡文化"}
    assert patches[2]["activityFlow"] == ["0-20分钟：校内集合并完成导入"]
    assert patches[3] == {"objectives": ["认识身边资源"]}
    assert '"time"' not in str(patches)
    assert "{" not in patches[2]["activityFlow"][0]


def test_runtime_emits_plan_patch_before_final_without_token_fragments(tmp_path: Path):
    class StreamingModel:
        async def stream_json_events(self, _prompt, _trace_context, _validator):
            yield "attempt", {"provider": "test", "model": "qwen-test"}
            yield "token", {"delta": '{"generationStatus":"success","theme":"家乡'}
            yield "token", {
                "delta": '文化","activityFlow":[{"time":"0-20分钟","content":"校内集合"}],'
            }
            yield "token", {"delta": '"objectives":["认识真实资源"]}'}
            yield "complete", {
                "result": {
                    "generationStatus": "success",
                    "theme": "家乡文化",
                    "objectives": ["认识真实资源"],
                    "activityFlow": [{"time": "0-20分钟", "content": "校内集合"}],
                },
                "provider": "test",
                "model": "qwen-test",
            }

    settings = settings_for(tmp_path, llm_model="qwen-test")
    runtime = started_runtime(settings)
    runtime.model = StreamingModel()
    runtime._agent = object()
    request = AgentMessageRequest.model_validate(message_payload(
        taskType="TEACHING_PLAN",
        taskPayload={"theme": "家乡文化", "grade": "四年级"},
        message="请生成教学方案。",
    ))

    async def collect_events():
        return [event async for event in runtime.stream_events(request)]

    events = run_async(collect_events())
    names = [event.splitlines()[0].split(": ", 1)[1] for event in events]
    patch_index = names.index("plan.patch")
    final_index = names.index("final")
    assert patch_index < final_index
    assert "token" not in names
    patch_events = [event for name, event in zip(names, events) if name == "plan.patch"]
    patch_text = "\n".join(patch_events)
    assert "qwen-test" not in patch_text
    assert '"time"' not in patch_text
    assert "0-20分钟：校内集合" in patch_text


def test_teaching_plan_normalizes_success_and_object_activity_flow(tmp_path: Path):
    request = AgentMessageRequest.model_validate(message_payload(
        taskType="TEACHING_PLAN",
        taskPayload={"theme": "家乡文化", "grade": "四年级"},
        message="请生成教学方案。",
    ))
    result = normalize_teaching_plan(
        {
            "generationStatus": "success",
            "theme": "家乡文化",
            "objectives": ["认识身边的真实资源"],
            "activityFlow": [
                {"time": "0-20分钟", "content": "校内集合并完成活动导入"},
                {"time": "20-40分钟", "content": "分组研读资源"},
            ],
        },
        request,
    )

    assert result["generationStatus"] == "completed"
    assert result["activityFlow"] == [
        "0-20分钟：校内集合并完成活动导入",
        "20-40分钟：分组研读资源",
    ]
    stream_text = teaching_plan_stream_text(result)
    assert "活动流程" in stream_text
    assert "0-20分钟：校内集合并完成活动导入" in stream_text
    assert '"time"' not in stream_text
    assert "{" not in stream_text


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
    runtime = started_runtime(settings)

    class FakeAgent:
        def __init__(self, content: str):
            self.content = content

        async def ainvoke(self, _input, config=None):
            return {"messages": [AIMessage(content=self.content)]}

    agent_for = lambda config: {
        "qwen-plus": FakeAgent("阿里云无效响应"),
        "qwen3:8b": FakeAgent('{"answer":"Ollama回答","citationIds":[]}'),
    }[config.model]

    async def create_agent_for(config, _checkpoint_namespace):
        return agent_for(config)

    runtime._create_agent_for = create_agent_for

    response = run_async(
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
    runtime = started_runtime(settings)

    class FakeAgent:
        def __init__(self, content: str):
            self.content = content

        async def ainvoke(self, _input, config=None):
            return {"messages": [AIMessage(content=self.content)]}

    agent_for = lambda config: {
        "qwen-plus": FakeAgent("阿里云无效响应"),
        "qwen3:8b": FakeAgent('{"answer":"Ollama流式回答","citationIds":[]}'),
    }[config.model]

    async def create_agent_for(config, _checkpoint_namespace):
        return agent_for(config)

    runtime._create_agent_for = create_agent_for

    async def collect_events():
        return [event async for event in runtime.stream_events(
            AgentMessageRequest.model_validate(
                message_payload(message="流式主模型失败后继续", conversationId="fallback-stream")
            )
        )]

    events = run_async(collect_events())
    names = [event.split("\n", 1)[0].removeprefix("event: ") for event in events]
    assert names.count("model.started") == 2
    assert "model.failed" in names
    assert "model.completed" in names
    final_block = next(event for event in events if event.startswith("event: final"))
    final_data = json.loads(final_block.split("data: ", 1)[1])
    assert final_data["response"]["provider"] == "ollama"
    assert final_data["response"]["fallbackLevel"] == 1


def test_stateful_stream_deduplicates_cumulative_langgraph_messages_before_final(tmp_path: Path):
    settings = settings_for(
        tmp_path,
        primary_provider="test",
        primary_model="stream-model",
        primary_base_url="http://test.invalid/v1",
        primary_api_key="stream-key",
    )
    runtime = started_runtime(settings)

    class StreamingAgent:
        async def astream(self, _input, config=None, stream_mode=None, version=None):
            for content in (
                '{"answer":"第一',
                '{"answer":"第一段',
                '{"answer":"第一段回答","citationIds":[]}',
            ):
                yield {"data": (AIMessageChunk(content=content), {"langgraph_node": "agent"})}

    async def create_agent_for(_config, _checkpoint_namespace):
        return StreamingAgent()

    runtime._create_agent_for = create_agent_for

    async def collect_events():
        return [event async for event in runtime.stream_events(
            AgentMessageRequest.model_validate(message_payload(message="测试累计分片"))
        )]

    events = run_async(collect_events())
    token_values = [
        json.loads(event.split("data: ", 1)[1])["delta"]
        for event in events if event.startswith("event: token")
    ]
    final_event = next(event for event in events if event.startswith("event: final"))
    final_data = json.loads(final_event.split("data: ", 1)[1])

    assert token_values == ["第一", "段", "回答"]
    assert "event: final" in events[-2]
    assert final_data["response"]["answer"] == "第一段回答"


def test_stream_merge_helpers_accept_delta_and_cumulative_provider_shapes():
    previous, delta = ModelGateway._merge_stream_text("", "你好")
    assert (previous, delta) == ("你好", "你好")
    previous, delta = ModelGateway._merge_stream_text(previous, "你好，老师")
    assert (previous, delta) == ("你好，老师", "，老师")
    previous, delta = AgentRuntime._merge_stream_text(previous, "继续说明")
    assert (previous, delta) == ("你好，老师继续说明", "继续说明")


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
    repository = conversation_repository(settings_for(tmp_path))
    thread = repository.create_thread("owner", "SCHOOL", 1)
    context = TrustedContext(resources=[
        {"resource": {"resourceName": "甲纪念馆"}},
        {"resource": {"resourceName": "乙文化站"}},
    ])
    runtime = ToolRuntimeContext(
        thread.thread_id, context, repository.async_target, 2000
    )
    token = bind_tool_runtime(runtime)
    try:
        output = run_async(
            search_approved_resources.ainvoke({"query": "纪念馆", "limit": 8})
        )
    finally:
        reset_tool_runtime(token)
    assert "甲纪念馆" in output
    assert "乙文化站" not in output
    assert runtime.executions[0].name == "search_approved_resources"
    assert runtime.event_sink is None
    assert len(repository.list_tool_audits()) == 1


def test_tool_runtime_emits_started_and_completed_events(tmp_path: Path):
    repository = conversation_repository(settings_for(tmp_path))
    thread = repository.create_thread("owner", "SCHOOL", 1)
    context = TrustedContext(resources=[{"resource": {"resourceName": "甲纪念馆"}}])
    events = []
    runtime = ToolRuntimeContext(
        thread.thread_id,
        context,
        repository.async_target,
        2000,
        event_sink=lambda name, data: events.append((name, data)),
    )
    token = bind_tool_runtime(runtime)
    try:
        run_async(
            search_approved_resources.ainvoke({"query": "纪念馆", "limit": 1})
        )
    finally:
        reset_tool_runtime(token)

    assert [name for name, _ in events] == ["tool.started", "tool.completed"]
    assert events[0][1]["toolName"] == "search_approved_resources"
    assert events[1][1]["status"] == "ok"


def test_model_output_filters_invented_citations(tmp_path: Path):
    runtime = started_runtime(settings_for(tmp_path))

    class FakeAgent:
        async def ainvoke(self, _input, config=None):
            return {"messages": [AIMessage(content=(
                '{"answer":"基于可信资料回答。","citationIds":["chunk:1","invented:9"],'
                '"relatedResources":["红色纪念馆"],"followUpQuestions":[]}'
            ))]}

    runtime._agent = FakeAgent()
    response = run_async(
        runtime.handle(AgentMessageRequest.model_validate(message_payload()))
    )
    assert response.status == "completed"
    assert [item.citation_id for item in response.citations] == ["chunk:1"]


def test_prefetched_context_uses_joint_rank_and_caps_graph_facts(tmp_path: Path):
    runtime = started_runtime(settings_for(tmp_path))
    chunks = [
        {"citationId": f"chunk:{index}", "text": f"文本证据{index}"}
        for index in range(1, 8)
    ]
    graph_facts = [
        {"citationId": f"graph:{index}", "text": f"图谱证据{index}"}
        for index in range(1, 5)
    ]
    candidates = [
        {
            "citationId": f"graph:{index}",
            "evidenceType": "graph_fact",
            "rank": index,
            "score": 1 - index / 100,
        }
        for index in range(1, 5)
    ] + [
        {
            "citationId": f"chunk:{index}",
            "evidenceType": "chunk",
            "rank": index + 4,
            "score": 0.8 - index / 100,
        }
        for index in range(1, 8)
    ]
    trusted = TrustedContext.model_validate({
        "retrieval": {
            "retrievalStatus": "ok",
            "chunks": chunks,
            "graphFacts": graph_facts,
        },
        "citationCandidates": candidates,
    })

    prompt = runtime._prefetched_evidence_message(trusted)

    assert prompt.count('"evidenceType": "graph_fact"') == 3
    assert '"citationId": "graph:4"' not in prompt
    assert '"citationId": "chunk:5"' in prompt
    assert '"citationId": "chunk:6"' not in prompt


def test_retrieval_trace_summary_is_bounded_and_drops_feature_details(tmp_path: Path):
    runtime = started_runtime(settings_for(tmp_path))
    trusted = TrustedContext.model_validate({
        "retrieval": {
            "retrievalTrace": {
                "retrievalStatus": "degraded",
                "intent": "NEARBY_RESOURCE",
                "needGraph": True,
                "graphStatus": "failed",
                "denseCandidateCount": 8,
                "lexicalCandidateCount": 10,
                "rrfCandidateCount": 12,
                "graphCandidateCount": 0,
                "rerankedCandidateCount": 12,
                "retrievalMethods": ["dense", "lexical", "rrf", "heuristic-rerank"],
                "topCandidates": [
                    {
                        "citationId": f"chunk:{index}",
                        "rank": index,
                        "score": 1 - index / 100,
                        "evidenceType": "chunk",
                        "retrievalMethod": "hybrid-rrf+heuristic-rerank",
                        "contributions": {"base": 0.6},
                    }
                    for index in range(1, 11)
                ],
            }
        }
    })

    summary = runtime._retrieval_trace_summary(trusted)

    assert summary["graphStatus"] == "failed"
    assert len(summary["topCandidates"]) == 8
    assert "contributions" not in summary["topCandidates"][0]


def test_grounded_response_persists_deterministic_sanitized_snapshot(tmp_path: Path):
    settings = settings_for(tmp_path)
    repository = conversation_repository(settings)
    runtime = AgentRuntime(settings, repository.async_target)
    thread = repository.create_thread("school-user:1", "SCHOOL", 1)
    trusted = TrustedContext.model_validate({
        "retrieval": {
            "retrievalStatus": "degraded",
            "chunks": [{
                "citationId": "chunk:1",
                "title": "关键词资料",
                "text": "可信的关键词资料",
                "retrievalMethod": "keyword-fallback",
            }],
            "graphFacts": [{"citationId": "graph:1", "text": "可信图谱事实"}],
        },
        "citationCandidates": [{
            "citationId": "source:1",
            "title": "审核来源",
            "excerpt": "审核摘要",
            "sourceType": "approved_source",
        }],
    })
    result = run_async(runtime._response_from_model_result(
        {"messages": [AIMessage(content=json.dumps({
            "answer": "基于可信证据回答。",
            "citationIds": [],
            "relatedResources": ["常安镇敬老院"],
            "followUpQuestions": ["还能如何开展活动？"],
        }, ensure_ascii=False))]},
        trusted,
        thread.thread_id,
        True,
        [ToolExecution(name="get_school_context", status="completed", durationMs=7)],
    ))
    result.provider = "openai-compatible"
    result.model = "qwen-test"
    result.fallback_level = 0
    result.memory_applied = MemoryApplied(count=2, memoryIds=["profile-1", "task-1"])

    run_async(runtime._persist_response(thread, result))
    stored = repository.list_messages(thread.thread_id)[-1]
    snapshot = stored["metadata"]["responseSnapshot"]

    assert [item.citation_id for item in result.citations] == ["chunk:1", "graph:1", "source:1"]
    assert result.retrieval_methods == ["keyword-fallback", "knowledge-graph"]
    assert snapshot == {
        "schemaVersion": 1,
        "status": "completed",
        "generationStatus": "completed",
        "retrievalStatus": "degraded",
        "retrievalMethods": ["keyword-fallback", "knowledge-graph"],
        "citations": [item.model_dump(by_alias=True) for item in result.citations],
        "relatedResources": ["常安镇敬老院"],
        "followUpQuestions": result.follow_up_questions,
        "provider": "openai-compatible",
        "model": "qwen-test",
        "fallbackLevel": 0,
        "toolExecutions": [{"name": "get_school_context", "status": "completed", "durationMs": 7}],
        "contextCompacted": True,
        "memoryApplied": {"count": 2, "memoryIds": ["profile-1", "task-1"]},
    }
    serialized = json.dumps(snapshot, ensure_ascii=False)
    assert "outputSummary" not in serialized
    assert "traceEvents" not in serialized
    assert "正在" not in serialized


def test_agent_prompt_allows_markdown_answer_without_changing_json_contract(tmp_path: Path):
    prompt = (Path(__file__).parent / "prompts" / "agent" / "v1" / "system.md").read_text(encoding="utf-8")
    assert "answer 字段允许使用 Markdown" in prompt
    assert "不要使用 HTML" in prompt

    runtime = started_runtime(settings_for(tmp_path))
    markdown_answer = "### 资源建议\n\n**重点：** 先确认开放状态。\n\n1. 课堂导入。\n2. 现场观察。"

    class FakeAgent:
        async def ainvoke(self, _input, config=None):
            return {"messages": [AIMessage(content=json.dumps({
                "answer": markdown_answer,
                "citationIds": [],
                "relatedResources": [],
                "followUpQuestions": [],
            }, ensure_ascii=False))]}

    runtime._agent = FakeAgent()
    response = run_async(
        runtime.handle(AgentMessageRequest.model_validate(message_payload()))
    )

    assert response.status == "completed"
    assert response.answer == markdown_answer


def test_degraded_answer_uses_markdown_sections_and_lists(tmp_path: Path):
    runtime = started_runtime(settings_for(tmp_path))
    request = AgentMessageRequest.model_validate(message_payload(message="请介绍周边资源"))
    trusted = TrustedContext(
        school={"schoolName": "里庄小学"},
        resources=[
            {"resource": {"resourceName": "甲纪念馆"}},
            {"resource": {"resourceName": "乙文化站"}},
        ],
    )

    response = runtime._degraded_answer(request, trusted, "thread-1", False)

    assert response.answer.startswith("**当前模型不可用**")
    assert "\n- 甲纪念馆" in response.answer
    assert "\n1. 资源开放状态。" in response.answer


def test_stream_prefetches_graph_tool_for_trusted_scope(tmp_path: Path):
    settings = settings_for(tmp_path)
    runtime = started_runtime(settings)
    calls = []

    class FakeBusinessToolClient:
        async def query_graph_relations(self, payload):
            calls.append(payload)
            return {
                "retrievalStatus": "ok",
                "graphFacts": [{"citationId": "graph:1", "text": "人物关联学校"}],
                "citationCandidates": [{"citationId": "graph:1", "title": "图谱关系"}],
            }

    class FakeAgent:
        async def ainvoke(self, _input, config=None):
            return {
                "messages": [AIMessage(content=(
                    '{"answer":"基于图谱关系回答。","citationIds":["graph:1"],'
                    '"relatedResources":[],"followUpQuestions":[]}'
                ))]
            }

    runtime.business_tool_client = FakeBusinessToolClient()
    runtime._agent = FakeAgent()
    request = AgentMessageRequest.model_validate(message_payload(
        message="李大钊与学校有什么关系？",
        context={
            "actor": {"accountId": 1, "roleCode": "school_admin", "schoolId": 1},
            "scope": {"scopeType": "SCHOOL", "scopeId": 1},
        },
    ))

    async def collect_events():
        return [event async for event in runtime.stream_events(request)]

    events = run_async(collect_events())
    names = [event.split("\n", 1)[0].removeprefix("event: ") for event in events]
    assert "tool.started" in names
    assert "tool.completed" in names
    assert calls[0]["actor"]["schoolId"] == 1
    assert calls[0]["scope"]["scopeId"] == 1
    final_block = next(event for event in events if event.startswith("event: final"))
    final_data = json.loads(final_block.split("data: ", 1)[1])
    final_response = final_data["response"]
    assert final_response["retrievalStatus"] == "ok"
    assert final_response["toolExecutions"][0]["name"] == "query_graph_relations"
    assert final_response["citations"][0]["citationId"] == "graph:1"


@pytest.mark.parametrize(
    ("content", "expected"),
    [
        (
            "说明如下：\n```json\n"
            '{"answer":"带包装的回答","citationIds":[],"relatedResources":[],"followUpQuestions":[]}'
            "\n```",
            "带包装的回答",
        ),
        ("这是一段没有 JSON 包装的有效回答。", "这是一段没有 JSON 包装的有效回答。"),
    ],
)
def test_model_output_accepts_wrapped_json_and_plain_text(
    tmp_path: Path, content: str, expected: str
):
    runtime = started_runtime(settings_for(tmp_path))

    class FakeAgent:
        async def ainvoke(self, _input, config=None):
            return {"messages": [AIMessage(content=content)]}

    runtime._agent = FakeAgent()
    response = run_async(
        runtime.handle(AgentMessageRequest.model_validate(message_payload()))
    )

    assert response.status == "completed"
    assert response.answer == expected
