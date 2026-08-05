## Context

当前检索器先由 Dense 和 Lexical 生成候选并执行 RRF，再加载 Chunk 和来源；Graph 事实独立追加到引用中。附近资源图谱已经能扩展 Resource ID，但多跳查询使用无限定关系类型的无向路径，并仅生成“存在一至三跳关联”的文本。`RagIndexService` 在每次启动时读取并嵌入全部 Chunk，Qdrant payload 仅保存 `chunk_id` 与 `entity_key`。

## Goals / Non-Goals

**Goals:**

- 在 RRF 保持主导的前提下，使用可解释、可配置的业务特征提高证据排序。
- 图谱事实包含可审计的节点、关系、跳数、距离和路径边。
- 未审核或停用实体不能通过图谱陈旧数据重新进入候选。
- 未变化的 Chunk 不产生 Embedding 或 Qdrant Upsert。
- 调试页和管理后台可明确区分内部检索与 LLM 工具调用。

**Non-Goals:**

- Cross-Encoder、HyDE、Web Search、Qdrant Sparse、LLM 查询改写。
- 定时索引任务或实体更新事件监听。
- 自动删除旧 Qdrant Collection 或自动执行 MySQL 迁移。

## Decisions

1. 使用内部 `RankedEvidence` 统一排序 Chunk 和 Graph Fact。公开的 `chunks`、`graphFacts` 保持分组返回；`citationCandidates` 增加 `evidenceType`、`score`、`rank`、`retrievalMethod` 并按联合顺序排列，Python 只取前八条可排名证据作为上下文。

2. 重排权重固定为 base `0.60`、entity `0.15`、grade `0.10`、theme `0.08`、source `0.03`、graph `0.04`。仅对当前查询可用的特征归一化。审核状态为硬过滤，不参与加分。

3. 年级按小学低/中/高、初中、高中归一化；主题使用固定领域词表，不调用模型。来源优先使用同实体同来源的 `credibilityScore`，再使用 `reliabilityLevel`，缺失取 `0.6`。

4. 多跳 Graph 查询只允许方案列出的关系类型，禁止 `HAS_TAG`。学校查询先锚定 `SCHOOL_NEAR_RESOURCE`，学校最多三跳、资源最多两跳。Graph 扩展实体必须在 MySQL 中存在、审核通过且启用。

5. `retrieval_text` 保存稳定的实体元数据，Dense 文本为元数据、标题和正文。Hash 使用索引版本、模型、维度和规范化全文的 SHA-256。Qdrant payload 同步保存这些应用级版本字段。

6. `qdrant-collection` 继续表示旧物理集合和名称前缀，新增默认 Alias `red_culture_content_chunks_active`。读取优先 Alias，不存在时回退旧集合。模型或索引版本变化时构建新物理集合，仅在全部批次成功后原子切换；旧集合不自动删除。

7. `retrievalTrace` 随可信检索结果传入 Python，但提示词构造只显式选择证据字段。Python 将精简 Trace 写入现有 LLM Trace metadata。只有 `AgentQaRequest.debug=true` 的 SSE `phase.completed` 返回详细 Trace；内部 Graph 不计入工具数。

## Risks / Trade-offs

- [元数据变化需要扫描所有 Chunk 才能比较 Hash] -> 批量加载实体元数据，避免 N+1；扫描允许，Embedding 和 Upsert 必须增量。
- [新旧向量模型混合] -> 使用版本化物理 Collection 和 Alias，切换前不对外查询新集合。
- [图谱路径爆炸或弱语义路径] -> 锚定首跳、关系白名单、最大跳数、候选上限和路径去重。
- [业务特征压过语义相关性] -> base 权重保持 0.60，所有特征限制在 `[0,1]` 并输出贡献明细。
- [数据库迁移未执行导致启动失败] -> 文档固定迁移先于应用部署，应用不自行变更 Schema。

## Migration Plan

1. 执行一次性 MySQL ALTER，增加六个可空字段并重建三列 ngram FULLTEXT。
2. 部署代码并启动依赖；读取仍可回退旧 Qdrant Collection。
3. 启动增量同步构建 v2 物理 Collection，零失败后切换 Alias。
4. 验证 Alias、Point 数量、第二次同步零 Embedding、Graph Trace 和四个验收问题。
5. 回滚时切回旧代码和旧集合；保留新增 MySQL 列及新旧 Collection。
