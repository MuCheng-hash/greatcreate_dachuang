import json
import os
import unittest
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
from langchain_core.messages import AIMessage, AIMessageChunk
from langgraph.errors import GraphRecursionError
from pydantic import PrivateAttr

import agent.runtime as runtime_module
from agent.config import AgentSettings
from agent.runtime import AgentRuntime
from agent.schemas import AgentRuntimeRequest
from agent.tools import AgentToolError, JavaToolClient
from app import app


class FakeAgentModel(FakeMessagesListChatModel):
    def bind_tools(self, tools, **kwargs):
        return self


class RecordingAgentModel(FakeAgentModel):
    _seen_messages: list[list[str]] = PrivateAttr(default_factory=list)

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        self._seen_messages.append([message.content for message in messages])
        return super()._generate(messages, stop, run_manager, **kwargs)


class FakeJavaToolClient:
    def __init__(self):
        self.calls = []

    def call(self, path, payload, run_id):
        self.calls.append((path, payload, run_id))
        return {
            "retrievalStatus": "ok",
            "citationCandidates": [
                {"citationId": "rag-citation-uuid-1", "title": "证据", "excerpt": "事实"}
            ],
        }


def request_payload(question="附近有哪些资源？", conversation_id="test-conversation"):
    return {
        "question": question,
        "conversationId": conversation_id,
        "scope": {"scopeType": "SCHOOL", "scopeId": 1},
        "actor": {"accountId": 1, "roleCode": "school_admin", "schoolId": 1},
    }


class AgentRuntimeTest(unittest.TestCase):
    def test_provider_specific_model_chain_is_loaded_from_environment(self):
        values = {
            "LLM_API_KEY": "legacy-key",
            "LLM_BASE_URL": "https://legacy.example/v1",
            "LLM_MODEL": "legacy-model",
            "AGENT_PRIMARY_PROVIDER": "bailian",
            "AGENT_PRIMARY_MODEL": "qwen-plus",
            "AGENT_PRIMARY_BASE_URL": "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "AGENT_PRIMARY_API_KEY": "bailian-key",
            "AGENT_FALLBACK_PROVIDER": "ollama",
            "AGENT_FALLBACK_MODEL": "qwen3:8b",
            "AGENT_FALLBACK_BASE_URL": "http://127.0.0.1:11434/v1",
            "AGENT_FALLBACK_API_KEY": "ollama",
        }
        with patch.dict(os.environ, values, clear=True):
            settings = AgentSettings.from_env()

        chain = settings.model_chain()
        self.assertEqual(["qwen-plus", "qwen3:8b"], [item.model for item in chain])
        self.assertEqual(["bailian", "ollama"], [item.provider for item in chain])
        self.assertEqual(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            chain[0].base_url,
        )
        self.assertEqual("http://127.0.0.1:11434/v1", chain[1].base_url)
        self.assertTrue(settings.primary_model_configured())
        self.assertTrue(settings.fallback_model_configured())

    def test_build_model_uses_provider_specific_url_and_key(self):
        settings = AgentSettings(
            primary_provider="bailian",
            primary_model="qwen-plus",
            primary_base_url="https://dashscope.example/v1",
            primary_api_key="bailian-key",
            fallback_provider="ollama",
            fallback_model="qwen3:8b",
            fallback_base_url="http://127.0.0.1:11434/v1",
            fallback_api_key="ollama",
        )
        runtime = AgentRuntime(settings)
        with patch("langchain_openai.ChatOpenAI") as chat_openai:
            runtime._build_model("qwen-plus")
            runtime._build_model("qwen3:8b")

        self.assertEqual(2, chat_openai.call_count)
        self.assertEqual(
            {
                "model": "qwen-plus",
                "api_key": "bailian-key",
                "base_url": "https://dashscope.example/v1",
                "temperature": 0.2,
                "timeout": 20.0,
                "max_retries": 0,
            },
            chat_openai.call_args_list[0].kwargs,
        )
        self.assertEqual(
            "http://127.0.0.1:11434/v1",
            chat_openai.call_args_list[1].kwargs["base_url"],
        )
        self.assertEqual("ollama", chat_openai.call_args_list[1].kwargs["api_key"])

    def test_model_events_include_provider_and_fallback_level(self):
        primary = FakeAgentModel(responses=[AIMessage(content="not-json")])
        fallback = FakeAgentModel(
            responses=[AIMessage(content='{"answer":"Ollama回答","citationIds":[]}')]
        )
        runtime = AgentRuntime(
            AgentSettings(
                primary_provider="bailian",
                primary_model="primary",
                primary_base_url="https://dashscope.example/v1",
                primary_api_key="primary-key",
                fallback_provider="ollama",
                fallback_model="fallback",
                fallback_base_url="http://127.0.0.1:11434/v1",
                fallback_api_key="ollama",
            )
        )
        runtime._build_model = lambda name: {"primary": primary, "fallback": fallback}[name]
        events = []

        response = runtime._execute(
            AgentRuntimeRequest.model_validate(request_payload()),
            "run-provider-fallback",
            lambda event, data: events.append((event, data)),
        )

        started = [data for event, data in events if event == "model.started"]
        failed = [data for event, data in events if event == "model.failed"]
        self.assertEqual(["bailian", "ollama"], [item["provider"] for item in started])
        self.assertEqual("invalid_json", failed[0]["errorType"])
        self.assertEqual("bailian", failed[0]["provider"])
        self.assertIsInstance(failed[0]["latencyMs"], int)
        self.assertEqual("fallback", response["model"])

    def test_stream_parser_uses_complete_buffer_for_fragmented_json(self):
        class FragmentedStreamAgent:
            def stream(self, *_args, **_kwargs):
                for fragment in (
                    '{"answer":"',
                    "分片回答",
                    '","citationIds":[]}',
                ):
                    yield {
                        "type": "messages",
                        "data": (AIMessageChunk(content=fragment), None),
                    }

        runtime = AgentRuntime(AgentSettings())
        runtime._create_agent = lambda _model, _tools: FragmentedStreamAgent()
        events = []
        response = runtime._invoke_agent_stream(
            AgentRuntimeRequest.model_validate(request_payload()),
            "run-stream-fragments",
            object(),
            "fake",
            lambda event, data: events.append((event, data)),
        )

        self.assertEqual("分片回答", response["answer"])
        self.assertEqual("completed", response["generationStatus"])
        self.assertTrue(any(event == "token" for event, _ in events))

    def test_model_intent_is_normalized_to_compatibility_enum(self):
        runtime = AgentRuntime(AgentSettings())
        self.assertEqual(
            "NEARBY_RESOURCE",
            runtime._normalize_intent("查询红色教育资源", "附近有哪些资源？"),
        )
        self.assertEqual(
            "RESOURCE_EXPLANATION",
            runtime._normalize_intent("查询学段适配性", "适合四年级吗？"),
        )

    def test_java_api_response_is_unwrapped_and_token_failure_is_not_success(self):
        response = MagicMock()
        response.read.return_value = b'{"code":200,"data":{"retrievalStatus":"ok"}}'
        context = MagicMock()
        context.__enter__.return_value = response
        with patch("agent.tools.urllib.request.urlopen", return_value=context):
            result = JavaToolClient("http://java", "secret").call(
                "/internal/agent/tools/knowledge-retrieve", {}, "run"
            )
        self.assertEqual({"retrievalStatus": "ok"}, result)

        response.read.return_value = b'{"code":403,"message":"token invalid"}'
        with patch("agent.tools.urllib.request.urlopen", return_value=context):
            with self.assertRaisesRegex(AgentToolError, "token invalid"):
                JavaToolClient("http://java", "secret").call(
                    "/internal/agent/tools/knowledge-retrieve", {}, "run"
                )

    def test_agent_selects_knowledge_tool_and_preserves_valid_citation(self):
        client = FakeJavaToolClient()
        model = FakeAgentModel(
            responses=[
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "retrieve_knowledge",
                            "args": {"query": "附近红色资源", "grade": "四年级"},
                            "id": "call-1",
                            "type": "tool_call",
                        }
                    ],
                ),
                AIMessage(
                    content=json.dumps(
                        {
                            "answer": "根据证据回答",
                            "intent": "NEARBY_RESOURCE",
                            "retrievalStatus": "degraded",
                            "citationIds": ["rag-citation-uuid-1", "forged-citation-uuid"],
                        },
                        ensure_ascii=False,
                    )
                ),
            ]
        )
        runtime = AgentRuntime(AgentSettings())
        request = AgentRuntimeRequest.model_validate(request_payload())
        with patch.object(runtime_module, "JavaToolClient", return_value=client):
            response = runtime._invoke_agent(
                request, "run-1", model, "fake", lambda *_: None
            )

        self.assertEqual("/internal/agent/tools/knowledge-retrieve", client.calls[0][0])
        self.assertEqual("附近红色资源", client.calls[0][1]["query"])
        self.assertEqual(["rag-citation-uuid-1"], response["citationIds"])
        self.assertEqual("ok", response["retrievalStatus"])
        self.assertEqual("rag-citation-uuid-1", response["citations"][0]["citationId"])

    def test_citation_ids_are_collected_only_from_tool_evidence(self):
        runtime = AgentRuntime(AgentSettings())
        messages = [
            AIMessage(content='{"citationId":"forged-citation-uuid"}'),
        ]

        self.assertEqual(set(), runtime._collect_citation_ids(messages))
        self.assertEqual("empty", runtime._resolve_retrieval_status(
            {"retrievalStatus": "ok"}, messages, set()
        ))

    def test_usage_fields_aggregate_multiple_model_turns(self):
        runtime = AgentRuntime(AgentSettings())
        messages = [
            AIMessage(
                content="第一轮",
                usage_metadata={"input_tokens": 10, "output_tokens": 3, "total_tokens": 13},
            ),
            AIMessage(
                content="第二轮",
                usage_metadata={"input_tokens": 7, "output_tokens": 5, "total_tokens": 12},
            ),
        ]

        self.assertEqual(
            {"input_tokens": 17, "output_tokens": 8},
            runtime._usage_fields(messages),
        )

    def test_same_conversation_keeps_previous_messages_and_rejects_other_actor(self):
        model = RecordingAgentModel(
            responses=[
                AIMessage(content='{"answer":"第一轮","citationIds":[]}'),
                AIMessage(content='{"answer":"第二轮","citationIds":[]}'),
            ]
        )
        runtime = AgentRuntime(AgentSettings())
        runtime._build_model = lambda _: model

        first = runtime.run(request_payload("第一问", "conversation-1"))
        second = runtime.run(request_payload("第二问", "conversation-1"))

        self.assertEqual("第一轮", first["answer"])
        self.assertEqual("第二轮", second["answer"])
        self.assertTrue(any("第一问" in messages for messages in model._seen_messages[1:]))
        with self.assertRaisesRegex(ValueError, "does not belong"):
            runtime.run(
                {
                    **request_payload("越权问题", "conversation-1"),
                    "actor": {"accountId": 2, "roleCode": "school_admin", "schoolId": 1},
                }
            )

    def test_max_iterations_is_bounded(self):
        model = FakeAgentModel(
            responses=[
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "retrieve_knowledge",
                            "args": {"query": "持续调用"},
                            "id": "loop",
                            "type": "tool_call",
                        }
                    ],
                )
            ]
        )
        runtime = AgentRuntime(AgentSettings(max_iterations=1))
        request = AgentRuntimeRequest.model_validate(request_payload())
        with patch.object(runtime_module, "JavaToolClient", return_value=FakeJavaToolClient()):
            with self.assertRaises(GraphRecursionError):
                runtime._invoke_agent(request, "run-loop", model, "fake", lambda *_: None)

    def test_primary_model_failure_uses_fallback_model(self):
        primary = FakeAgentModel(responses=[AIMessage(content="not-json")])
        fallback = FakeAgentModel(
            responses=[AIMessage(content='{"answer":"降级模型回答","citationIds":[]}')]
        )
        runtime = AgentRuntime(
            AgentSettings(primary_model="primary", fallback_model="fallback")
        )
        runtime._build_model = lambda name: {"primary": primary, "fallback": fallback}[name]
        events = []
        response = runtime.run(request_payload())
        self.assertEqual("降级模型回答", response["answer"])

        runtime._build_model = lambda name: {"primary": primary, "fallback": fallback}[name]
        request = AgentRuntimeRequest.model_validate(request_payload("再试一次", "fallback-test"))
        runtime._execute(request, "run-fallback", lambda event, data: events.append((event, data)))
        self.assertTrue(any(event == "model.failed" for event, _ in events))

    def test_sse_event_order_contains_final_and_done(self):
        runtime = AgentRuntime(AgentSettings())
        events = list(runtime.stream_events(request_payload()))
        names = [line.split("\n", 1)[0].removeprefix("event: ") for line in events]
        self.assertEqual("run.started", names[0])
        self.assertIn("token", names)
        self.assertEqual("final", names[-2])
        self.assertEqual("done", names[-1])

    def test_health_endpoints(self):
        with TestClient(app) as client:
            self.assertEqual(200, client.get("/health/live").status_code)
            self.assertEqual("ok", client.get("/health/live").json()["status"])
            self.assertEqual(200, client.get("/health/ready").status_code)

    def test_fastapi_agent_run_and_stream_endpoints(self):
        with TestClient(app) as client:
            run_response = client.post("/llm/agent/run", json=request_payload(
                "接口运行", "endpoint-conversation"
            ))
            self.assertEqual(200, run_response.status_code)
            self.assertEqual("endpoint-conversation", run_response.json()["conversationId"])
            self.assertTrue(run_response.json()["runId"])

            stream_response = client.post("/llm/agent/stream", json=request_payload(
                "接口流式", "endpoint-stream"
            ))
            self.assertEqual(200, stream_response.status_code)
            self.assertIn("event: run.started", stream_response.text)
            self.assertIn("event: token", stream_response.text)
            self.assertIn("event: final", stream_response.text)
            self.assertIn("event: done", stream_response.text)


if __name__ == "__main__":
    unittest.main()
