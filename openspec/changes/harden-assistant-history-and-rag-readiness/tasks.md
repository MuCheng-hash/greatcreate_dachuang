## 1. Contract Tests

- [x] 1.1 Add Python tests for deterministic citation fallback, retrieval methods, response snapshots, and snapshot sanitization
- [x] 1.2 Add Java tests for retrieval method propagation, vector failure fallback, and rich history metadata forwarding
- [x] 1.3 Add Vue tests for rich history restoration, collapsed summaries, precise retrieval labels, and legacy compatibility

## 2. Agent Persistence

- [x] 2.1 Extend the Agent response contract with optional retrieval methods and derive them from trusted retrieval context
- [x] 2.2 Align Python citation fallback ordering with Spring and persist versioned response snapshots for stream and non-stream answers
- [x] 2.3 Keep legacy metadata fields and exclude raw stream or tool output data from snapshots

## 3. Business Service Retrieval Contract

- [x] 3.1 Derive distinct retrieval methods from chunks and graph facts for synchronous and streamed answers
- [x] 3.2 Forward optional model and retrieval method metadata without breaking existing clients
- [x] 3.3 Preserve degraded keyword fallback when vector retrieval fails

## 4. Historical Presentation

- [x] 4.1 Add typed response-snapshot normalization with safe legacy fallback in the assistant frontend
- [x] 4.2 Restore citations, statuses, resources, model, memory, follow-ups, and a collapsed sanitized execution summary
- [x] 4.3 Render retrieval wording from status plus actual retrieval methods

## 5. RAG Startup and Verification

- [x] 5.1 Update the local startup guide with Qdrant-first health, index synchronization, collection verification, and failure guidance
- [x] 5.2 Start Qdrant, restart the services with existing RAG configuration, and verify a zero-failure non-empty vector index

## 6. Quality Gates

- [x] 6.1 Run focused and full Python, JDK 21 Maven, and frontend test suites
- [x] 6.2 Run frontend typecheck and production build, then verify the assets served by Spring
- [x] 6.3 Perform real-page acceptance for vector retrieval and A-B-A historical result restoration
- [x] 6.4 Validate the OpenSpec change and confirm all tasks are complete
