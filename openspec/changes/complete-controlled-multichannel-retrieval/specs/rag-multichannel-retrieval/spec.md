## Purpose

在不牺牲审核与引用边界的前提下，通过受控改写、低召回补充召回和云端精排提升问答检索质量。

## ADDED Requirements

### Requirement: Augment only weak local retrieval
The system SHALL preserve Dense and Lexical first-pass retrieval and SHALL invoke HyDE and approved Web search only when configured low-recall thresholds are met.

#### Scenario: Local retrieval is sufficient
- **WHEN** the first pass reaches the configured candidate and RRF thresholds
- **THEN** the system SHALL not call HyDE or Web search

### Requirement: Restrict web evidence to approved domains
The system SHALL only accept HTTPS Web results whose normalized host is an enabled administrator-managed authoritative domain.

#### Scenario: Search returns an unapproved host
- **WHEN** a provider result host is not enabled in the authoritative source catalog
- **THEN** the result SHALL be discarded and SHALL not become an LLM citation

### Requirement: Preserve usable degradation
The system SHALL return existing local evidence when query rewrite, HyDE, Web search, or cross-encoder reranking fails.

#### Scenario: Cross-encoder is unavailable
- **WHEN** the reranker fails or times out
- **THEN** the system SHALL return heuristic reranked evidence and report a degraded rerank trace
