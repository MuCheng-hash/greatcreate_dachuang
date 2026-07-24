## Why

The current LLM service is a stateless Flask wrapper around an OpenAI-compatible HTTP request, while the Java "Agent" path is a fixed request pipeline backed by mock retrieval and template answers. The platform needs a real, server-side conversational Agent that can plan, call approved business tools, retain isolated conversation state, and manage bounded model context without losing the existing business and security boundaries.

## What Changes

- Replace the Flask application with an asynchronous FastAPI service and converge all model-facing tasks on the Stateful Agent endpoints.
- Introduce a LangChain/LangGraph Agent runtime with typed tool registration, bounded planning and tool execution, structured answers, and citation validation.
- Add server-side conversation threads, persisted messages/checkpoints, rolling summaries, and token-budget-aware context compaction.
- Add internal Spring-to-Agent integration so authenticated school scope is supplied by the business service and cannot be selected by the model.
- Extend the teacher assistant UI and request contract with `threadId`, while retaining a compatibility path for callers that omit it.
- Replace production-default mock/template Agent behavior with an explicit degraded mode when the model or a tool is unavailable.

## Capabilities

### New Capabilities

- `stateful-agent-conversations`: Covers conversation lifecycle, state isolation, persistence, recovery, context-window management, and multi-turn behavior.
- `agent-tool-orchestration`: Covers typed tool registration, planning and bounded execution, scope enforcement, citation validation, and failure handling.
- `fastapi-llm-runtime`: Covers the FastAPI Stateful Agent contract, task workflows, asynchronous model access, health reporting, and streaming responses.

### Modified Capabilities

None.

## Impact

- Replaces `llm-service/app.py` and Flask dependencies with a modular FastAPI/LangChain/LangGraph application.
- Adds local Agent persistence under `llm-service` for development and a configurable production checkpoint backend boundary.
- Changes the Spring Agent request/response integration and Vue assistant state to exchange a `threadId`.
- Adds Python, Java, and frontend tests for multi-turn state, scope isolation, tool behavior, context compaction, removal of old routes, and degraded operation.
- Existing business data, authentication, approved-resource rules, Neo4j access, and teaching-plan persistence remain owned by `business-service`.
