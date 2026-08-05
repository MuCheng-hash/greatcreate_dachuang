## Why

P0 已经完成 Dense、MySQL FULLTEXT 和按意图启用 Graph 的混合召回，但当前结果只按 RRF 排名，图谱多跳事实丢失真实路径，索引启动时仍会重复生成全部向量，且用户无法从 Agent 调试界面确认内部 Graph 是否执行。需要在保持问答和工具接口兼容的前提下，提高证据相关性、图谱可解释性、索引效率和运行可观测性。

## What Changes

- 增加可序列化的检索 Trace，并在显式调试请求、LLM Trace 元数据和管理后台展示 Dense、Lexical、Graph 与重排状态。
- 将 Neo4j 结果改为结构化一至三跳路径，限制关系白名单，并只扩展经 MySQL 审核的实体。
- 在 RRF 候选上增加实体、年级、主题、来源可信度和图谱距离的确定性业务重排。
- 为向量和全文检索构建同一份增强元数据文本，并使用内容 Hash、模型和索引版本进行增量同步。
- 使用版本化 Qdrant 物理 Collection 和 Alias 原子切换，失败时保留旧索引。
- 增加 24 条检索评测集及 Java、Python、前端和真实服务验收。

## Capabilities

### New Capabilities

- `rag-retrieval-quality`: 定义结构化 GraphRAG、业务重排、联合证据顺序和审核过滤。
- `rag-index-lifecycle`: 定义增强检索文本、增量索引、陈旧 Point 删除和版本化 Collection 切换。
- `agent-retrieval-observability`: 定义调试 SSE、LLM Trace 元数据和管理界面的内部检索可见性。

### Modified Capabilities

- 无。

## Impact

- `business-service` 检索、RAG 索引、Qdrant 客户端、Agent SSE、SQL Schema 和接口文档。
- `llm-service` 可信证据上下文和 observability metadata。
- Vue Agent 调试页及静态管理后台时间线。
- `content_chunk` 增加检索文本和索引版本字段，并重建 ngram FULLTEXT 索引。
- 现有问答、SSE、Agent 工具和 `KnowledgeRetriever` 方法保持兼容；新增字段均为可选。
