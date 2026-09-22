from __future__ import annotations

import json

import httpx
import pytest

from llm_service.history_index import (
    ConversationHistoryRetriever,
    ConversationHistoryWorker,
    ConversationVectorStore,
    HistoryEmbeddingClient,
    HistoryIndexError,
    HistoryIndexJob,
    HistoryCleanupJob,
    IndexedMessage,
    RetrievedHistoryMessage,
)
from llm_service.db_cli import _parser
from llm_service.settings import Settings


def test_history_settings_use_documented_threshold_name() -> None:
    settings = Settings(
        _env_file=None,
        database_url="postgresql://user:password@localhost:5432/agent",
        agent_history_retrieval_enabled=True,
        agent_history_qdrant_url="http://qdrant.test",
        embedding_api_url="http://embedding.test/v1",
        agent_history_retrieval_min_score=0.61,
        agent_history_index_poll_seconds=7,
        agent_history_index_max_attempts=3,
    )

    assert settings.agent_history_retrieval_min_score == 0.61
    assert settings.agent_history_index_poll_seconds == 7
    assert settings.agent_history_index_max_attempts == 3
    assert settings.history_retrieval_configured is True


def test_history_requeue_cli_accepts_default_and_full_rebuild_modes() -> None:
    parser = _parser()

    default = parser.parse_args(["history-requeue"])
    rebuild = parser.parse_args(["history-requeue", "--all"])

    assert default.command == "history-requeue"
    assert default.rebuild_all is False
    assert rebuild.rebuild_all is True


@pytest.mark.asyncio
async def test_embedding_client_rejects_wrong_dimension_without_exposing_response() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": [{"embedding": [0.1]}]})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    embedder = HistoryEmbeddingClient(
        "http://embedding.test", "secret", "embedding-model", 2, client
    )
    try:
        with pytest.raises(HistoryIndexError) as raised:
            await embedder.embed("private source text")
    finally:
        await client.aclose()

    assert raised.value.code == "embedding_response_invalid"
    assert "private source text" not in str(raised.value)


@pytest.mark.asyncio
async def test_disabled_retrieval_does_not_call_external_dependencies() -> None:
    class UnexpectedCall:
        async def embed(self, _text: str) -> list[float]:
            raise AssertionError("disabled retrieval must not embed")

        async def search(self, *_args):
            raise AssertionError("disabled retrieval must not search")

    result = await ConversationHistoryRetriever(
        UnexpectedCall(), UnexpectedCall(), UnexpectedCall(), enabled=False
    ).retrieve("thread-1", "account:1", "SCHOOL", "1", 9, "question")

    assert result == []


@pytest.mark.asyncio
async def test_retriever_caps_hydrated_history_characters() -> None:
    class Embedder:
        async def embed(self, _text: str) -> list[float]:
            return [0.1]

    class Store:
        async def search(self, *_args):
            return [type("Match", (), {"message_id": 7, "score": 0.91})()]

    class Repository:
        async def hydrate_history_messages(self, *_args):
            return [RetrievedHistoryMessage(7, "thread-1", None, "user", "abcdef", 0.91)]

    result = await ConversationHistoryRetriever(
        Repository(), Embedder(), Store(), enabled=True, character_limit=4
    ).retrieve("thread-1", "account:1", "SCHOOL", "1", 9, "question")

    # A retrieved record is an atomic context component.  Returning a fragment
    # could invert the meaning of a constraint, so an oversized record is skipped.
    assert result == []


@pytest.mark.asyncio
async def test_retriever_skips_an_oversized_match_and_keeps_a_later_atomic_match() -> None:
    class Embedder:
        async def embed(self, _text: str) -> list[float]:
            return [0.1]

    class Store:
        async def search(self, *_args):
            return [
                type("Match", (), {"message_id": 7, "score": 0.91})(),
                type("Match", (), {"message_id": 8, "score": 0.87})(),
            ]

    class Repository:
        async def hydrate_history_messages(self, *_args):
            return [
                RetrievedHistoryMessage(7, "thread-1", "turn-1", "user", "abcdef", 0.91),
                RetrievedHistoryMessage(8, "thread-1", "turn-2", "assistant", "可用", 0.87),
            ]

    result = await ConversationHistoryRetriever(
        Repository(), Embedder(), Store(), enabled=True, character_limit=4
    ).retrieve("thread-1", "account:1", "SCHOOL", "1", 9, "question")

    assert [(item.message_id, item.content) for item in result] == [(8, "可用")]


@pytest.mark.asyncio
async def test_qdrant_upsert_never_sends_raw_conversation_content() -> None:
    captured: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(200, json={"result": {"status": "acknowledged"}})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    store = ConversationVectorStore("http://qdrant.test", "", "conversation_test", client)
    try:
        await store.upsert(
            IndexedMessage(7, "thread-1", None, "user", "绝不能写入向量库的秘密正文"),
            [0.1, 0.2],
        )
    finally:
        await client.aclose()

    payload = json.dumps(captured, ensure_ascii=False)
    assert "绝不能写入向量库的秘密正文" not in payload
    assert captured["points"][0]["id"] == 7
    assert '"message_id": 7' in payload
    assert '"thread_id": "thread-1"' in payload


@pytest.mark.asyncio
async def test_qdrant_message_cleanup_uses_numeric_point_id() -> None:
    captured: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(200, json={"result": {"status": "acknowledged"}})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    store = ConversationVectorStore("http://qdrant.test", "", "conversation_test", client)
    try:
        await store.delete_point(7)
    finally:
        await client.aclose()

    assert captured == {"points": [7]}


@pytest.mark.asyncio
async def test_qdrant_search_filters_current_thread_and_summary_cursor() -> None:
    captured: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(
            200,
            json={"result": [{"id": "7", "score": 0.91, "payload": {"message_id": 7}}]},
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    store = ConversationVectorStore("http://qdrant.test", "", "conversation_test", client)
    try:
        matches = await store.search("thread-1", 19, [0.1, 0.2], 4, 0.55)
    finally:
        await client.aclose()

    assert [(item.message_id, item.score) for item in matches] == [(7, 0.91)]
    filters = captured["filter"]["must"]
    assert filters[0] == {"key": "thread_id", "match": {"value": "thread-1"}}
    assert filters[1] == {"key": "message_id", "range": {"lte": 19}}


@pytest.mark.asyncio
async def test_qdrant_collection_setup_rejects_empty_success_result() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"result": None})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    store = ConversationVectorStore("http://qdrant.test", "", "conversation_test", client)
    try:
        with pytest.raises(HistoryIndexError) as raised:
            await store.ensure_collection(2)
    finally:
        await client.aclose()

    assert raised.value.code == "vector_store_response_invalid"


@pytest.mark.asyncio
async def test_qdrant_collection_setup_creates_thread_and_message_payload_indexes() -> None:
    requests: list[tuple[str, str, dict[str, object]]] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append((request.method, request.url.path, json.loads(request.content)))
        return httpx.Response(200, json={"result": True})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    store = ConversationVectorStore("http://qdrant.test", "", "conversation_test", client)
    try:
        await store.ensure_collection(2)
    finally:
        await client.aclose()

    assert requests == [
        ("PUT", "/collections/conversation_test", {
            "vectors": {"size": 2, "distance": "Cosine"},
        }),
        ("PUT", "/collections/conversation_test/index", {
            "field_name": "thread_id", "field_schema": "keyword",
        }),
        ("PUT", "/collections/conversation_test/index", {
            "field_name": "message_id", "field_schema": "integer",
        }),
    ]


@pytest.mark.asyncio
async def test_worker_leaves_a_retryable_job_when_embedding_service_fails() -> None:
    job = HistoryIndexJob(7, "processing", 1, "worker-1")

    class Repository:
        retry_calls: list[tuple[int, str]] = []

        async def claim_ready(self, *_args):
            return [job]

        async def indexable_message(self, message_id: int):
            assert message_id == 7
            return IndexedMessage(7, "thread-1", None, "user", "预算不超过3000元")

        async def mark_completed(self, *_args):
            raise AssertionError("embedding failed, job must not complete")

        async def release_retry(
            self, message_id: int, error_code: str, *_args
        ) -> bool:
            self.retry_calls.append((message_id, error_code))
            return True

        async def claim_cleanup_ready(self, *_args):
            return []

    class FailingEmbedder:
        async def embed(self, _text: str) -> list[float]:
            raise HistoryIndexError("embedding_unavailable")

    result = await ConversationHistoryWorker(
        Repository(), FailingEmbedder(), object(), "worker-1", batch_size=1, max_attempts=3
    ).run_once()

    assert result.retried == 1
    assert result.completed == 0
    assert Repository.retry_calls == [(7, "embedding_unavailable")]


@pytest.mark.asyncio
async def test_worker_scales_claim_leases_for_serial_external_calls() -> None:
    class Repository:
        claims: list[tuple[str, str, int, int]] = []

        async def claim_ready(
            self, worker_id: str, batch_size: int, lease_seconds: int
        ) -> list[HistoryIndexJob]:
            self.claims.append(("index", worker_id, batch_size, lease_seconds))
            return []

        async def claim_cleanup_ready(
            self, worker_id: str, batch_size: int, lease_seconds: int
        ) -> list[HistoryCleanupJob]:
            self.claims.append(("cleanup", worker_id, batch_size, lease_seconds))
            return []

    class Embedder:
        timeout_seconds = 20

    class Store:
        timeout_seconds = 10

    repository = Repository()
    await ConversationHistoryWorker(
        repository, Embedder(), Store(), "worker-1", batch_size=3, lease_seconds=1
    ).run_once()

    assert repository.claims == [
        ("index", "worker-1", 3, 95),
        ("cleanup", "worker-1", 3, 65),
    ]


@pytest.mark.asyncio
async def test_worker_marks_job_failed_after_max_attempts() -> None:
    class Repository:
        failed_calls: list[tuple[int, str]] = []

        async def claim_ready(self, *_args):
            return [HistoryIndexJob(7, "processing", 3, "worker-1")]

        async def indexable_message(self, _message_id: int):
            return IndexedMessage(7, "thread-1", None, "user", "private message")

        async def mark_failed(self, message_id: int, error_code: str, *_args) -> bool:
            self.failed_calls.append((message_id, error_code))
            return True

        async def claim_cleanup_ready(self, *_args):
            return []

    class FailingEmbedder:
        async def embed(self, _text: str) -> list[float]:
            raise HistoryIndexError("embedding_unavailable")

    result = await ConversationHistoryWorker(
        Repository(), FailingEmbedder(), object(), "worker-1", max_attempts=3
    ).run_once()

    assert result.failed == 1
    assert Repository.failed_calls == [(7, "embedding_unavailable")]


@pytest.mark.asyncio
async def test_worker_deletes_message_vector_for_cleanup_job() -> None:
    class Repository:
        finished: list[tuple[HistoryCleanupJob, bool]] = []

        async def claim_ready(self, *_args):
            return []

        async def claim_cleanup_ready(self, *_args):
            return [HistoryCleanupJob("message", "7", 1, "worker-1")]

        async def finish_cleanup(self, job: HistoryCleanupJob, *, deleted: bool, **_kwargs):
            self.finished.append((job, deleted))
            return True

    class Store:
        deleted_ids: list[int] = []

        async def delete_point(self, message_id: int):
            self.deleted_ids.append(message_id)

    result = await ConversationHistoryWorker(
        Repository(), object(), Store(), "worker-1"
    ).run_once()

    assert result.message_cleaned == 1
    assert Store.deleted_ids == [7]
    assert Repository.finished == [(
        HistoryCleanupJob("message", "7", 1, "worker-1"),
        True,
    )]


@pytest.mark.asyncio
async def test_worker_deletes_thread_vectors_for_thread_cleanup_job() -> None:
    class Repository:
        async def claim_ready(self, *_args):
            return []

        async def claim_cleanup_ready(self, *_args):
            return [HistoryCleanupJob("thread", "thread-1", 1, "worker-1")]

        async def finish_cleanup(self, *_args, **_kwargs) -> bool:
            return True

    class Store:
        deleted_threads: list[str] = []

        async def delete_thread(self, thread_id: str) -> None:
            self.deleted_threads.append(thread_id)

    result = await ConversationHistoryWorker(
        Repository(), object(), Store(), "worker-1"
    ).run_once()

    assert result.thread_cleaned == 1
    assert Store.deleted_threads == ["thread-1"]


@pytest.mark.asyncio
async def test_worker_marks_cleanup_failed_after_max_attempts() -> None:
    class Repository:
        failed: list[dict[str, object]] = []

        async def claim_ready(self, *_args):
            return []

        async def claim_cleanup_ready(self, *_args):
            return [HistoryCleanupJob("message", "7", 3, "worker-1")]

        async def finish_cleanup(self, _job, **kwargs) -> bool:
            self.failed.append(kwargs)
            return True

    class FailingStore:
        async def delete_point(self, _message_id: int) -> None:
            raise HistoryIndexError("vector_store_unavailable")

    result = await ConversationHistoryWorker(
        Repository(), object(), FailingStore(), "worker-1", max_attempts=3
    ).run_once()

    assert result.failed == 1
    assert Repository.failed == [{
        "deleted": False,
        "terminal": True,
        "error_code": "vector_store_unavailable",
        "delay_seconds": 0,
    }]


@pytest.mark.asyncio
async def test_retriever_hydrates_only_current_thread_messages_after_vector_search() -> None:
    class Embedder:
        async def embed(self, text: str) -> list[float]:
            assert text == "请重新推荐资源"
            return [0.1]

    class Store:
        async def search(self, thread_id, max_message_id, vector, limit, min_score):
            assert (thread_id, max_message_id, vector, limit, min_score) == (
                "thread-1", 19, [0.1], 4, 0.55
            )
            return [
                type("Match", (), {"message_id": 7, "score": 0.91})(),
                type("Match", (), {"message_id": 8, "score": 0.87})(),
            ]

    class Repository:
        async def hydrate_history_messages(
            self, message_ids, thread_id, owner_id, scope_type, scope_id, max_message_id
        ):
            assert message_ids == [7, 8]
            assert (thread_id, owner_id, scope_type, scope_id, max_message_id) == (
                "thread-1", "account:1", "SCHOOL", "1", 19
            )
            return [
                RetrievedHistoryMessage(7, "thread-1", "turn-1", "user", "预算不超过3000元", 0.91),
            ]

    result = await ConversationHistoryRetriever(
        Repository(), Embedder(), Store(), enabled=True, limit=4, min_score=0.55
    ).retrieve("thread-1", "account:1", "SCHOOL", "1", 19, "请重新推荐资源")

    assert [item.content for item in result] == ["预算不超过3000元"]
    assert [item.score for item in result] == [0.91]
