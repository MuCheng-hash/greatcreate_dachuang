from __future__ import annotations

from typing import Any, Mapping

import httpx


class BusinessToolError(RuntimeError):
    """A controlled failure while calling the Java business tool boundary."""

    def __init__(self, reason: str, message: str | None = None):
        self.reason = reason
        super().__init__(message or reason)


class BusinessToolClient:
    """通过一个共享 AsyncClient 调用 Java 的认证工具接口。"""

    KNOWLEDGE_RETRIEVE_PATH = "/internal/agent/tools/knowledge-retrieve"
    RELATION_QUERY_PATH = "/internal/agent/tools/relation-query"
    WEB_SOURCE_DOMAINS_PATH = "/internal/agent/tools/web-source-domains"

    def __init__(
        self,
        base_url: str,
        service_token: str,
        timeout_seconds: float = 5.0,
        client: httpx.AsyncClient | None = None,
        write_tools_enabled: bool = False,
    ):
        self.base_url = base_url.strip().rstrip("/")
        self.service_token = service_token.strip()
        self.timeout_seconds = max(0.5, float(timeout_seconds))
        self._client = client or httpx.AsyncClient(
            timeout=self.timeout_seconds, trust_env=False
        )
        self._owns_client = client is None
        self.write_tools_enabled = bool(write_tools_enabled)

    @property
    def configured(self) -> bool:
        return bool(self.base_url and self.service_token)

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def query_knowledge(
        self, payload: Mapping[str, Any]
    ) -> dict[str, Any]:
        return await self._post_retrieval(self.KNOWLEDGE_RETRIEVE_PATH, payload)

    async def query_graph_relations(
        self, payload: Mapping[str, Any]
    ) -> dict[str, Any]:
        return await self._post_retrieval(self.RELATION_QUERY_PATH, payload)

    async def execute_write(
        self,
        path: str,
        payload: Mapping[str, Any],
        *,
        action_id: str,
        turn_id: str,
    ) -> dict[str, Any]:
        """未来写工具的唯一 HTTP 出口；默认关闭且强制端到端幂等键。"""
        if not self.write_tools_enabled:
            raise BusinessToolError("write_tools_disabled")
        if not action_id.strip() or not turn_id.strip():
            raise BusinessToolError("write_tool_idempotency_required")
        normalized_path = path.strip()
        if not normalized_path.startswith("/internal/agent/actions/"):
            raise BusinessToolError("write_tool_path_rejected")
        try:
            response = await self._client.post(
                f"{self.base_url}{normalized_path}",
                headers={
                    **self._headers(),
                    "Idempotency-Key": action_id,
                    "X-Agent-Turn-Id": turn_id,
                },
                json=dict(payload),
            )
        except httpx.TimeoutException as exc:
            raise BusinessToolError("business_tool_timeout") from exc
        except httpx.HTTPError as exc:
            raise BusinessToolError("business_tool_transport_error") from exc
        if response.status_code == 409:
            conflict_code = "idempotency_conflict"
            try:
                envelope = response.json()
                data = envelope.get("data") if isinstance(envelope, dict) else None
                candidate = data.get("code") if isinstance(data, dict) else None
                if candidate in {"idempotency_conflict", "action_in_progress"}:
                    conflict_code = candidate
            except ValueError:
                pass
            raise BusinessToolError(conflict_code)
        if response.status_code == 202:
            raise BusinessToolError("action_in_progress")
        if response.status_code != 200:
            raise BusinessToolError(f"business_tool_http_{response.status_code}")
        try:
            envelope = response.json()
        except ValueError as exc:
            raise BusinessToolError("business_tool_invalid_json") from exc
        if not isinstance(envelope, dict) or envelope.get("code") != 200:
            raise BusinessToolError("business_tool_rejected")
        data = envelope.get("data")
        return dict(data) if isinstance(data, dict) else {}

    async def web_source_domains(self) -> list[str]:
        if not self.configured:
            raise BusinessToolError("business_tool_unconfigured")
        try:
            response = await self._client.get(
                f"{self.base_url}{self.WEB_SOURCE_DOMAINS_PATH}",
                headers=self._headers(),
            )
            response.raise_for_status()
            envelope = response.json()
            data = envelope.get("data") if isinstance(envelope, dict) else None
            domains = data.get("domains") if isinstance(data, dict) else None
            return [
                str(item).strip().lower()
                for item in domains or []
                if str(item).strip()
            ]
        except httpx.TimeoutException as exc:
            raise BusinessToolError("business_tool_timeout") from exc
        except (httpx.HTTPError, ValueError) as exc:
            raise BusinessToolError("business_tool_transport_error") from exc

    async def health(self, path: str) -> None:
        if not self.base_url:
            raise BusinessToolError("business_tool_unconfigured")
        try:
            response = await self._client.get(
                f"{self.base_url}{path}", headers=self._headers(accept_json=True)
            )
            response.raise_for_status()
            payload = response.json()
        except httpx.TimeoutException as exc:
            raise BusinessToolError("business_tool_timeout") from exc
        except (httpx.HTTPError, ValueError, TypeError) as exc:
            raise BusinessToolError("business_tool_transport_error") from exc
        if isinstance(payload, dict) and "code" in payload and payload.get("code") != 200:
            raise BusinessToolError("business_tool_rejected")

    async def _post_retrieval(
        self, path: str, payload: Mapping[str, Any]
    ) -> dict[str, Any]:
        if not self.configured:
            raise BusinessToolError("business_tool_unconfigured")
        try:
            response = await self._client.post(
                f"{self.base_url}{path}",
                headers=self._headers(),
                json=dict(payload),
            )
        except httpx.TimeoutException as exc:
            raise BusinessToolError("business_tool_timeout") from exc
        except httpx.HTTPError as exc:
            raise BusinessToolError("business_tool_transport_error") from exc
        if response.status_code != 200:
            if response.status_code in {401, 403}:
                reason = "business_tool_unauthorized"
            elif response.status_code >= 500:
                reason = "business_tool_server_error"
            else:
                reason = f"business_tool_http_{response.status_code}"
            raise BusinessToolError(reason)
        try:
            envelope = response.json()
        except ValueError as exc:
            raise BusinessToolError("business_tool_invalid_json") from exc
        if not isinstance(envelope, dict) or envelope.get("code") != 200:
            raise BusinessToolError("business_tool_rejected")
        data = envelope.get("data")
        if not isinstance(data, dict):
            raise BusinessToolError("business_tool_empty_result")
        result = dict(data)
        result["source"] = "business-service"
        return result

    def _headers(self, *, accept_json: bool = False) -> dict[str, str]:
        headers = {"X-Agent-Service-Token": self.service_token}
        if accept_json:
            headers["Accept"] = "application/json"
        return headers
