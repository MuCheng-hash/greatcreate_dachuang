## 1. Contracts and Storage

- [x] 1.1 Add ingestion metadata, status history, restart node and primary-index configuration contracts
- [x] 1.2 Extend document detail, retry and Markdown download APIs without breaking current clients

## 2. Worker Pipeline

- [x] 2.1 Implement provider-based Markdown, DOCX and PDF conversion with explicit MinerU fallback
- [x] 2.2 Implement structured VLM and metadata enrichment with deterministic degraded fallbacks
- [x] 2.3 Replace whitespace splitting with heading, paragraph and Chinese-safe chunking
- [x] 2.4 Publish deterministic chunks into the primary RAG collection with atomic cleanup and lifecycle reporting
- [ ] 2.5 Add bounded automatic retry and sanitized per-node failure reporting

## 3. Management Experience

- [x] 3.1 Restore the frontend request client and production build
- [ ] 3.2 Render ingestion details, normalized Markdown download and retry controls in the knowledge-base management view

## 4. Verification

- [ ] 4.1 Add worker tests for conversion fallback, Chinese chunking, structured metadata and vector payloads
- [ ] 4.2 Add Spring API tests for detailed lifecycle, retry and Markdown download
- [ ] 4.3 Run worker tests, frontend typecheck/build and focused backend tests
