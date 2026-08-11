## ADDED Requirements

### Requirement: Imported documents enter the primary retrieval index
The system SHALL write successful knowledge-document chunks to the configured primary RAG Qdrant collection using deterministic point identifiers and retrieval-compatible document, school, title-path, metadata and index-version payload fields.

#### Scenario: Successful import is retrievable
- **WHEN** a supported document finishes ingestion
- **THEN** its chunks are stored in the active primary RAG collection and the document is marked `SUCCESS` or `DEGRADED`

### Requirement: Ingestion lifecycle is observable and recoverable
The system SHALL persist each job's status, current node, attempts, node results, degradation reasons and sanitized error summary. It MUST allow retrying failed jobs from a permitted persisted node and default to full validation.

#### Scenario: Optional enrichment fails
- **WHEN** MinerU, VLM or metadata extraction fails while text content remains usable
- **THEN** the job completes as `DEGRADED` with the affected node and reason visible in document details

#### Scenario: Indexing fails
- **WHEN** vector persistence fails
- **THEN** the document and job are marked `FAILED`, the error summary is persisted, and an administrator can retry the job

### Requirement: Structured and Chinese-safe ingestion
The system SHALL support Markdown, DOCX and PDF uploads, preserve headings and paragraphs, use optional external PDF parsing with explicit native fallback, and split Chinese and mixed-language text without relying on whitespace-only tokenization.

#### Scenario: Long Chinese paragraph is imported
- **WHEN** an oversized Chinese paragraph has no whitespace boundaries
- **THEN** it is split at sentence or character boundaries into bounded chunks retaining its heading path

### Requirement: Enrichment results are explicit
The system SHALL store extracted images in object storage, record VLM processing state, and use structured JSON output for metadata extraction. If optional enrichment is unavailable, it MUST use deterministic fallback metadata and record the degradation.

#### Scenario: Image model is unavailable
- **WHEN** a DOCX contains an image and VLM is unavailable
- **THEN** the original image remains stored, its status is reported as skipped or failed, and the text document continues ingestion as degraded

### Requirement: Reindexing does not leave stale chunks
The system SHALL remove existing document chunks and matching vector points before publishing replacement chunks, and MUST not mark a document published until database and vector writes complete.

#### Scenario: Failed reindex is retried
- **WHEN** a reindex fails after stale chunks were removed
- **THEN** the document remains unavailable for retrieval and a retry restores the complete deterministic chunk set
