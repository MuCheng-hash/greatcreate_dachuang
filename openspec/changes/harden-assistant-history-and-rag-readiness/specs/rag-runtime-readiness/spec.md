## Purpose

让本地向量检索的依赖状态、索引就绪情况和实际检索方式可以被验证，并在 Qdrant 故障时继续以明确标注的关键词检索提供可用回答。

## ADDED Requirements

### Requirement: Verify Qdrant before vector RAG startup
The local RAG startup procedure SHALL start Qdrant, verify its HTTP health endpoint, and start the business service with RAG synchronization only after Qdrant is reachable.

#### Scenario: Qdrant starts successfully
- **WHEN** the documented local RAG startup sequence is followed
- **THEN** Qdrant health succeeds before the business service performs startup index synchronization

#### Scenario: Qdrant does not become healthy
- **WHEN** Qdrant fails the bounded health check
- **THEN** the startup verification reports the dependency failure instead of claiming vector retrieval is ready

### Requirement: Verify vector index readiness
The system SHALL verify that startup index synchronization completes without failed chunks and that the configured Qdrant collection contains indexed points before vector RAG is accepted as ready.

#### Scenario: Index synchronization succeeds
- **WHEN** RAG synchronization finishes with all indexable chunks processed
- **THEN** the synchronization report has zero failed chunks and the configured collection contains vector points

### Requirement: Report actual retrieval methods
Question-answering responses SHALL optionally report the distinct retrieval methods actually used for their evidence, including vector hybrid retrieval, keyword fallback, and knowledge-graph evidence.

#### Scenario: Vector retrieval supplies chunks
- **WHEN** vector search and hybrid reranking return evidence
- **THEN** the response includes `vector+hybrid-rerank` in `retrievalMethods`

#### Scenario: Keyword fallback supplies chunks
- **WHEN** vector retrieval is disabled, empty, or unavailable and keyword retrieval returns evidence
- **THEN** the response includes `keyword-fallback` in `retrievalMethods`

#### Scenario: Graph facts supply evidence
- **WHEN** knowledge-graph facts are included in the grounded context
- **THEN** the response includes `knowledge-graph` in `retrievalMethods`

### Requirement: Keep question answering available during vector failure
The system SHALL fall back to keyword retrieval when Qdrant or embedding retrieval is unavailable and SHALL return a degraded result with the fallback method instead of failing the whole answer.

#### Scenario: Qdrant connection is refused
- **WHEN** vector retrieval cannot connect to Qdrant but keyword evidence is available
- **THEN** the answer completes with degraded retrieval status and `keyword-fallback` in `retrievalMethods`

### Requirement: Explain retrieval state precisely
The user interface SHALL combine retrieval status and retrieval methods so users can distinguish successful vector retrieval, keyword fallback, missing direct evidence, and partial failure of another knowledge component.

#### Scenario: Keyword fallback is used
- **WHEN** a completed answer is degraded and reports `keyword-fallback`
- **THEN** the interface states that keyword retrieval was used instead of claiming that all knowledge retrieval was unavailable

#### Scenario: Vector retrieval succeeds with another partial failure
- **WHEN** a degraded answer reports `vector+hybrid-rerank`
- **THEN** the interface states that vector retrieval completed and that another knowledge component was partially unavailable
