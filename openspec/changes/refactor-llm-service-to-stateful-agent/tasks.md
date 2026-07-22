## 1. FastAPI Foundation

- [x] 1.1 Replace Flask dependencies and entry point with a modular FastAPI application, typed settings, request models, health endpoint, and restricted CORS configuration.
- [x] 1.2 Move the existing teaching-plan, POI-classification, town, and school workflows behind compatible FastAPI routes using asynchronous LangChain model integration.

## 2. Conversation Persistence and Memory

- [x] 2.1 Implement a transactional SQLite repository for owner-scoped threads, raw messages, rolling summaries, and tool audit records.
- [x] 2.2 Implement deterministic context budgeting that preserves recent turns, compacts older history, and bounds trusted/tool context.
- [x] 2.3 Add thread creation, retrieval, message, and archive APIs with owner/scope isolation and structured responses.

## 3. Agent Runtime and Tools

- [x] 3.1 Implement the typed read-only tool registry over trusted Spring-supplied school, resource, retrieval, graph, and citation context.
- [x] 3.2 Implement the shared LangChain/LangGraph Agent runtime with bounded tool execution, structured response parsing, citation filtering, and explicit degraded behavior.
- [x] 3.3 Persist sanitized tool execution audit data and expose tool names/statuses in the Agent response.

## 4. Business and Frontend Integration

- [x] 4.1 Extend Java QA request/response models and generated-answer plumbing with `threadId` and Agent execution status.
- [x] 4.2 Add a configurable Spring HTTP answer generator that sends authenticated owner/scope and trusted context to FastAPI while preserving local fallback.
- [x] 4.3 Update the Vue assistant to persist and resend the server-issued `threadId` and clear it when the conversation is reset.

## 5. Verification and Documentation

- [x] 5.1 Add Python tests for legacy compatibility, validation, multi-turn persistence, restart recovery, isolation, context compaction, tools, citations, and degraded mode.
- [x] 5.2 Add or update Java and frontend tests for the new runtime contract and thread lifecycle.
- [x] 5.3 Update service and root documentation, sample configuration, and startup commands for FastAPI and the stateful Agent runtime.
- [x] 5.4 Run focused Python, Maven, and frontend test suites and validate the OpenSpec change.
