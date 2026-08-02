## Why

智能问答在向量库停止时会退化到关键词检索，但当前状态文案无法说明实际使用的检索方式；同时，历史会话只恢复正文，用户切换会话后会丢失来源、模型、检索状态和执行摘要。需要让检索可用性可验证，并让已经展示给用户的稳定结果能够跨会话恢复。

## What Changes

- 将 Qdrant 健康检查、索引同步和向量检索验收纳入本地启动流程，继续保留关键词兜底。
- 在问答结果中增加可选的检索方式列表，用于区分向量混合检索、关键词兜底和图谱证据。
- 在 Agent SQLite 消息元数据中保存版本化的最终展示快照，包括完整引用、状态、模型、工具摘要、追问和记忆使用信息。
- 历史页面优先从展示快照恢复来源、状态、模型和脱敏后的折叠执行摘要，并兼容没有快照的旧消息。
- 不保存逐字流事件、运行中状态、原始工具输出、提示词或业务上下文 JSON。

## Capabilities

### New Capabilities

- `assistant-rich-history`: 定义智能问答最终展示快照的持久化、兼容读取、脱敏执行摘要和跨会话恢复行为。
- `rag-runtime-readiness`: 定义 Qdrant 启动健康检查、索引就绪、检索方式报告和关键词降级行为。

### Modified Capabilities

- 无。

## Impact

- `llm-service` 的问答响应模型、引用兜底和 SQLite 消息元数据。
- `business-service` 的知识检索结果归一化、Agent 最终响应和本地 RAG 启动流程。
- `business-service/frontend` 的历史消息恢复、检索状态文案与执行摘要。
- 对外问答响应新增可选 `retrievalMethods`，历史消息元数据新增可选 `responseSnapshot`；现有接口路径和必填字段不变。
- 复用现有 Qdrant 命名卷和 Agent SQLite，不新增数据库表。
