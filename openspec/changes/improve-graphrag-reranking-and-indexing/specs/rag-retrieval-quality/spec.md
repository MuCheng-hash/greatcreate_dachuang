## Purpose

提高学校范围内 RAG 证据的业务相关性、图谱可解释性和审核安全性，同时保持现有检索接口及降级语义。

## ADDED Requirements

### Requirement: Return structured graph paths
The retriever SHALL return graph facts with typed subject and object identities, real relationship types, hop count, optional distance, and ordered path edges.

#### Scenario: A direct nearby relationship is found
- **WHEN** an approved school has a `SCHOOL_NEAR_RESOURCE` edge to an approved active resource
- **THEN** the fact reports the real predicate, `hop=1`, distance, both entity names, and one path edge

#### Scenario: A multi-hop relationship is found
- **WHEN** a relation query reaches an eligible entity within the configured hop bound through whitelisted edges
- **THEN** the fact uses `GRAPH_PATH` and reports every real edge in order

### Requirement: Reject unsafe graph expansion
The retriever SHALL NOT use `HAS_TAG`, unapproved relations, inactive entities, rejected entities, or missing MySQL entities to expand retrieval scope.

#### Scenario: Neo4j contains a stale resource edge
- **WHEN** the target resource is not approved and active in MySQL
- **THEN** its entity key, chunks, and graph fact are excluded

### Requirement: Rerank evidence with bounded business features
The retriever SHALL rerank RRF chunks and graph facts using configured normalized base, entity, grade, theme, source, and graph features while keeping base retrieval dominant.

#### Scenario: Shared relevance has a grade distinction
- **WHEN** two candidates have equal RRF relevance but only one exactly matches the requested grade band
- **THEN** the exact grade candidate ranks first and the score trace reports the grade contribution

#### Scenario: Nearby resources contain repeated chunks
- **WHEN** one resource supplies many high-scoring chunks and other resources have useful evidence
- **THEN** the first diversity pass limits that resource to two chunks before filling remaining positions

### Requirement: Preserve fail-open retrieval semantics
Dense, Lexical, and required Graph failures SHALL retain P0 status behavior and usable evidence from successful channels.

#### Scenario: Graph fails for a nearby query
- **WHEN** text retrieval returns evidence but Neo4j fails
- **THEN** the result is degraded, retains text evidence, and reports `graphStatus=failed`
