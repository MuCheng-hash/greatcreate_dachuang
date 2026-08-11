## Why

知识文档导入目前是一个不完整的 MVP：中文文本切片不可靠，解析和视觉增强失败会被静默忽略，并且 worker 写入的向量集合与主 RAG 检索集合不同，导致上传后的内容不能稳定参与智能问答。需要把导入过程提升为可观察、可恢复且可演示的闭环。

## What Changes

- 将知识文档 worker 与主 RAG 使用的 Qdrant collection、向量 payload 和索引版本统一，保证成功导入的内容可被问答检索和引用。
- 增加可观察的导入状态机、节点结果、降级原因和可控重试。
- 用结构化解析、中文友好切片、JSON 结构化元数据和可降级的视觉理解替换当前的脆弱实现。
- 为管理端提供完整的导入任务详情、标准化 Markdown 下载和安全节点重试。
- 修复管理端知识库页面缺失请求客户端而无法构建的问题。

## Capabilities

### New Capabilities

- `knowledge-ingestion-pipeline`: 将上传文档解析、增强、切片、索引和发布为可恢复、可观察的统一主 RAG 数据链路。
- `knowledge-ingestion-operations`: 为管理员展示任务步骤、降级和索引结果，并支持下载和安全重试。

### Modified Capabilities

- 无。

## Impact

- 影响 `ingestion-worker`、知识文档 Spring API/存储模型、主 RAG Qdrant 契约及管理端知识库页面。
- 增加 MinerU 兼容解析服务、VLM 和模型网关的可选配置；继续使用 MinIO、Redis、MySQL 和 Qdrant。
- 现有 `/api/knowledge-documents` API 保持兼容，详情和重试请求增加可选字段。
