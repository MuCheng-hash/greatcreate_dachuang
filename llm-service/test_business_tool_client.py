from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
from pydantic import ValidationError

from llm_service.business_tool_client import BusinessToolClient, BusinessToolError
from llm_service.schemas import AgentMessageRequest, TrustedContext
from llm_service.tools import (
    AGENT_TOOLS,
    ToolRuntimeContext,
    bind_tool_runtime,
    query_graph_relations,
    reset_tool_runtime,
)
from postgres_test_support import conversation_repository, run_async, settings_for_database


def test_business_tool_client_sends_authenticated_relation_query() -> None:
    received = {}

    def handler(request: httpx.Request) -> httpx.Response:
        received["url"] = str(request.url)
        received["token"] = request.headers.get("X-Agent-Service-Token")
        received["tool_context"] = request.headers.get("X-Agent-Tool-Context")
        received["client_turn"] = request.headers.get("X-Agent-Client-Turn-Id")
        received["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={"code": 200, "message": "success", "data": {
                "retrievalStatus": "ok", "graphFacts": [{"fact": "李大钊"}]
            }},
        )

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    client = BusinessToolClient(
        "http://business-service",
        "secret",
        client=http_client,
    )

    result = run_async(
        client.query_graph_relations(
            {"scope": {"scopeId": 1}, "query": "关系"},
            tool_authorization="signed-context",
            client_turn_id="turn-1",
        )
    )
    run_async(http_client.aclose())

    assert received["url"].endswith("/internal/agent/tools/relation-query")
    assert received["token"] == "secret"
    assert received["tool_context"] == "signed-context"
    assert received["client_turn"] == "turn-1"
    assert received["body"].get("toolAuthorization") is None
    assert received["body"]["query"] == "关系"
    assert result["source"] == "business-service"
    assert result["graphFacts"][0]["fact"] == "李大钊"


def test_business_tool_client_sends_authenticated_knowledge_query() -> None:
    received = {}

    def handler(request: httpx.Request) -> httpx.Response:
        received["url"] = str(request.url)
        received["token"] = request.headers.get("X-Agent-Service-Token")
        received["tool_context"] = request.headers.get("X-Agent-Tool-Context")
        received["client_turn"] = request.headers.get("X-Agent-Client-Turn-Id")
        received["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "code": 200,
                "message": "success",
                "data": {
                    "retrievalStatus": "ok",
                    "chunks": [{"citationId": "chunk:1", "text": "红色教育"}],
                },
            },
        )

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    client = BusinessToolClient(
        "http://business-service",
        "secret",
        client=http_client,
    )

    result = run_async(client.query_knowledge(
        {
            "actor": {"accountId": 1},
            "scope": {"scopeType": "SCHOOL", "scopeId": 1},
            "query": "红色教育",
            "grade": "四年级",
            "theme": "家乡文化",
            "topK": 5,
        },
        tool_authorization="signed-context",
        client_turn_id="turn-1",
    ))
    run_async(http_client.aclose())

    assert received["url"].endswith("/internal/agent/tools/knowledge-retrieve")
    assert received["token"] == "secret"
    assert received["tool_context"] == "signed-context"
    assert received["client_turn"] == "turn-1"
    assert received["body"].get("toolAuthorization") is None
    assert received["body"]["grade"] == "四年级"
    assert result["chunks"][0]["citationId"] == "chunk:1"


def test_agent_message_rejects_top_level_scope_that_differs_from_trusted_scope() -> None:
    """若删除请求范围与可信范围的一致性校验，此测试必须失败。"""
    with pytest.raises(ValidationError, match="可信范围"):
        AgentMessageRequest(
            ownerId="account:1",
            scopeType="SCHOOL",
            scopeId=1,
            clientTurnId="turn-1",
            message="查询资源",
            context={
                "actor": {"accountId": 1, "roleCode": "student", "schoolId": 1},
                "scope": {"scopeType": "SCHOOL", "scopeId": 2},
            },
        )


def test_dynamic_tool_schemas_do_not_expose_identity_or_authorization_fields() -> None:
    schemas = {tool.name: tool.args_schema.model_json_schema() for tool in AGENT_TOOLS}
    for tool_name in ("retrieve_knowledge", "query_graph_relations"):
        assert set(schemas[tool_name]["properties"]) == {"query", "limit"}
    for schema in schemas.values():
        assert not {"actor", "scope", "schoolId", "toolAuthorization"}.intersection(
            schema["properties"]
        )


def test_business_tool_client_reads_internal_web_source_domains() -> None:
    received = {}

    def handler(request: httpx.Request) -> httpx.Response:
        received["url"] = str(request.url)
        received["token"] = request.headers.get("X-Agent-Service-Token")
        return httpx.Response(200, json={"code": 200, "data": {"domains": ["www.gov.cn"]}})

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    client = BusinessToolClient(
        "http://business-service",
        "secret",
        client=http_client,
    )

    assert run_async(client.web_source_domains()) == ["www.gov.cn"]
    run_async(http_client.aclose())
    assert received["url"].endswith("/internal/agent/tools/web-source-domains")
    assert received["token"] == "secret"


def test_write_client_forwards_stable_end_to_end_idempotency_headers() -> None:
    received = {}

    def handler(request: httpx.Request) -> httpx.Response:
        received["idempotency_key"] = request.headers.get("Idempotency-Key")
        received["turn_id"] = request.headers.get("X-Agent-Turn-Id")
        received["body"] = json.loads(request.content)
        return httpx.Response(
            200, json={"code": 200, "data": {"resourceId": 7}}
        )

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    client = BusinessToolClient(
        "http://business-service",
        "secret",
        write_tools_enabled=True,
        client=http_client,
    )
    result = run_async(client.execute_write(
        "/internal/agent/actions/resources/update",
        {"resourceId": 7},
        action_id="stable-action-1",
        turn_id="turn-1",
    ))
    run_async(http_client.aclose())

    assert result == {"resourceId": 7}
    assert received == {
        "idempotency_key": "stable-action-1",
        "turn_id": "turn-1",
        "body": {"resourceId": 7},
    }


def test_write_client_keeps_action_in_progress_distinct_from_payload_conflict() -> None:
    http_client = httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _request: httpx.Response(
            409,
            json={"code": 409, "data": {"code": "action_in_progress"}},
        )
    ))
    client = BusinessToolClient(
        "http://business-service", "secret",
        write_tools_enabled=True, client=http_client,
    )

    with pytest.raises(BusinessToolError, match="action_in_progress"):
        run_async(client.execute_write(
            "/internal/agent/actions/resources/update", {},
            action_id="stable-action-1", turn_id="turn-1",
        ))
    run_async(http_client.aclose())


def test_graph_tool_keeps_explicit_degraded_fallback_and_audit(tmp_path: Path) -> None:
    repository = conversation_repository(settings_for_database(tmp_path))
    thread = repository.create_thread("account:1", "SCHOOL", 1)
    runtime = ToolRuntimeContext(
        thread.thread_id,
        TrustedContext(
            actor={"accountId": 1, "roleCode": "school_admin", "schoolId": 1},
            scope={"scopeType": "SCHOOL", "scopeId": 1},
            retrieval={"graphFacts": [{"fact": "可信关系"}]},
        ),
        repository.async_target,
        2000,
        client_turn_id="turn-1",
        business_tool_client=BusinessToolClient("", ""),
    )
    token = bind_tool_runtime(runtime)
    try:
        output = run_async(
            query_graph_relations.ainvoke({"query": "关系", "limit": 5})
        )
    finally:
        reset_tool_runtime(token)

    payload = json.loads(output)
    assert payload["retrievalStatus"] == "degraded"
    assert payload["degradedReason"] == "business_tool_unconfigured"
    assert payload["graphFacts"] == [{"fact": "可信关系"}]
    assert runtime.executions[0].status == "degraded"
    assert repository.list_tool_audits()[0]["status"] == "degraded"


def test_graph_tool_does_not_call_business_service_without_signed_context(tmp_path: Path) -> None:
    """若缺少 Java 签发凭据仍可远程查询，学生或管理员身份可被伪造。"""
    repository = conversation_repository(settings_for_database(tmp_path))
    thread = repository.create_thread("account:1", "SCHOOL", 1)
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json={"code": 200, "data": {"graphFacts": []}})

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    runtime = ToolRuntimeContext(
        thread.thread_id,
        TrustedContext(
            actor={"accountId": 1, "roleCode": "student", "schoolId": 1},
            scope={"scopeType": "SCHOOL", "scopeId": 1},
        ),
        repository.async_target,
        2000,
        client_turn_id="turn-1",
        business_tool_client=BusinessToolClient(
            "http://business-service", "secret", client=http_client
        ),
    )
    token = bind_tool_runtime(runtime)
    try:
        output = run_async(query_graph_relations.ainvoke({"query": "关联", "limit": 5}))
    finally:
        reset_tool_runtime(token)
        run_async(http_client.aclose())

    assert calls == []
    assert json.loads(output)["degradedReason"] == "tool_context_authorization_missing"


def test_graph_tool_merges_remote_graph_facts_and_citations(tmp_path: Path) -> None:
    repository = conversation_repository(settings_for_database(tmp_path))
    thread = repository.create_thread("account:1", "SCHOOL", 1)
    received = {}

    def handler(request: httpx.Request) -> httpx.Response:
        received["body"] = json.loads(request.content)
        received["tool_context"] = request.headers.get("X-Agent-Tool-Context")
        received["client_turn"] = request.headers.get("X-Agent-Client-Turn-Id")
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {
                    "retrievalStatus": "OK",
                    "graphFacts": [{"citationId": "graph:1", "text": "李大钊关联学校"}],
                    "citationCandidates": [{"citationId": "graph:1", "title": "关系来源"}],
                },
            },
        )

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    runtime = ToolRuntimeContext(
        thread.thread_id,
        TrustedContext(
            actor={"accountId": 1, "roleCode": "school_admin", "schoolId": 1},
            scope={"scopeType": "SCHOOL", "scopeId": 1},
            toolAuthorization="signed-context",
        ),
        repository.async_target,
        2000,
        client_turn_id="turn-1",
        business_tool_client=BusinessToolClient(
            "http://business-service",
            "secret",
            client=http_client,
        ),
    )
    token = bind_tool_runtime(runtime)
    try:
        output = run_async(
            query_graph_relations.ainvoke({"query": "关联", "limit": 5})
        )
    finally:
        reset_tool_runtime(token)

    assert json.loads(output)["retrievalStatus"] == "OK"
    assert received["body"]["actor"] == {
        "accountId": 1,
        "roleCode": "school_admin",
        "schoolId": 1,
    }
    assert received["body"]["scope"] == {
        "scopeType": "SCHOOL",
        "scopeId": 1,
    }
    assert received["body"]["query"] == "关联"
    assert received["body"]["topK"] == 5
    assert received["body"].get("toolAuthorization") is None
    assert received["tool_context"] == "signed-context"
    assert received["client_turn"] == "turn-1"
    assert runtime.trusted_context.retrieval["graphFacts"][0]["citationId"] == "graph:1"
    assert runtime.trusted_context.citation_candidates[0]["citationId"] == "graph:1"
    run_async(http_client.aclose())


@pytest.mark.parametrize("status", [401, 500])
def test_business_tool_client_classifies_upstream_failure(status: int) -> None:
    http_client = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _request: httpx.Response(status))
    )
    client = BusinessToolClient(
        "http://business-service",
        "secret",
        client=http_client,
    )

    with pytest.raises(RuntimeError) as error:
        run_async(client.query_graph_relations(
            {}, tool_authorization="signed-context", client_turn_id="turn-1"
        ))

    assert "business_tool_" in str(error.value)
    run_async(http_client.aclose())
