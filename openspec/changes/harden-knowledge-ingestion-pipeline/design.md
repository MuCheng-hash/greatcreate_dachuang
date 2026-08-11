## Context

The Spring application saves uploads in MinIO and enqueues a MySQL-backed job through Redis. The Python worker currently converts files, enriches them, and writes to its own Qdrant collection. The primary RAG service uses a separate collection and payload contract, so successful imports are not guaranteed to appear in retrieval. Several optional enrichment failures are silently hidden and Chinese text is split with whitespace semantics.

## Goals / Non-Goals

**Goals:**

- Publish successfully imported documents into the active primary RAG collection with stable identifiers and metadata required by retrieval.
- Make every pipeline node observable, retryable and explicitly degradable.
- Preserve usable text ingestion when MinerU or VLM are unavailable.
- Make management UI and API report the imported artifact, lifecycle and recovery actions.

**Non-Goals:**

- Replacing Qdrant, adding arbitrary office formats, or making VLM/MinerU mandatory.
- Replacing the existing primary RAG retrieval/reranking implementation.

## Decisions

1. The worker will receive the primary RAG collection, alias, dimensions and index version as configuration and write the same point shape used by the existing Qdrant store. This favors a single source of retrieval truth over dual-write migration.
2. The job remains persisted in MySQL, with `metadata_json` used for node results and degradation reasons. This avoids a new event service while allowing the existing detail API to expose status.
3. Parsing is provider-based: native Markdown/DOCX, optional MinerU HTTP for PDFs, then native PDF extraction as explicit degraded fallback. The external parser does not become a hard dependency.
4. Chunking is heading and paragraph based, with Chinese sentence/character splitting for oversized blocks. It uses an estimated token count only for storage/limits, never whitespace-only segmentation.
5. VLM and metadata are structured gateway calls. Any enhancement failure is recorded as a degradation and receives deterministic metadata fallback; indexing failures remain terminal because a partial index must not be published.
6. Reindexing removes all existing points and relational chunks for a document before inserting deterministic replacement point ids. MySQL document status is published only after Qdrant upsert completes.

## Risks / Trade-offs

- [External parser contracts vary] → isolate the MinerU client and retain native PDF fallback.
- [A worker crash between delete and write leaves a missing document] → retain a failed job with the failed node and allow full retry from validation.
- [Qdrant payload differs from the Java retriever] → centralize collection/payload configuration and cover it with a contract test.
- [Optional model outages reduce quality] → expose `DEGRADED` in the API and UI rather than masking the condition.

## Migration Plan

1. Add columns and configuration with backward-compatible defaults.
2. Deploy the worker capable of reading both legacy and new jobs.
3. Configure worker collection/alias to the primary RAG active index and reimport affected documents.
4. Deploy the API/UI detail additions; rollback preserves source files and allows jobs to be requeued.

## Open Questions

- None. MinerU, VLM and metadata enhancement are optional and use the existing external gateway configuration.
