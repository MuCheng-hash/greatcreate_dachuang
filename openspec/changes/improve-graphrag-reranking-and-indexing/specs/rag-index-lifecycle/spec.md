## Purpose

让 Dense 与 Lexical 使用一致的实体元数据，并避免对未变化内容重复生成向量或保留陈旧 Point。

## ADDED Requirements

### Requirement: Build one stable retrieval document
The indexer SHALL build deterministic labeled metadata for each supported entity and combine it with chunk title and body for hashing and Dense embedding without including private contact fields.

#### Scenario: Resource metadata is indexed
- **WHEN** a resource chunk is eligible
- **THEN** its retrieval metadata includes name, alias, region, category, grade, and education themes but excludes contact phone

### Requirement: Skip unchanged vectors
The startup synchronizer SHALL compare application-managed Hash, model, dimensions, and index version and SHALL NOT call Embedding or Qdrant Upsert for unchanged points.

#### Scenario: A second startup has no data changes
- **WHEN** all active Point metadata matches current index documents
- **THEN** indexed count is zero and skipped count equals the eligible chunk count

### Requirement: Remove stale points
The synchronizer SHALL delete Point IDs that no longer correspond to eligible approved active chunks.

#### Scenario: An entity becomes inactive
- **WHEN** an indexed entity is inactive during the next synchronization
- **THEN** all of its Point IDs are deleted from the target collection

### Requirement: Switch incompatible indexes atomically
The system SHALL build a new physical collection when the model, dimensions, or index schema version changes and SHALL switch the read Alias only after a zero-failure build.

#### Scenario: A versioned build partially fails
- **WHEN** any batch fails while building a new physical collection
- **THEN** the Alias continues to resolve to the previous collection and the old collection is not deleted
