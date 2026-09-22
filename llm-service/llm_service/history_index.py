from __future__ import annotations

import hashlib
import inspect
import math
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping

import httpx

from .database import Database


class HistoryIndexError(RuntimeError):
    """A stable, non-sensitive error returned by the history-index boundary."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True, slots=True)
class HistoryIndexJob:
    message_id: int
    status: str
    attempt_count: int
    lease_owner: str


@dataclass(frozen=True, slots=True)
class IndexedMessage:
    message_id: int
    thread_id: str
    turn_id: str | None
    role: str
    content: str
    created_at: str | None = None


@dataclass(frozen=True, slots=True)
class RetrievedHistoryMessage:
    message_id: int
    thread_id: str
    turn_id: str | None
    role: str
    content: str
    score: float


@dataclass(frozen=True, slots=True)
class HistoryVectorMatch:
    message_id: int
    score: float


# Keep the short name available to callers which do not need the history prefix.
VectorMatch = HistoryVectorMatch


@dataclass(frozen=True, slots=True)
class HistoryCleanupJob:
    kind: str
    identifier: str
    attempt_count: int
    lease_owner: str


@dataclass(frozen=True, slots=True)
class HistoryIndexRunResult:
    completed: int = 0
    skipped: int = 0
    retried: int = 0
    failed: int = 0
    lease_lost: int = 0
    thread_cleaned: int = 0
    message_cleaned: int = 0


def _clean_url(url: str, suffix: str) -> str:
    normalized = url.strip().rstrip("/")
    return normalized if normalized.endswith(suffix) else normalized + suffix


def _json_object(response: httpx.Response, error_code: str) -> Mapping[str, Any]:
    try:
        value = response.json()
    except (ValueError, TypeError) as exc:
        raise HistoryIndexError(error_code) from exc
    if not isinstance(value, Mapping):
        raise HistoryIndexError(error_code)
    return value


def _response_ok(response: httpx.Response, unavailable: str, invalid: str) -> Mapping[str, Any]:
    if response.status_code < 200 or response.status_code >= 300:
        raise HistoryIndexError(unavailable)
    return _json_object(response, invalid)


def _qdrant_result(document: Mapping[str, Any]) -> None:
    result = document.get("result")
    if not isinstance(result, (Mapping, bool)):
        raise HistoryIndexError("vector_store_response_invalid")


class HistoryEmbeddingClient:
    """OpenAI-compatible embedding client with a deliberately small error surface."""

    def __init__(
        self,
        api_url: str,
        api_key: str = "",
        model: str = "",
        dimensions: int = 0,
        client: httpx.AsyncClient | None = None,
        timeout_seconds: float = 20.0,
    ) -> None:
        self.api_url = _clean_url(api_url, "/embeddings") if api_url.strip() else ""
        self.api_key = api_key
        self.model = model
        self.dimensions = dimensions
        self.timeout_seconds = max(0.1, float(timeout_seconds))
        self._owned_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=self.timeout_seconds, trust_env=False
        )

    async def close(self) -> None:
        if self._owned_client:
            await self._client.aclose()

    async def embed(self, text: str) -> list[float]:
        if not self.api_url or not self.model or not str(text).strip():
            raise HistoryIndexError("embedding_not_configured")
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        payload: dict[str, Any] = {"model": self.model, "input": text}
        if self.dimensions > 0:
            payload["dimensions"] = self.dimensions
        try:
            response = await self._client.post(self.api_url, headers=headers, json=payload)
        except httpx.HTTPError as exc:
            raise HistoryIndexError("embedding_unavailable") from exc
        document = _response_ok(
            response, "embedding_unavailable", "embedding_response_invalid"
        )
        data = document.get("data")
        if not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], Mapping):
            raise HistoryIndexError("embedding_response_invalid")
        vector = data[0].get("embedding")
        if not isinstance(vector, list) or not vector:
            raise HistoryIndexError("embedding_response_invalid")
        if self.dimensions > 0 and len(vector) != self.dimensions:
            raise HistoryIndexError("embedding_response_invalid")
        if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) for value in vector):
            raise HistoryIndexError("embedding_response_invalid")
        return [float(value) for value in vector]


class ConversationVectorStore:
    """Qdrant boundary that stores identifiers and hashes, never message bodies."""

    def __init__(
        self,
        qdrant_url: str,
        api_key: str = "",
        collection: str = "agent_conversation_messages",
        client: httpx.AsyncClient | None = None,
        timeout_seconds: float = 10.0,
    ) -> None:
        self.base_url = qdrant_url.strip().rstrip("/")
        self.api_key = api_key
        self.collection = collection or "agent_conversation_messages"
        self.timeout_seconds = max(0.1, float(timeout_seconds))
        self._owned_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=self.timeout_seconds, trust_env=False
        )

    async def close(self) -> None:
        if self._owned_client:
            await self._client.aclose()

    @property
    def _headers(self) -> dict[str, str]:
        return {"api-key": self.api_key} if self.api_key else {}

    def _url(self, path: str) -> str:
        if not self.base_url:
            raise HistoryIndexError("vector_store_not_configured")
        return f"{self.base_url}{path}"

    async def _request(self, method: str, path: str, payload: Mapping[str, Any]) -> Mapping[str, Any]:
        try:
            response = await self._client.request(
                method, self._url(path), headers=self._headers, json=payload
            )
        except httpx.HTTPError as exc:
            raise HistoryIndexError("vector_store_unavailable") from exc
        return _response_ok(response, "vector_store_unavailable", "vector_store_response_invalid")

    async def ensure_collection(self, dimensions: int) -> None:
        if dimensions < 1:
            raise HistoryIndexError("vector_store_response_invalid")
        collection_path = f"/collections/{self.collection}"
        document = await self._request(
            "PUT", collection_path,
            {"vectors": {"size": dimensions, "distance": "Cosine"}},
        )
        _qdrant_result(document)
        for field_name, field_schema in (("thread_id", "keyword"), ("message_id", "integer")):
            indexed = await self._request(
                "PUT", f"{collection_path}/index",
                {"field_name": field_name, "field_schema": field_schema},
            )
            _qdrant_result(indexed)

    async def upsert(self, message: IndexedMessage, vector: list[float]) -> None:
        if not vector or any(
            not isinstance(value, (int, float))
            or isinstance(value, bool)
            or not math.isfinite(value)
            for value in vector
        ):
            raise HistoryIndexError("vector_store_response_invalid")
        payload: dict[str, Any] = {
            "message_id": int(message.message_id),
            "thread_id": str(message.thread_id),
            "role": str(message.role),
            "content_sha256": hashlib.sha256(message.content.encode("utf-8")).hexdigest(),
        }
        if message.turn_id is not None:
            payload["turn_id"] = str(message.turn_id)
        if message.created_at is not None:
            payload["created_at"] = str(message.created_at)
        document = await self._request(
            "PUT", f"/collections/{self.collection}/points?wait=true",
            {"points": [{"id": int(message.message_id), "vector": vector, "payload": payload}]},
        )
        _qdrant_result(document)

    async def search(
        self,
        thread_id: str,
        max_message_id: int,
        vector: list[float],
        limit: int,
        min_score: float,
    ) -> list[HistoryVectorMatch]:
        if max_message_id < 1 or limit < 1:
            return []
        document = await self._request(
            "POST", f"/collections/{self.collection}/points/search",
            {
                "vector": vector,
                "limit": limit,
                "score_threshold": min_score,
                "with_payload": ["message_id"],
                "filter": {"must": [
                    {"key": "thread_id", "match": {"value": thread_id}},
                    {"key": "message_id", "range": {"lte": max_message_id}},
                ]},
            },
        )
        raw_matches = document.get("result")
        if not isinstance(raw_matches, list):
            raise HistoryIndexError("vector_store_response_invalid")
        matches: list[HistoryVectorMatch] = []
        for raw in raw_matches:
            if not isinstance(raw, Mapping) or not isinstance(raw.get("payload"), Mapping):
                raise HistoryIndexError("vector_store_response_invalid")
            try:
                message_id = int(raw["payload"]["message_id"])
                score = float(raw["score"])
            except (KeyError, TypeError, ValueError) as exc:
                raise HistoryIndexError("vector_store_response_invalid") from exc
            if message_id < 1 or not math.isfinite(score):
                raise HistoryIndexError("vector_store_response_invalid")
            matches.append(HistoryVectorMatch(message_id, score))
        return matches

    async def delete_thread(self, thread_id: str) -> None:
        document = await self._request(
            "POST", f"/collections/{self.collection}/points/delete?wait=true",
            {"filter": {"must": [{"key": "thread_id", "match": {"value": thread_id}}]}},
        )
        _qdrant_result(document)

    async def delete_point(self, message_id: int) -> None:
        document = await self._request(
            "POST", f"/collections/{self.collection}/points/delete?wait=true",
            {"points": [int(message_id)]},
        )
        _qdrant_result(document)


class HistoryIndexRepository:
    """PostgreSQL state transitions for indexing and eventually-consistent cleanup."""

    def __init__(self, database: Database):
        self.database = database

    async def claim_ready(
        self, worker_id: str, batch_size: int, lease_seconds: int = 60
    ) -> list[HistoryIndexJob]:
        limit = max(1, min(int(batch_size), 256))
        lease = max(1, int(lease_seconds))
        async with self.database.transaction() as connection:
            rows = await (await connection.execute(
                """
                WITH ready AS (
                    SELECT message_id
                    FROM agent_history_index_job
                    WHERE (status = 'pending' AND available_at <= CURRENT_TIMESTAMP)
                       OR (status = 'processing' AND lease_expires_at <= CURRENT_TIMESTAMP)
                    ORDER BY available_at, message_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT %s
                )
                UPDATE agent_history_index_job job
                SET status = 'processing', attempt_count = job.attempt_count + 1,
                    lease_owner = %s,
                    lease_expires_at = CURRENT_TIMESTAMP + (%s * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                FROM ready
                WHERE job.message_id = ready.message_id
                RETURNING job.message_id, job.status, job.attempt_count, job.lease_owner
                """, (limit, worker_id, lease)
            )).fetchall()
        return [
            HistoryIndexJob(
                int(row["message_id"]),
                str(row["status"]),
                int(row["attempt_count"]),
                str(row["lease_owner"]),
            )
            for row in rows
        ]

    async def indexable_message(self, message_id: int) -> IndexedMessage | None:
        async with self.database.connection() as connection:
            row = await (await connection.execute(
                """
                SELECT m.id, m.thread_id, m.turn_id, m.role, m.content, m.created_at
                FROM agent_message m
                INNER JOIN agent_turn tr ON tr.turn_id = m.turn_id
                WHERE m.id = %s
                  AND m.role IN ('user', 'assistant')
                  AND btrim(m.content) <> ''
                  AND tr.status = 'completed'
                """, (message_id,)
            )).fetchone()
        if row is None:
            return None
        created_at = row.get("created_at")
        timestamp = created_at.astimezone(timezone.utc).isoformat() if isinstance(created_at, datetime) else (str(created_at) if created_at else None)
        return IndexedMessage(int(row["id"]), str(row["thread_id"]), str(row["turn_id"]) if row.get("turn_id") is not None else None, str(row["role"]), str(row["content"]), timestamp)

    async def mark_completed(
        self, message_id: int, content_sha256: str, lease_owner: str
    ) -> bool:
        async with self.database.transaction() as connection:
            cursor = await connection.execute(
                """UPDATE agent_history_index_job
                   SET status = 'completed', indexed_content_sha256 = %s,
                       lease_owner = NULL, lease_expires_at = NULL, last_error_code = NULL,
                       updated_at = CURRENT_TIMESTAMP
                   WHERE message_id = %s AND status = 'processing'
                     AND lease_owner = %s AND lease_expires_at > CURRENT_TIMESTAMP""",
                (content_sha256 or None, message_id, lease_owner),
            )
        return bool(cursor.rowcount)

    async def mark_skipped(self, message_id: int, lease_owner: str) -> bool:
        async with self.database.transaction() as connection:
            cursor = await connection.execute(
                """UPDATE agent_history_index_job
                   SET status = 'skipped', lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
                   WHERE message_id = %s AND status = 'processing'
                     AND lease_owner = %s AND lease_expires_at > CURRENT_TIMESTAMP""",
                (message_id, lease_owner),
            )
        return bool(cursor.rowcount)

    async def release_retry(
        self, message_id: int, error_code: str, delay_seconds: int, lease_owner: str
    ) -> bool:
        async with self.database.transaction() as connection:
            cursor = await connection.execute(
                """UPDATE agent_history_index_job
                   SET status = 'pending', available_at = CURRENT_TIMESTAMP + (%s * INTERVAL '1 second'),
                       lease_owner = NULL, lease_expires_at = NULL, last_error_code = %s,
                       updated_at = CURRENT_TIMESTAMP
                   WHERE message_id = %s AND status = 'processing'
                     AND lease_owner = %s AND lease_expires_at > CURRENT_TIMESTAMP""",
                (max(0, int(delay_seconds)), error_code, message_id, lease_owner),
            )
        return bool(cursor.rowcount)

    async def mark_failed(
        self, message_id: int, error_code: str, lease_owner: str
    ) -> bool:
        async with self.database.transaction() as connection:
            cursor = await connection.execute(
                """UPDATE agent_history_index_job
                   SET status = 'failed', lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = %s, updated_at = CURRENT_TIMESTAMP
                   WHERE message_id = %s AND status = 'processing'
                     AND lease_owner = %s AND lease_expires_at > CURRENT_TIMESTAMP""",
                (error_code, message_id, lease_owner),
            )
        return bool(cursor.rowcount)

    async def claim_cleanup_ready(
        self, worker_id: str, batch_size: int, lease_seconds: int = 60
    ) -> list[HistoryCleanupJob]:
        return [
            *await self.claim_thread_cleanup_ready(worker_id, batch_size, lease_seconds),
            *await self.claim_message_cleanup_ready(worker_id, batch_size, lease_seconds),
        ]

    async def _claim_cleanup(
        self, table: str, id_column: str, kind: str, worker_id: str, batch_size: int, lease_seconds: int
    ) -> list[HistoryCleanupJob]:
        limit = max(1, min(int(batch_size), 256))
        async with self.database.transaction() as connection:
            rows = await (await connection.execute(
                f"""
                WITH ready AS (
                    SELECT {id_column}
                    FROM {table}
                    WHERE (status = 'pending' AND available_at <= CURRENT_TIMESTAMP)
                       OR (status = 'processing' AND lease_expires_at <= CURRENT_TIMESTAMP)
                    ORDER BY available_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT %s
                )
                UPDATE {table} job
                SET status = 'processing', attempt_count = job.attempt_count + 1,
                    lease_owner = %s,
                    lease_expires_at = CURRENT_TIMESTAMP + (%s * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                FROM ready
                WHERE job.{id_column} = ready.{id_column}
                RETURNING job.{id_column}, job.attempt_count, job.lease_owner
                """, (limit, worker_id, max(1, int(lease_seconds)))
            )).fetchall()
        return [
            HistoryCleanupJob(
                kind,
                str(row[id_column]),
                int(row["attempt_count"]),
                str(row["lease_owner"]),
            )
            for row in rows
        ]

    async def claim_thread_cleanup_ready(self, worker_id: str, batch_size: int, lease_seconds: int = 60) -> list[HistoryCleanupJob]:
        return await self._claim_cleanup("agent_history_vector_cleanup_job", "thread_id", "thread", worker_id, batch_size, lease_seconds)

    async def claim_message_cleanup_ready(self, worker_id: str, batch_size: int, lease_seconds: int = 60) -> list[HistoryCleanupJob]:
        return await self._claim_cleanup("agent_history_message_cleanup_job", "message_id", "message", worker_id, batch_size, lease_seconds)

    async def finish_cleanup(
        self,
        job: HistoryCleanupJob,
        *,
        deleted: bool,
        terminal: bool = False,
        error_code: str = "",
        delay_seconds: int = 0,
    ) -> bool:
        if job.kind == "thread":
            return await self.finish_thread_cleanup(
                job.identifier,
                lease_owner=job.lease_owner,
                deleted=deleted,
                terminal=terminal,
                error_code=error_code,
                delay_seconds=delay_seconds,
            )
        return await self.finish_message_cleanup(
            int(job.identifier),
            lease_owner=job.lease_owner,
            deleted=deleted,
            terminal=terminal,
            error_code=error_code,
            delay_seconds=delay_seconds,
        )

    async def _finish_cleanup(
        self,
        table: str,
        id_column: str,
        identifier: str | int,
        *,
        lease_owner: str,
        deleted: bool,
        terminal: bool,
        error_code: str,
        delay_seconds: int,
    ) -> bool:
        status = "completed" if deleted else ("failed" if terminal else "pending")
        async with self.database.transaction() as connection:
            cursor = await connection.execute(
                f"""UPDATE {table}
                   SET status = %s, available_at = CURRENT_TIMESTAMP + (%s * INTERVAL '1 second'),
                       lease_owner = NULL, lease_expires_at = NULL, last_error_code = %s,
                       updated_at = CURRENT_TIMESTAMP
                   WHERE {id_column} = %s AND status = 'processing'
                     AND lease_owner = %s AND lease_expires_at > CURRENT_TIMESTAMP""",
                (
                    status,
                    max(0, int(delay_seconds)),
                    error_code or None,
                    identifier,
                    lease_owner,
                ),
            )
        return bool(cursor.rowcount)

    async def finish_thread_cleanup(
        self,
        thread_id: str,
        *,
        lease_owner: str,
        deleted: bool,
        terminal: bool = False,
        error_code: str = "",
        delay_seconds: int = 0,
    ) -> bool:
        return await self._finish_cleanup(
            "agent_history_vector_cleanup_job",
            "thread_id",
            thread_id,
            lease_owner=lease_owner,
            deleted=deleted,
            terminal=terminal,
            error_code=error_code,
            delay_seconds=delay_seconds,
        )

    async def finish_message_cleanup(
        self,
        message_id: int,
        *,
        lease_owner: str,
        deleted: bool,
        terminal: bool = False,
        error_code: str = "",
        delay_seconds: int = 0,
    ) -> bool:
        return await self._finish_cleanup(
            "agent_history_message_cleanup_job",
            "message_id",
            message_id,
            lease_owner=lease_owner,
            deleted=deleted,
            terminal=terminal,
            error_code=error_code,
            delay_seconds=delay_seconds,
        )

    async def job_status(self, message_id: int) -> HistoryIndexJob | None:
        async with self.database.connection() as connection:
            row = await (await connection.execute(
                """SELECT message_id, status, attempt_count, lease_owner
                   FROM agent_history_index_job WHERE message_id = %s""",
                (message_id,),
            )).fetchone()
        return None if row is None else HistoryIndexJob(
            int(row["message_id"]),
            str(row["status"]),
            int(row["attempt_count"]),
            str(row.get("lease_owner") or ""),
        )

    async def requeue_failed_jobs(self) -> int:
        async with self.database.transaction() as connection:
            cursor = await connection.execute(
                """UPDATE agent_history_index_job SET status = 'pending', available_at = CURRENT_TIMESTAMP,
                   attempt_count = 0, lease_owner = NULL, lease_expires_at = NULL,
                   last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
                   WHERE status = 'failed'"""
            )
        return max(0, int(cursor.rowcount or 0))

    async def requeue_failed_cleanup_jobs(self) -> int:
        total = 0
        for table in (
            "agent_history_vector_cleanup_job",
            "agent_history_message_cleanup_job",
        ):
            async with self.database.transaction() as connection:
                cursor = await connection.execute(
                    f"""UPDATE {table}
                       SET status = 'pending', attempt_count = 0,
                           available_at = CURRENT_TIMESTAMP, lease_owner = NULL,
                           lease_expires_at = NULL, last_error_code = NULL,
                           updated_at = CURRENT_TIMESTAMP
                       WHERE status = 'failed'"""
                )
            total += max(0, int(cursor.rowcount or 0))
        return total

    async def rebuild_all_jobs(self) -> int:
        async with self.database.transaction() as connection:
            cursor = await connection.execute(
                """INSERT INTO agent_history_index_job(message_id, status, available_at, created_at, updated_at)
                   SELECT id, 'pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                   FROM agent_message
                   ON CONFLICT (message_id) DO UPDATE SET status = 'pending', attempt_count = 0,
                     available_at = CURRENT_TIMESTAMP, lease_owner = NULL, lease_expires_at = NULL,
                     indexed_content_sha256 = NULL, last_error_code = NULL, updated_at = CURRENT_TIMESTAMP"""
            )
        return max(0, int(cursor.rowcount or 0))

class ConversationHistoryWorker:
    def __init__(self, repository: Any, embedder: Any, vector_store: Any, worker_id: str, *, batch_size: int = 16, max_attempts: int = 8, lease_seconds: int = 60) -> None:
        self.repository = repository
        self.embedder = embedder
        self.vector_store = vector_store
        self.worker_id = worker_id
        self.batch_size = max(1, batch_size)
        self.max_attempts = max(1, max_attempts)
        self.lease_seconds = max(1, lease_seconds)

    async def ensure_collection(self, dimensions: int) -> None:
        ensure = getattr(self.vector_store, "ensure_collection", None)
        if ensure is not None:
            result = ensure(dimensions)
            if inspect.isawaitable(result):
                await result

    async def close(self) -> None:
        """关闭 worker 持有的共享 HTTP 客户端；注入的测试替身无需实现。"""
        closed: set[int] = set()
        for dependency in (self.embedder, self.vector_store):
            if id(dependency) in closed:
                continue
            closed.add(id(dependency))
            close = getattr(dependency, "close", None)
            if close is None:
                continue
            result = close()
            if inspect.isawaitable(result):
                await result

    @staticmethod
    def _error_code(error: BaseException) -> str:
        return error.code if isinstance(error, HistoryIndexError) else "history_index_unavailable"

    @staticmethod
    def _delay(attempt_count: int) -> int:
        return min(3600, 5 * (2 ** max(0, attempt_count - 1)))

    @staticmethod
    def _dependency_timeout_seconds(dependency: Any, fallback: float) -> float:
        try:
            return max(0.1, float(getattr(dependency, "timeout_seconds", fallback)))
        except (TypeError, ValueError):
            return fallback

    def _index_lease_seconds(self) -> int:
        embedding_timeout = self._dependency_timeout_seconds(self.embedder, 20.0)
        vector_timeout = self._dependency_timeout_seconds(self.vector_store, 10.0)
        return max(
            self.lease_seconds,
            math.ceil(self.batch_size * (embedding_timeout + vector_timeout)) + 5,
        )

    def _cleanup_lease_seconds(self) -> int:
        vector_timeout = self._dependency_timeout_seconds(self.vector_store, 10.0)
        # Cleanup claims one batch for threads and one for messages, then handles
        # both batches serially through the same Qdrant client.
        return max(
            self.lease_seconds,
            math.ceil(self.batch_size * 2 * vector_timeout) + 5,
        )

    async def run_once(self) -> HistoryIndexRunResult:
        result = HistoryIndexRunResult()
        jobs = await self.repository.claim_ready(
            self.worker_id, self.batch_size, self._index_lease_seconds()
        )
        for job in jobs:
            try:
                message = await self.repository.indexable_message(job.message_id)
                if message is None:
                    transitioned = await self.repository.mark_skipped(
                        job.message_id, job.lease_owner
                    )
                    result = replace(
                        result,
                        skipped=result.skipped + int(transitioned),
                        lease_lost=result.lease_lost + int(not transitioned),
                    )
                    continue
                vector = await self.embedder.embed(message.content)
                await self.vector_store.upsert(message, vector)
                transitioned = await self.repository.mark_completed(
                    job.message_id,
                    hashlib.sha256(message.content.encode("utf-8")).hexdigest(),
                    job.lease_owner,
                )
                result = replace(
                    result,
                    completed=result.completed + int(transitioned),
                    lease_lost=result.lease_lost + int(not transitioned),
                )
            except Exception as error:
                code = self._error_code(error)
                if job.attempt_count >= self.max_attempts:
                    transitioned = await self.repository.mark_failed(
                        job.message_id, code, job.lease_owner
                    )
                    result = replace(
                        result,
                        failed=result.failed + int(transitioned),
                        lease_lost=result.lease_lost + int(not transitioned),
                    )
                else:
                    transitioned = await self.repository.release_retry(
                        job.message_id,
                        code,
                        self._delay(job.attempt_count),
                        job.lease_owner,
                    )
                    result = replace(
                        result,
                        retried=result.retried + int(transitioned),
                        lease_lost=result.lease_lost + int(not transitioned),
                    )
        cleanup_jobs = await self.repository.claim_cleanup_ready(
            self.worker_id, self.batch_size, self._cleanup_lease_seconds()
        )
        for job in cleanup_jobs:
            try:
                if job.kind == "thread":
                    await self.vector_store.delete_thread(job.identifier)
                    cleaned_field = "thread_cleaned"
                else:
                    await self.vector_store.delete_point(int(job.identifier))
                    cleaned_field = "message_cleaned"
                transitioned = await self.repository.finish_cleanup(job, deleted=True)
                result = replace(
                    result,
                    **{
                        cleaned_field: getattr(result, cleaned_field) + int(transitioned),
                        "lease_lost": result.lease_lost + int(not transitioned),
                    },
                )
            except Exception as error:
                terminal = job.attempt_count >= self.max_attempts
                transitioned = await self.repository.finish_cleanup(
                    job,
                    deleted=False,
                    terminal=terminal,
                    error_code=self._error_code(error),
                    delay_seconds=0 if terminal else self._delay(job.attempt_count),
                )
                result = replace(
                    result,
                    failed=result.failed + int(terminal and transitioned),
                    retried=result.retried + int(not terminal and transitioned),
                    lease_lost=result.lease_lost + int(not transitioned),
                )
        return result


class ConversationHistoryRetriever:
    def __init__(self, repository: Any, embedder: Any, vector_store: Any, *, enabled: bool = False, limit: int = 4, character_limit: int = 1800, min_score: float = 0.55) -> None:
        self.repository = repository
        self.embedder = embedder
        self.vector_store = vector_store
        self.enabled = enabled
        self.limit = max(1, min(limit, 12))
        self.character_limit = max(1, character_limit)
        self.min_score = max(0.0, min(float(min_score), 1.0))

    async def retrieve(self, thread_id: str, owner_id: str, scope_type: str, scope_id: str | int, max_message_id: int, query: str) -> list[RetrievedHistoryMessage]:
        if not self.enabled or max_message_id < 1 or not str(query).strip():
            return []
        try:
            vector = await self.embedder.embed(query)
            matches = await self.vector_store.search(thread_id, max_message_id, vector, self.limit, self.min_score)
            message_ids = [match.message_id for match in matches[:self.limit]]
            if not message_ids:
                return []
            hydrated = await self.repository.hydrate_history_messages(message_ids, thread_id, owner_id, scope_type, scope_id, max_message_id)
        except Exception:
            return []
        selected: list[RetrievedHistoryMessage] = []
        used = 0
        scores = {match.message_id: match.score for match in matches}
        for message in hydrated[:self.limit]:
            remaining = self.character_limit - used
            if remaining <= 0:
                break
            if len(message.content) > remaining:
                continue
            content = message.content
            if not content:
                continue
            selected.append(RetrievedHistoryMessage(
                message.message_id,
                message.thread_id,
                message.turn_id,
                message.role,
                content,
                scores.get(message.message_id, message.score),
            ))
            used += len(content)
        return selected
