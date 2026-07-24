## Context

The repository currently splits business ownership into Spring Boot and model-facing endpoints into a single Flask module. The Python service has no request models, server-side conversation identity, durable state, tool registry, or model-driven execution loop. The Java QA service performs a useful but fixed sequence of scope resolution, intent recognition, retrieval, template generation, and citation filtering. The Vue client stores display history only in browser session storage and sends each question independently.

The change must preserve Spring ownership of authentication, approved business data, and database/graph access. Java keeps the public teaching-plan and QA façades, while the model-facing FastAPI service converges all tasks on the Stateful Agent protocol. Development must remain runnable without model credentials.

## Goals / Non-Goals

**Goals:**

- Provide a real LangChain/LangGraph Agent runtime with model-driven typed tool calls.
- Isolate and persist each conversation by an opaque thread identifier and authenticated owner scope.
- Bound tool rounds, tool output, message history, and model context.
- Keep citations limited to evidence supplied by trusted business and retrieval context.
- Remove the old FastAPI one-shot `/llm/*` endpoints and provide explicit degraded behavior without credentials.
- Route the existing Spring QA API and Vue assistant through the stateful runtime without weakening authorization.

**Non-Goals:**

- Moving MySQL, Neo4j, school authorization, or approval workflows into Python.
- Allowing the model to execute arbitrary SQL, Cypher, URLs, or shell commands.
- Converting deterministic POI classification and teaching-plan generation into open-ended Agents.
- Adding autonomous background execution or write-capable tools in this change.

## Decisions

1. **FastAPI application factory and modular packages.** The Python service will expose a FastAPI `app` from a small entry point, with settings, schemas, persistence, tools, Agent runtime, and structured task helpers in separate modules. FastAPI is selected for typed validation, async HTTP/model access, generated API documentation, and streaming support. Replacing Flask alone is not considered sufficient.

2. **LangChain tools with a LangGraph-backed Agent.** The runtime will use LangChain's current Agent constructor, which is backed by LangGraph, and register an allowlist of typed read-only tools. The compiled Agent is shared; `thread_id` and owner metadata select isolated state. A bounded model tool loop serves as the planner/executor, with explicit middleware/runtime limits and a deterministic degraded path when no model is configured. A hand-written unrestricted planner was rejected because it would duplicate LangGraph execution and increase unsafe actions.

3. **Trusted context is supplied by Spring.** Spring resolves the authenticated school/administrator scope, loads approved business data, performs the existing bounded retrieval, and sends a context envelope to FastAPI. Python tools can inspect only this envelope: scope summary, approved resources, retrieved chunks, graph facts, and citation candidates. The LLM never supplies or overrides the authoritative owner or scope. Direct database access from Python and model-authored SQL/Cypher are rejected.

4. **SQLite development repository behind a persistence interface.** The initial implementation stores threads, messages, summaries, and tool audit entries in SQLite under a configurable path. It uses transactions, owner checks, and JSON metadata. The interface keeps a production backend replaceable by Redis/PostgreSQL without changing HTTP behavior. In-memory-only state was rejected because service restarts must preserve conversations.

5. **Application-managed history plus Agent invocation.** Persisted messages are loaded for a thread, compacted, and passed into each Agent invocation. This avoids coupling the HTTP contract to one checkpoint provider while still using LangGraph for the model/tool loop. Recent messages remain verbatim; older messages are converted into a bounded rolling summary. Tool results are size-limited and not copied indefinitely into subsequent prompts.

6. **Token-budget approximation is deterministic and provider-independent.** A conservative character-based estimator enforces configured input budgets without requiring provider-specific tokenizers. It reserves output capacity, retains the system policy and recent turns, and updates the rolling summary when older turns are removed. Exact provider usage is recorded when available but is not required for safety.

7. **Spring remains the public authenticated gateway.** `AgentQaRequest` and `AgentQaResponse` gain `threadId`. The Java QA service continues to enforce scope and assemble trusted context, then uses an HTTP answer generator for the stateful runtime when configured. Local template generation remains an explicit fallback. The Vue UI stores the returned opaque thread identifier and sends it on later turns.

8. **Unified structured task workflows.** Chat, teaching-plan generation, and resource classification all use the same Stateful Agent request and SSE protocols. Structured tasks retain schema validation, PromptManager versions, trusted context, and local fallbacks; resource-discovery service threads are archived after completion so they do not pollute teacher conversations.

## Risks / Trade-offs

- **Agent dependency APIs can evolve** → Pin compatible LangChain/LangGraph versions and isolate imports behind `AgentRuntime`.
- **SQLite is unsuitable for high write concurrency** → Use it for local/small deployment only and keep the repository boundary replaceable.
- **Spring preloads retrieval before the model chooses a tool** → The first migration prioritizes security and compatibility; later the same typed tools can call protected internal Spring endpoints lazily.
- **A model can loop or over-call tools** → Enforce maximum tool rounds, request timeout, tool output limits, and an allowlist.
- **Conversation identifiers could be replayed across users** → Require an owner key on every thread access and return not-found for mismatches.
- **Summaries can lose details** → Preserve recent turns verbatim and store all raw messages durably even when only a summary is sent to the model.
- **No model credentials in development** → Return an explicit degraded answer based on trusted context and never claim that an Agent/model call succeeded.

## Migration Plan

1. Add the FastAPI modular service and tests with the unified Stateful Agent route family.
2. Add thread/message APIs and SQLite persistence; verify restart recovery and owner isolation.
3. Add typed context tools and LangChain/LangGraph execution with bounded context.
4. Add the Spring HTTP answer generator and `threadId` fields behind configuration, preserving template fallback.
5. Update Vue to persist only the opaque server thread identifier plus render history.
6. Run Python, Java, and frontend tests, then deploy FastAPI before enabling the Spring runtime client.

Rollback consists of disabling remote Agent calls in Spring and returning to the existing local structured fallback. The removed FastAPI `/llm/*` routes are not part of rollback and must remain absent.

## Open Questions

- Production checkpoint storage can be selected between Redis and PostgreSQL after deployment volume is known; this does not change the API contract.
- The compatible SSE extension is now implemented as `POST /agent/messages/stream`; it emits `run.started`, model/tool events, `token`, `final`, `error`, and `done` while keeping the same owner/scope checks and SQLite persistence.
