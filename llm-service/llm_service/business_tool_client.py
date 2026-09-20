from __future__ import annotations

from typing import Any, Mapping

import httpx


class BusinessToolError(RuntimeError):
    """调用 Java 业务工具边界时的受控失败。

    ``reason`` 是供上层决定降级、重试或向用户反馈的稳定机器标识；不透传
    下游服务的响应正文，避免把内部实现和潜在敏感信息带出可信服务边界。
    """

    def __init__(self, reason: str, message: str | None = None):
        self.reason = reason
        super().__init__(message or reason)


class BusinessToolClient:
    """通过一个共享 ``AsyncClient`` 调用 Java 的认证工具接口。

    该类是 LLM 服务访问业务事实的唯一 HTTP 边界。调用者传入的查询条件仍由
    业务服务按服务令牌和范围校验；本类只负责传输、统一响应信封校验，以及将
    可预期的网络和协议失败转换为 ``BusinessToolError``，不在本地伪造结果。
    """

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
        """保存认证边界配置，并在未注入客户端时取得其关闭责任。"""
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
        """返回是否具备发起已认证业务请求所需的地址和服务令牌。"""
        return bool(self.base_url and self.service_token)

    async def aclose(self) -> None:
        """仅关闭由本实例创建的客户端，避免误关闭外部共享连接池。"""
        if self._owns_client:
            await self._client.aclose()

    async def query_knowledge(
        self, payload: Mapping[str, Any], *, tool_authorization: str,
        client_turn_id: str
    ) -> dict[str, Any]:
        """向受认证的知识检索端点请求事实和引用候选。

        ``payload`` 中的范围、检索词和过滤条件直接交给业务服务裁决；服务不可用
        时抛出受控错误，由工具层决定是否从当前轮次的可信上下文降级。
        """
        return await self._post_retrieval(
            self.KNOWLEDGE_RETRIEVE_PATH, payload, tool_authorization=tool_authorization,
            client_turn_id=client_turn_id
        )

    async def query_graph_relations(
        self, payload: Mapping[str, Any], *, tool_authorization: str,
        client_turn_id: str
    ) -> dict[str, Any]:
        """向受认证的图谱端点请求关系事实，失败语义与知识检索保持一致。"""
        return await self._post_retrieval(
            self.RELATION_QUERY_PATH, payload, tool_authorization=tool_authorization,
            client_turn_id=client_turn_id
        )

    async def execute_write(
        self,
        path: str,
        payload: Mapping[str, Any],
        *,
        action_id: str,
        turn_id: str,
    ) -> dict[str, Any]:
        """执行已确认写操作的唯一 HTTP 出口。

        写工具默认关闭，且只接受 ``/internal/agent/actions/`` 范围的路径；
        ``action_id`` 和 ``turn_id`` 分别作为跨重试幂等键和审计关联键传给业务
        服务，防止模型或网络重放造成重复写入。
        """
        if not self.write_tools_enabled:
            raise BusinessToolError("写入工具已禁用")
        if not action_id.strip() or not turn_id.strip():
            raise BusinessToolError("写入工具需要幂等键")
        normalized_path = path.strip()
        if not normalized_path.startswith("/internal/agent/actions/"):
            raise BusinessToolError("写入工具路径被拒绝")
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
        """读取业务服务维护的权威网页域名白名单。

        白名单是业务侧可信配置而非模型输出；配置缺失、超时或响应失效均显式失败，
        由上层选择跳过联网增强而不是扩大检索范围。
        """
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
        """检查指定业务健康端点，并将非成功信封归一为受控错误。"""
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
        self, path: str, payload: Mapping[str, Any], *, tool_authorization: str,
        client_turn_id: str
    ) -> dict[str, Any]:
        """提交只读检索请求并校验业务服务的 ``code=200`` 响应信封。

        返回值会写入 ``source`` 以保留证据来源；任何非 200、非 JSON 或空数据都
        不被当作空检索结果，从而让调用方可以区分“无命中”和“不可用”。
        """
        if not self.configured:
            raise BusinessToolError("business_tool_unconfigured")
        if not tool_authorization.strip():
            raise BusinessToolError("tool_context_authorization_missing")
        if not client_turn_id.strip():
            raise BusinessToolError("tool_context_turn_missing")
        try:
            response = await self._client.post(
                f"{self.base_url}{path}",
                headers={
                    **self._headers(),
                    "X-Agent-Tool-Context": tool_authorization,
                    "X-Agent-Client-Turn-Id": client_turn_id,
                },
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
        """构造内部服务认证头；令牌不进入工具审计参数或模型上下文。"""
        headers = {"X-Agent-Service-Token": self.service_token}
        if accept_json:
            headers["Accept"] = "application/json"
        return headers
