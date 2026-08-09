from __future__ import annotations

from typing import Any, Mapping

import httpx


class BusinessToolError(RuntimeError):
    """A controlled failure while calling the Java business tool boundary."""

    def __init__(self, reason: str, message: str | None = None):
        self.reason = reason
        super().__init__(message or reason)


class BusinessToolClient:
    """Calls Java's authenticated, non-Cypher Agent tool endpoints."""

    KNOWLEDGE_RETRIEVE_PATH = "/internal/agent/tools/knowledge-retrieve"
    RELATION_QUERY_PATH = "/internal/agent/tools/relation-query"
    WEB_SOURCE_DOMAINS_PATH = "/internal/agent/tools/web-source-domains"

    def __init__(
        self,
        base_url: str,
        service_token: str,
        timeout_seconds: float = 5.0,
        client: httpx.Client | None = None,
    ):
        self.base_url = base_url.strip().rstrip("/")
        self.service_token = service_token.strip()
        self.timeout_seconds = max(0.5, float(timeout_seconds))
        self._client = client or httpx.Client(timeout=self.timeout_seconds)

    @property
    def configured(self) -> bool:
        return bool(self.base_url and self.service_token)

    def query_knowledge(self, payload: Mapping[str, Any]) -> dict[str, Any]:
        return self._post_retrieval(self.KNOWLEDGE_RETRIEVE_PATH, payload)

    def query_graph_relations(self, payload: Mapping[str, Any]) -> dict[str, Any]:
        return self._post_retrieval(self.RELATION_QUERY_PATH, payload)

    def web_source_domains(self) -> list[str]:
        if not self.configured:
            raise BusinessToolError("business_tool_unconfigured")
        try:
            response = self._client.get(
                f"{self.base_url}{self.WEB_SOURCE_DOMAINS_PATH}",
                headers={"X-Agent-Service-Token": self.service_token},
            )
            response.raise_for_status()
            envelope = response.json()
            data = envelope.get("data") if isinstance(envelope, dict) else None
            domains = data.get("domains") if isinstance(data, dict) else None
            return [str(item).strip().lower() for item in domains or [] if str(item).strip()]
        except httpx.TimeoutException as exc:
            raise BusinessToolError("business_tool_timeout") from exc
        except (httpx.HTTPError, ValueError) as exc:
            raise BusinessToolError("business_tool_transport_error") from exc

    def _post_retrieval(
        self, path: str, payload: Mapping[str, Any]
    ) -> dict[str, Any]:
        if not self.configured:
            raise BusinessToolError("business_tool_unconfigured")

        try:
            response = self._client.post(
                f"{self.base_url}{path}",
                headers={"X-Agent-Service-Token": self.service_token},
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
