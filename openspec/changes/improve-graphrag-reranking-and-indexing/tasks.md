## 1. Baseline and Contracts

- [x] 1.1 Add retrieval evaluation fixtures and capture the P0 expected entity/path baseline
- [x] 1.2 Add optional trace, structured graph, ranked citation, and debug request contract types
- [x] 1.3 Update the RAG and Agent contract documentation

## 2. Structured GraphRAG

- [x] 2.1 Return structured direct and multi-hop graph facts using the relationship whitelist
- [x] 2.2 Validate graph-expanded entities and school-resource relations against approved active MySQL data
- [x] 2.3 Expand eligible relation-query entities with bounded scope and preserve graph failure degradation

## 3. Heuristic Reranking

- [x] 3.1 Batch-load candidate entity and source metadata and implement normalized feature scoring
- [x] 3.2 Rank chunk and graph evidence jointly with nearby-resource diversity and graph quotas
- [x] 3.3 Report heuristic retrieval methods, ranked citation fields, and feature contribution traces

## 4. Incremental Index Lifecycle

- [x] 4.1 Add content chunk metadata/version columns and the three-column ngram FULLTEXT migration
- [x] 4.2 Build stable per-entity retrieval metadata and SHA-256 index documents without private contact fields
- [x] 4.3 Extend Qdrant payload, metadata scrolling, stale deletion, physical collection creation, and Alias switching
- [x] 4.4 Replace startup full rebuild with incremental synchronization while retaining explicit rebuild

## 5. Agent Observability

- [x] 5.1 Add debug-only retrieval details to Java SSE events
- [x] 5.2 Persist a sanitized retrieval summary in Python LLM Trace metadata and use ranked evidence in prompts
- [x] 5.3 Show retrieval channels and Graph status in Agent Debug and the admin timeline without changing tool counts

## 6. Tests and Verification

- [x] 6.1 Add Java retrieval and indexing tests for all routing, scoring, filtering, incremental, and Alias cases
- [x] 6.2 Add Python and frontend tests for ranked evidence, trace metadata, debug SSE, and UI labels
- [x] 6.3 Run focused JDK 21, Python, frontend tests, typecheck, and production build
- [x] 6.4 Run the 24-query evaluation and full-service acceptance; record unavailable external checks honestly

## Verification Record (2026-08-05)

- JDK 21 full suite: 95 passed; focused retrieval/index/Agent suite: 39 passed.
- Python Agent: 87 passed; frontend: 71 passed; typecheck and production build passed.
- Live 24-query evaluation: Recall@8 `0.850`, MRR@8 `0.762`, graph predicate accuracy `0.800`, negative cases `4/4`; average `404 ms`, P95 `823 ms`.
- Incremental startup: total `12`, indexed `0`, skipped `12`, failed `0`; active Alias points to the green v2 collection with 12 points at 1024 dimensions.
- Debug SSE and admin timeline: retrieval Trace is present, Graph status is `ok`, and the same run is shown as retrieval `1` / tool `0`.
- Known data gaps: resource IDs 1 and 2 have no `content_chunk`; the current MySQL/Neo4j data has no Li Dazhao entity or verifiable relation. These cases remain empty instead of generating unsupported evidence.
- OpenSpec CLI was not available on PATH; proposal, design, delta specs, and tasks were created and cross-checked manually, so CLI validation was not executed.
