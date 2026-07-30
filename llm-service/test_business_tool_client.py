from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from llm_service.business_tool_client import BusinessToolClient
from llm_service.repository import ConversationRepository
from llm_service.schemas import TrustedContext
from llm_service.tools import ToolRuntimeContext, bind_tool_runtime, query_graph_relations, reset_tool_runtime


def test_business_tool_client_sends_authenticated_relation_query() -> None:
    received = {}

    def handler(request: httpx.Request) -> httpx.Response:
        received["url"] = str(request.url)
        received["token"] = request.headers.get("X-Agent-Service-Token")
        received["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={"code": 200, "message": "success", "data": {
                "retrievalStatus": "ok", "graphFacts": [{"fact": "李大钊"}]
            }},
        )

    client = BusinessToolClient(
        "http://business-service",
        "secret",
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    result = client.query_graph_relations({"scope": {"scopeId": 1}, "query": "关系"})

    assert received["url"].endswith("/internal/agent/tools/relation-query")
    assert received["token"] == "secret"
    assert received["body"]["query"] == "关系"
    assert result["source"] == "business-service"
    assert result["graphFacts"][0]["fact"] == "李大钊"


def test_business_tool_client_sends_authenticated_knowledge_query() -> None:
    received = {}

    def handler(request: httpx.Request) -> httpx.Response:
        received["url"] = str(request.url)
        received["token"] = request.headers.get("X-Agent-Service-Token")
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

    client = BusinessToolClient(
        "http://business-service",
        "secret",
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    result = client.query_knowledge(
        {
            "actor": {"accountId": 1},
            "scope": {"scopeType": "SCHOOL", "scopeId": 1},
            "query": "红色教育",
            "grade": "四年级",
            "theme": "家乡文化",
            "topK": 5,
        }
    )

    assert received["url"].endswith("/internal/agent/tools/knowledge-retrieve")
    assert received["token"] == "secret"
    assert received["body"]["grade"] == "四年级"
    assert result["chunks"][0]["citationId"] == "chunk:1"


def test_graph_tool_keeps_explicit_degraded_fallback_and_audit(tmp_path: Path) -> None:
    repository = ConversationRepository(tmp_path / "agent.sqlite3")
    thread = repository.create_thread("account:1", "SCHOOL", 1)
    runtime = ToolRuntimeContext(
        thread.thread_id,
        TrustedContext(
            actor={"accountId": 1, "roleCode": "school_admin", "schoolId": 1},
            scope={"scopeType": "SCHOOL", "scopeId": 1},
            retrieval={"graphFacts": [{"fact": "可信关系"}]},
        ),
        repository,
        2000,
        business_tool_client=BusinessToolClient("", ""),
    )
    token = bind_tool_runtime(runtime)
    try:
        output = query_graph_relations.invoke({"query": "关系", "limit": 5})
    finally:
        reset_tool_runtime(token)

    payload = json.loads(output)
    assert payload["retrievalStatus"] == "degraded"
    assert payload["degradedReason"] == "business_tool_unconfigured"
    assert payload["graphFacts"] == [{"fact": "可信关系"}]
    assert runtime.executions[0].status == "degraded"
    assert repository.list_tool_audits()[0]["status"] == "degraded"


def test_graph_tool_merges_remote_graph_facts_and_citations(tmp_path: Path) -> None:
    repository = ConversationRepository(tmp_path / "agent.sqlite3")
    thread = repository.create_thread("account:1", "SCHOOL", 1)

    def handler(_request: httpx.Request) -> httpx.Response:
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

    runtime = ToolRuntimeContext(
        thread.thread_id,
        TrustedContext(
            actor={"accountId": 1, "roleCode": "school_admin", "schoolId": 1},
            scope={"scopeType": "SCHOOL", "scopeId": 1},
        ),
        repository,
        2000,
        business_tool_client=BusinessToolClient(
            "http://business-service",
            "secret",
            client=httpx.Client(transport=httpx.MockTransport(handler)),
        ),
    )
    token = bind_tool_runtime(runtime)
    try:
        output = query_graph_relations.invoke({"query": "关联", "limit": 5})
    finally:
        reset_tool_runtime(token)

    assert json.loads(output)["retrievalStatus"] == "OK"
    assert runtime.trusted_context.retrieval["graphFacts"][0]["citationId"] == "graph:1"
    assert runtime.trusted_context.citation_candidates[0]["citationId"] == "graph:1"


@pytest.mark.parametrize("status", [401, 500])
def test_business_tool_client_classifies_upstream_failure(status: int) -> None:
    client = BusinessToolClient(
        "http://business-service",
        "secret",
        client=httpx.Client(
            transport=httpx.MockTransport(lambda _request: httpx.Response(status))
        ),
    )

    with pytest.raises(RuntimeError) as error:
        client.query_graph_relations({})

    assert "business_tool_" in str(error.value)
