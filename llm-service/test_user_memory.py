from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
import pytest

from llm_service.api import create_app
from llm_service.runtime import AgentRuntime
from llm_service.schemas import AgentMessageRequest
from llm_service.settings import Settings
from llm_service.user_memory import (
    MemoryContentPolicy,
    MemoryConflictError,
    MemoryNotFoundError,
    MemoryStateError,
    MemoryValidationError,
)
from postgres_test_support import (
    conversation_repository,
    memory_repository,
    run_async,
    settings_for_database,
)


OWNER = "account:teacher-1"
SCOPE_TYPE = "SCHOOL"
SCOPE_ID = "school-1"


class MutableClock:
    def __init__(self) -> None:
        self.value = datetime(2026, 7, 31, 8, 0, tzinfo=timezone.utc)

    def now(self) -> datetime:
        return self.value

    def advance(self, **kwargs: int) -> None:
        self.value += timedelta(**kwargs)


def repository_for(tmp_path: Path, clock: MutableClock | None = None):
    return memory_repository(
        settings_for_database(tmp_path),
        now_provider=clock.now if clock else None,
    )


def api_settings(tmp_path: Path, *, memory_available: bool = True) -> Settings:
    return settings_for_database(
        tmp_path,
        internal_service_token="memory-internal-token",
        observability_admin_token="memory-admin-token",
        internal_business_base_url="",
        business_health_required=False,
        llm_api_url="",
        llm_api_key="",
        agent_memory_enabled=memory_available,
    )


def runtime_for(settings: Settings):
    conversations = conversation_repository(settings)
    memories = memory_repository(settings)
    runtime = AgentRuntime(
        settings,
        conversations.async_target,
        memory_repository=memories.async_target,
    )
    return runtime, memories


def memory_client(settings: Settings, *, authenticated: bool = True) -> TestClient:
    headers = (
        {"X-Agent-Service-Token": settings.internal_service_token}
        if authenticated
        else {}
    )
    return TestClient(create_app(settings), headers=headers)


def memory_scope(**overrides: str) -> dict[str, str]:
    scope = {
        "ownerId": OWNER,
        "scopeType": SCOPE_TYPE,
        "scopeId": SCOPE_ID,
    }
    scope.update(overrides)
    return scope


def agent_request(message: str, **overrides: object) -> AgentMessageRequest:
    payload: dict[str, object] = {
        **memory_scope(),
        "message": message,
        "context": {},
    }
    payload.update(overrides)
    return AgentMessageRequest.model_validate(payload)


class CapturingAgent:
    def __init__(self, payload: dict[str, object]):
        self.payload = payload
        self.calls: list[list[object]] = []

    async def ainvoke(self, request: dict[str, object], config: dict[str, object]):
        del config
        self.calls.append(list(request["messages"]))
        return {
            "messages": [
                AIMessage(content=json_dumps(self.payload)),
            ]
        }


def json_dumps(value: object) -> str:
    import json

    return json.dumps(value, ensure_ascii=False)


def test_memory_setting_defaults_off_and_is_scope_isolated(tmp_path: Path) -> None:
    repository = repository_for(tmp_path)

    default_setting = repository.get_setting(OWNER, SCOPE_TYPE, SCOPE_ID)
    assert default_setting.enabled is False

    enabled = repository.update_setting(OWNER, SCOPE_TYPE, SCOPE_ID, True)
    assert enabled.enabled is True
    assert repository.get_setting(OWNER, SCOPE_TYPE, SCOPE_ID).enabled is True
    assert repository.get_setting(OWNER, SCOPE_TYPE, "school-2").enabled is False
    assert repository.get_setting("account:teacher-2", SCOPE_TYPE, SCOPE_ID).enabled is False


def test_scoped_crud_hides_memory_existence_from_other_owner_or_school(tmp_path: Path) -> None:
    repository = repository_for(tmp_path)
    memory = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        field_key="grade",
        content="常教四年级",
        source="profile_ui",
    )

    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, memory.id).content == "常教四年级"
    assert [item.id for item in repository.list_memories(OWNER, SCOPE_TYPE, SCOPE_ID)] == [memory.id]
    assert repository.list_memories(OWNER, SCOPE_TYPE, "school-2") == []

    with pytest.raises(MemoryNotFoundError):
        repository.get_memory("account:teacher-2", SCOPE_TYPE, SCOPE_ID, memory.id)
    with pytest.raises(MemoryNotFoundError):
        repository.update_memory(OWNER, SCOPE_TYPE, "school-2", memory.id, content="越权修改")


def test_pending_confirm_delete_restore_and_permanent_delete_state_machine(tmp_path: Path) -> None:
    repository = repository_for(tmp_path)
    pending = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="TASK",
        content="本学期准备红色文化项目课",
        status="pending",
        source="inferred_chat",
        source_thread_id="thread-1",
        confidence=0.82,
    )

    assert pending.status == "pending"
    assert pending.expires_at is not None
    active = repository.confirm_memory(OWNER, SCOPE_TYPE, SCOPE_ID, pending.id)
    assert active.status == "active"
    assert active.expires_at is not None

    with pytest.raises(MemoryStateError):
        repository.confirm_memory(OWNER, SCOPE_TYPE, SCOPE_ID, pending.id)

    deleted = repository.delete_memory(OWNER, SCOPE_TYPE, SCOPE_ID, pending.id)
    assert deleted.status == "deleted"
    assert deleted.deleted_at is not None
    assert deleted.purge_after is not None
    assert repository.list_memories(OWNER, SCOPE_TYPE, SCOPE_ID, status="active") == []

    restored = repository.restore_memory(OWNER, SCOPE_TYPE, SCOPE_ID, pending.id)
    assert restored.status == "active"
    assert restored.deleted_at is None
    assert restored.purge_after is None

    repository.delete_memory(OWNER, SCOPE_TYPE, SCOPE_ID, pending.id)
    repository.permanent_delete(OWNER, SCOPE_TYPE, SCOPE_ID, pending.id)
    with pytest.raises(MemoryNotFoundError):
        repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, pending.id)


@pytest.mark.parametrize(
    "content",
    [
        "记住我的密码是 Test123456",
        "API token: sk-proj-" + "abcdefghijklmnopqrstuvwxyz",
        "身份证号 130102199001011234",
        "我的电话是 13800138000",
        "家庭住址是河北省邯郸市丛台区人民路 18 号 2 单元 301 室",
    ],
)
def test_sensitive_content_is_rejected_before_persistence(tmp_path: Path, content: str) -> None:
    repository = repository_for(tmp_path)

    with pytest.raises(MemoryValidationError, match="敏感"):
        repository.create_memory(
            OWNER,
            SCOPE_TYPE,
            SCOPE_ID,
            memory_type="PROFILE",
            content=content,
            source="profile_ui",
        )

    assert repository.list_memories(OWNER, SCOPE_TYPE, SCOPE_ID) == []


def test_content_policy_normalizes_safe_text_and_limits_length() -> None:
    policy = MemoryContentPolicy(max_characters=20)

    assert policy.validate("  常教   四年级 \n 项目式教学 ") == "常教 四年级 项目式教学"
    with pytest.raises(MemoryValidationError, match="长度"):
        policy.validate("这是一条明显超过二十个字符限制的长期记忆内容，不能保存")


def test_core_profile_conflict_requires_explicit_replacement_and_normalizes_aliases(
    tmp_path: Path,
) -> None:
    repository = repository_for(tmp_path)
    original = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        field_key="response_format",
        content="28岁女教师",
        source="profile_ui",
    )
    # 模拟从旧库导入的历史别名，读取和比对时仍须等价于 answer_format。
    async def write_legacy_alias() -> None:
        async with repository.database.transaction() as connection:
            await connection.execute(
                "UPDATE agent_memory SET field_key = 'response_format' WHERE id = %s",
                (original.id,),
            )

    run_async(write_legacy_alias())

    candidate = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        field_key="answer_format",
        content="26岁男教师",
        source="inferred_chat",
        status="pending",
    )

    preview = repository.confirmation_preview(OWNER, SCOPE_TYPE, SCOPE_ID, candidate.id)
    assert preview.duplicate is False
    assert [(item.id, item.field_key, item.content) for item in preview.conflicts] == [
        (original.id, "answer_format", "28岁女教师")
    ]

    with pytest.raises(MemoryConflictError):
        repository.confirm_memory(OWNER, SCOPE_TYPE, SCOPE_ID, candidate.id)

    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, original.id).status == "active"
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, candidate.id).status == "pending"

    replacement = repository.confirm_memory(
        OWNER, SCOPE_TYPE, SCOPE_ID, candidate.id, replace_conflicts=True
    )
    assert replacement.id == candidate.id
    assert replacement.status == "active"
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, original.id).status == "deleted"
    active_core = repository.list_memories(OWNER, SCOPE_TYPE, SCOPE_ID, status="active")
    assert [(item.field_key, item.content) for item in active_core] == [
        ("answer_format", "26岁男教师")
    ]

    custom = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        content="回答时优先给出可直接复制的步骤",
        source="profile_ui",
    )
    duplicate = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        content="回答时优先给出可直接复制的步骤",
        source="profile_ui",
    )
    assert duplicate.id == custom.id


def test_duplicate_candidate_is_recycled_without_creating_another_active_memory(
    tmp_path: Path,
) -> None:
    repository = repository_for(tmp_path)
    original = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        field_key="grade",
        content="常教三年级",
        source="profile_ui",
    )
    candidate = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        field_key="grade",
        content="常教三年级",
        status="pending",
        source="inferred_chat",
    )

    preview = repository.confirmation_preview(OWNER, SCOPE_TYPE, SCOPE_ID, candidate.id)
    assert preview.duplicate is True
    assert [item.id for item in preview.conflicts] == [original.id]

    recycled = repository.confirm_memory(OWNER, SCOPE_TYPE, SCOPE_ID, candidate.id)
    assert recycled.status == "deleted"
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, original.id).status == "active"
    assert [item.id for item in repository.list_memories(OWNER, SCOPE_TYPE, SCOPE_ID, status="active")] == [original.id]


def test_create_restore_and_update_never_silently_overwrite_field_conflicts(tmp_path: Path) -> None:
    repository = repository_for(tmp_path)
    original = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        field_key="grade",
        content="常教四年级",
        source="profile_ui",
    )

    with pytest.raises(MemoryConflictError):
        repository.create_memory(
            OWNER,
            SCOPE_TYPE,
            SCOPE_ID,
            memory_type="PROFILE",
            field_key="grade",
            content="常教五年级",
            source="profile_ui",
        )
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, original.id).status == "active"

    replacement = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        field_key="grade",
        content="常教五年级",
        source="profile_ui",
        replace_conflicts=True,
    )
    assert replacement.status == "active"
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, original.id).status == "deleted"

    with pytest.raises(MemoryConflictError):
        repository.restore_memory(OWNER, SCOPE_TYPE, SCOPE_ID, original.id)
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, original.id).status == "deleted"

    restored = repository.restore_memory(
        OWNER, SCOPE_TYPE, SCOPE_ID, original.id, replace_conflicts=True
    )
    assert restored.status == "active"
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, replacement.id).status == "deleted"

    second = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        content="主要教数学",
        source="profile_ui",
    )
    with pytest.raises(MemoryConflictError):
        repository.update_memory(
            OWNER, SCOPE_TYPE, SCOPE_ID, second.id, field_key="grade"
        )
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, second.id).field_key is None


def test_cleanup_applies_pending_task_and_recycle_bin_lifetimes(tmp_path: Path) -> None:
    clock = MutableClock()
    repository = repository_for(tmp_path, clock)
    pending = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        content="可能偏好表格回答",
        status="pending",
        source="inferred_chat",
    )
    task = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="TASK",
        content="本学期完成红色文化项目",
        source="profile_ui",
    )
    deleted = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        content="偏好先给结论",
        source="profile_ui",
    )
    repository.delete_memory(OWNER, SCOPE_TYPE, SCOPE_ID, deleted.id)

    clock.advance(days=8)
    first_cleanup = repository.cleanup_expired()
    assert first_cleanup["pending"] == 1
    with pytest.raises(MemoryNotFoundError):
        repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, pending.id)
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, task.id).status == "active"

    clock.advance(days=23)
    second_cleanup = repository.cleanup_expired()
    assert second_cleanup["deleted"] == 1
    with pytest.raises(MemoryNotFoundError):
        repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, deleted.id)

    clock.advance(days=60)
    third_cleanup = repository.cleanup_expired()
    assert third_cleanup["task"] == 1
    with pytest.raises(MemoryNotFoundError):
        repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, task.id)


def test_audit_table_has_no_content_column_or_memory_body(tmp_path: Path) -> None:
    repository = repository_for(tmp_path)
    memory = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        content="只在回答末尾给出延伸问题",
        source="profile_ui",
    )
    repository.update_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory.id,
        content="回答末尾不需要延伸问题",
    )

    async def inspect_audit_table():
        async with repository.database.connection() as connection:
            column_rows = await (
                await connection.execute(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'agent_memory_audit'
                    """
                )
            ).fetchall()
            audit_rows = await (
                await connection.execute(
                    """
                    SELECT event_type, metadata_json
                    FROM agent_memory_audit
                    ORDER BY id
                    """
                )
            ).fetchall()
        return {row["column_name"] for row in column_rows}, audit_rows

    columns, audit_rows = run_async(inspect_audit_table())

    assert "content" not in columns
    assert [row["event_type"] for row in audit_rows] == ["created", "updated"]
    assert all(
        "延伸问题" not in json.dumps(row["metadata_json"], ensure_ascii=False)
        for row in audit_rows
    )


def test_memory_api_requires_internal_token_and_reports_global_availability(
    tmp_path: Path,
) -> None:
    settings = api_settings(tmp_path, memory_available=False)
    with memory_client(settings, authenticated=False) as anonymous:
        unauthorized = anonymous.get("/agent/memory-settings", params=memory_scope())
    assert unauthorized.status_code == 401

    with memory_client(settings) as client:
        initial = client.get("/agent/memory-settings", params=memory_scope())
        enabled = client.put(
            "/agent/memory-settings",
            json={**memory_scope(), "enabled": True},
        )
        reread = client.get("/agent/memory-settings", params=memory_scope())

    assert initial.status_code == 200
    assert initial.json()["available"] is False
    assert initial.json()["enabled"] is False
    assert initial.json()["effectiveEnabled"] is False
    assert enabled.status_code == 200
    assert enabled.json()["enabled"] is True
    assert enabled.json()["effectiveEnabled"] is False
    assert reread.json()["enabled"] is True


def test_memory_api_crud_confirm_recycle_restore_and_permanent_delete(
    tmp_path: Path,
) -> None:
    settings = api_settings(tmp_path)
    with memory_client(settings) as client:
        client.put("/agent/memory-settings", json={**memory_scope(), "enabled": True})
        created = client.post(
            "/agent/memories",
            json={
                **memory_scope(),
                "memoryType": "PROFILE",
                "fieldKey": "grade",
                "content": "常教四年级",
                "source": "profile_ui",
            },
        )
        memory_id = created.json()["id"]
        active = client.get(
            "/agent/memories",
            params={**memory_scope(), "status": "active"},
        )
        updated = client.patch(
            f"/agent/memories/{memory_id}",
            params=memory_scope(),
            json={"content": "常教五年级"},
        )
        deleted = client.delete(
            f"/agent/memories/{memory_id}",
            params=memory_scope(),
        )
        recycled = client.get(
            "/agent/memories",
            params={**memory_scope(), "status": "deleted"},
        )
        restored = client.post(
            f"/agent/memories/{memory_id}/restore",
            params=memory_scope(),
        )

        pending = client.post(
            "/agent/memories",
            json={
                **memory_scope(),
                "memoryType": "TASK",
                "content": "本学期准备项目式学习",
                "status": "pending",
                "source": "inferred_chat",
                "confidence": 0.8,
            },
        )
        confirmed = client.post(
            f"/agent/memories/{pending.json()['id']}/confirm",
            params=memory_scope(),
        )

        client.delete(f"/agent/memories/{memory_id}", params=memory_scope())
        permanent = client.delete(
            f"/agent/memories/{memory_id}/permanent",
            params=memory_scope(),
        )
        missing = client.patch(
            f"/agent/memories/{memory_id}",
            params=memory_scope(),
            json={"content": "不应再出现"},
        )

    assert created.status_code == 201
    assert active.status_code == 200
    assert [item["id"] for item in active.json()] == [memory_id]
    assert updated.json()["content"] == "常教五年级"
    assert deleted.json()["status"] == "deleted"
    assert [item["id"] for item in recycled.json()] == [memory_id]
    assert restored.json()["status"] == "active"
    assert confirmed.status_code == 200
    assert confirmed.json()["status"] == "active"
    assert permanent.status_code == 204
    assert missing.status_code == 404


def test_memory_api_preview_blocks_conflicts_until_explicit_replacement(
    tmp_path: Path,
) -> None:
    settings = api_settings(tmp_path)
    with memory_client(settings) as client:
        original = client.post(
            "/agent/memories",
            json={
                **memory_scope(),
                "memoryType": "PROFILE",
                "fieldKey": "teacher_gender_age",
                "content": "28岁女教师",
                "source": "profile_ui",
            },
        )
        candidate = client.post(
            "/agent/memories",
            json={
                **memory_scope(),
                "memoryType": "PROFILE",
                "fieldKey": "teacher_gender_age",
                "content": "26岁男教师",
                "status": "pending",
                "source": "inferred_chat",
            },
        )
        candidate_id = candidate.json()["id"]
        preview = client.get(
            f"/agent/memories/{candidate_id}/confirmation-preview",
            params=memory_scope(),
        )
        blocked = client.post(
            f"/agent/memories/{candidate_id}/confirm",
            params=memory_scope(),
        )
        resolved = client.post(
            f"/agent/memories/{candidate_id}/confirm",
            params=memory_scope(),
            json={"replaceConflicts": True},
        )

    assert preview.status_code == 200
    assert preview.json()["candidate"]["id"] == candidate_id
    assert preview.json()["duplicate"] is False
    assert [item["content"] for item in preview.json()["conflicts"]] == ["28岁女教师"]
    assert blocked.status_code == 409
    assert original.status_code == 201
    assert resolved.status_code == 200
    assert resolved.json()["status"] == "active"

    repository = repository_for(tmp_path)
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, original.json()["id"]).status == "deleted"
    assert repository.get_memory(OWNER, SCOPE_TYPE, SCOPE_ID, candidate_id).status == "active"


def test_memory_api_enforces_owner_school_isolation_and_sensitive_validation(
    tmp_path: Path,
) -> None:
    settings = api_settings(tmp_path)
    with memory_client(settings) as client:
        created = client.post(
            "/agent/memories",
            json={
                **memory_scope(),
                "memoryType": "PROFILE",
                "content": "偏好先给结论",
                "source": "profile_ui",
            },
        )
        memory_id = created.json()["id"]
        other_owner = client.get(
            "/agent/memories",
            params=memory_scope(ownerId="account:teacher-2"),
        )
        other_school = client.patch(
            f"/agent/memories/{memory_id}",
            params=memory_scope(scopeId="school-2"),
            json={"content": "越权内容"},
        )
        sensitive = client.post(
            "/agent/memories",
            json={
                **memory_scope(),
                "memoryType": "PROFILE",
                "content": "我的电话是 13800138000",
                "source": "profile_ui",
            },
        )

    assert other_owner.status_code == 200
    assert other_owner.json() == []
    assert other_school.status_code == 404
    assert sensitive.status_code == 422
    assert "13800138000" not in sensitive.text


def test_admin_memory_metrics_are_aggregate_only(tmp_path: Path) -> None:
    settings = api_settings(tmp_path)
    with memory_client(settings) as client:
        client.post(
            "/agent/memories",
            json={
                **memory_scope(),
                "memoryType": "PROFILE",
                "content": "回答末尾给出检查清单",
                "source": "profile_ui",
            },
        )
        denied = client.get("/admin/memory-metrics")
        metrics = client.get(
            "/admin/memory-metrics",
            headers={"X-Observability-Admin-Token": "memory-admin-token"},
        )

    assert denied.status_code == 401
    assert metrics.status_code == 200
    payload = metrics.json()
    assert payload["memories"]["total"] == 1
    assert payload["memories"]["byStatus"]["active"] == 1
    assert "检查清单" not in metrics.text
    assert "content" not in metrics.text.lower()


def test_fastapi_lifespan_cleans_expired_memory_and_runs_daily_worker(
    tmp_path: Path,
) -> None:
    settings = api_settings(tmp_path)
    repository = repository_for(tmp_path)
    expired = repository.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        content="待确认但已经过期的偏好",
        status="pending",
        source="inferred_chat",
    )
    async def expire_memory() -> None:
        async with repository.database.transaction() as connection:
            await connection.execute(
                "UPDATE agent_memory SET expires_at = %s WHERE id = %s",
                (datetime(2000, 1, 1, tzinfo=timezone.utc), expired.id),
            )

    run_async(expire_memory())
    application = create_app(settings)

    with TestClient(
        application,
        headers={"X-Agent-Service-Token": settings.internal_service_token},
    ):
        cleanup_task = application.state.memory_cleanup_task
        assert cleanup_task.done() is False
        assert repository.list_memories(OWNER, SCOPE_TYPE, SCOPE_ID) == []

    assert cleanup_task.cancelled()


def test_explicit_remember_is_saved_even_when_model_is_degraded(tmp_path: Path) -> None:
    settings = api_settings(tmp_path)
    runtime, memories = runtime_for(settings)
    memories.update_setting(OWNER, SCOPE_TYPE, SCOPE_ID, True)

    response = run_async(runtime.handle(agent_request("请记住我通常教四年级")))

    assert response.generation_status == "degraded"
    active = memories.list_memories(OWNER, SCOPE_TYPE, SCOPE_ID, status="active")
    assert len(active) == 1
    assert active[0].source == "explicit_chat"
    assert active[0].field_key == "grade"
    assert "四年级" in active[0].content
    assert memories.list_memories(OWNER, SCOPE_TYPE, SCOPE_ID, status="pending") == []


def test_inferred_candidates_are_filtered_capped_and_pending_until_confirmation(
    tmp_path: Path,
) -> None:
    settings = api_settings(tmp_path)
    runtime, memories = runtime_for(settings)
    memories.update_setting(OWNER, SCOPE_TYPE, SCOPE_ID, True)
    agent = CapturingAgent(
        {
            "answer": "可以采用项目式教学。",
            "citationIds": [],
            "memoryCandidates": [
                {
                    "memoryType": "PROFILE",
                    "content": "联系电话是 13800138000",
                    "confidence": 0.99,
                },
                {
                    "memoryType": "PROFILE",
                    "fieldKey": "teaching_style",
                    "content": "偏好项目式教学",
                    "confidence": 0.88,
                },
                {
                    "memoryType": "PROFILE",
                    "fieldKey": "answer_format",
                    "content": "回答优先给步骤清单",
                    "confidence": 0.8,
                },
                {
                    "memoryType": "TASK",
                    "content": "本学期准备红色文化项目课",
                    "confidence": 0.76,
                },
                {
                    "memoryType": "PROFILE",
                    "content": "喜欢使用课堂讨论",
                    "confidence": 0.7,
                },
            ],
        }
    )
    runtime._agent = agent

    response = run_async(runtime.handle(agent_request("我想多做一些探究活动")))

    assert response.status == "completed"
    assert len(agent.calls) == 1
    assert len(response.memory_candidates) == 3
    assert all(item.status == "pending" for item in response.memory_candidates)
    assert "13800138000" not in response.model_dump_json()
    pending = memories.list_memories(OWNER, SCOPE_TYPE, SCOPE_ID, status="pending")
    assert len(pending) == 3
    assert response.memory_applied is None

    agent.payload = {"answer": "这是下一次回答。", "citationIds": []}
    run_async(runtime.handle(agent_request("新会话里继续回答")))
    second_messages = agent.calls[-1]
    system_text = "\n".join(
        str(message.content)
        for message in second_messages
        if isinstance(message, SystemMessage)
    )
    assert all(item.content not in system_text for item in pending)


def test_stream_final_serializes_memory_candidate_datetimes(tmp_path: Path) -> None:
    settings = api_settings(tmp_path)
    runtime, memories = runtime_for(settings)
    memories.update_setting(OWNER, SCOPE_TYPE, SCOPE_ID, True)
    runtime._agent = CapturingAgent(
        {
            "answer": "以后按分点方式回答。",
            "citationIds": [],
            "memoryCandidates": [
                {
                    "memoryType": "PROFILE",
                    "fieldKey": "answer_format",
                    "content": "回答时不要使用表格",
                    "confidence": 0.91,
                }
            ],
        }
    )

    async def collect_events() -> list[str]:
        return [
            event
            async for event in runtime.stream_events(
                agent_request("以后不要给我生成表格了")
            )
        ]

    events = run_async(collect_events())
    final_block = next(event for event in events if event.startswith("event: final"))
    final_data = json.loads(final_block.split("data: ", 1)[1])
    candidate = final_data["response"]["memoryCandidates"][0]

    assert candidate["status"] == "pending"
    assert isinstance(candidate["createdAt"], str)
    assert isinstance(candidate["updatedAt"], str)
    assert events[-1].startswith("event: done")


def test_recall_is_bounded_and_states_current_input_priority(tmp_path: Path) -> None:
    settings = api_settings(
        tmp_path,
        memory_available=True,
    ).model_copy(
        update={
            "agent_memory_context_character_limit": 1500,
            "agent_memory_task_limit": 5,
        }
    )
    runtime, memories = runtime_for(settings)
    memories.update_setting(OWNER, SCOPE_TYPE, SCOPE_ID, True)
    memories.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        field_key="lesson_duration",
        content="常用课时 40 分钟",
        source="profile_ui",
    )
    for index in range(8):
        memories.create_memory(
            OWNER,
            SCOPE_TYPE,
            SCOPE_ID,
            memory_type="TASK",
            content=f"本学期第 {index + 1} 项红色文化课堂任务，重点包含项目实践和学生展示",
            source="profile_ui",
        )
    agent = CapturingAgent({"answer": "本次按 25 分钟设计。", "citationIds": []})
    runtime._agent = agent

    response = run_async(
        runtime.handle(agent_request("这次请设计 25 分钟的红色文化项目实践课"))
    )

    memory_messages = [
        message
        for message in agent.calls[0]
        if isinstance(message, SystemMessage) and "用户长期记忆" in str(message.content)
    ]
    assert len(memory_messages) == 1
    memory_prompt = str(memory_messages[0].content)
    assert len(memory_prompt) <= 1500
    assert memory_prompt.count("[阶段任务]") <= 5
    assert "常用课时 40 分钟" in memory_prompt
    assert "本轮明确输入优先" in memory_prompt
    assert isinstance(agent.calls[0][-1], HumanMessage)
    assert "25 分钟" in str(agent.calls[0][-1].content)
    assert response.memory_applied is not None
    assert response.memory_applied.count <= 6
    assert len(response.memory_applied.memory_ids) == response.memory_applied.count


def test_teaching_plan_can_recall_but_resource_discovery_is_excluded(tmp_path: Path) -> None:
    settings = api_settings(tmp_path)
    runtime, memories = runtime_for(settings)
    memories.update_setting(OWNER, SCOPE_TYPE, SCOPE_ID, True)
    memories.create_memory(
        OWNER,
        SCOPE_TYPE,
        SCOPE_ID,
        memory_type="PROFILE",
        field_key="teaching_style",
        content="偏好项目式教学",
        source="profile_ui",
    )
    teaching_context = run_async(runtime._memory_context_for(
        agent_request(
            "生成教学方案",
            taskType="TEACHING_PLAN",
            taskPayload={"grade": "四年级", "durationMinutes": 40},
        )
    ))
    discovery_context = run_async(runtime._memory_context_for(
        agent_request(
            "发现周边资源",
            taskType="RESOURCE_DISCOVERY",
            taskPayload={"candidates": []},
        )
    ))

    assert teaching_context.items
    assert "偏好项目式教学" in teaching_context.prompt
    assert discovery_context.items == ()
    assert discovery_context.prompt == ""


def test_disabled_user_setting_stops_extraction_and_recall(tmp_path: Path) -> None:
    settings = api_settings(tmp_path)
    runtime, memories = runtime_for(settings)

    run_async(runtime.handle(agent_request("记住我偏好表格回答")))

    assert memories.list_memories(OWNER, SCOPE_TYPE, SCOPE_ID) == []
    context = run_async(runtime._memory_context_for(agent_request("继续回答")))
    assert context.items == ()
    assert context.prompt == ""
