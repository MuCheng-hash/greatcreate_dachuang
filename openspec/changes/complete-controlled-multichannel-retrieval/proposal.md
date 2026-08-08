## Why

当前检索已具备 Dense、MySQL 全文、RRF 与受限 GraphRAG，但无法处理依赖会话上下文的指代问题，也没有在本地低召回时补充 HyDE、权威 Web 与语义精排。

## What Changes

- 增加 Agent 受控查询改写、HyDE 与 Tavily 白名单 Web 编排。
- 在 Java 检索边界融合 HyDE、Web、Dense 与 Lexical 候选，并接入可降级的云端精排。
- 增加管理员维护的 RAG 权威域名白名单、内部读取接口和调试 Trace。

## Impact

- `llm-service` 检索工具和模型配置。
- `business-service` RAG 检索、管理员 API、SQL 迁移与静态后台。
- 既有问答、SSE、`KnowledgeRetriever` 和 Agent 工具保留兼容。
