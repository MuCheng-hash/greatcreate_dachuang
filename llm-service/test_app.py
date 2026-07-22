import unittest
from unittest.mock import patch

import app
from fastapi.testclient import TestClient


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

    @patch.object(app, "call_openai_compatible", return_value=None)
    def test_returns_degraded_fallback_with_valid_evidence_ids(self, _call):
        response = self.client.post("/llm/agent/answer", json=self.payload)

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["generationStatus"], "degraded")
        self.assertTrue(data["answer"])
        self.assertEqual(data["citationIds"], ["chunk:1", "graph:1"])

    @patch.object(app, "call_openai_compatible", return_value={
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
    @patch.object(app.urllib.request, "urlopen")
    def test_calls_openai_compatible_json_endpoint(self, urlopen):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                return '{"choices":[{"message":{"content":"```json\\n{\\"answer\\":\\"模型回答\\"}\\n```"}}]}'.encode("utf-8")

        urlopen.return_value = FakeResponse()

        result = app.call_openai_compatible("请回答")

        self.assertEqual(result["answer"], "模型回答")
        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
        self.assertEqual(request.get_header("Authorization"), "Bearer test-key")


if __name__ == "__main__":
    unittest.main()
