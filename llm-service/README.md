# Stateful Agent Service

This service is the model-facing runtime for the platform. It is an asynchronous FastAPI application with a LangChain/LangGraph Agent, typed read-only tools, owner-scoped conversation threads, SQLite persistence for local development, and explicit degraded responses when no model is configured.

## Start

```powershell
python -m venv .venv
& .venv/Scripts/Activate.ps1
python -m pip install -r requirements.txt
python app.py
```

The default address is `http://127.0.0.1:5050`.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `LLM_API_URL` | empty | OpenAI-compatible Chat Completions URL; `/chat/completions` may be included |
| `LLM_API_KEY` | empty | Provider credential |
| `LLM_MODEL` | `qwen-plus` | Provider model name |
| `LLM_TIMEOUT_SECONDS` | `20` | Model request timeout |
| `DATABASE_PATH` | `data/agent-state.sqlite3` | Durable local conversation store |
| `ALLOWED_ORIGINS` | empty | Comma-separated browser origins; empty means no CORS middleware |
| `AGENT_CONTEXT_TOKEN_BUDGET` | `6000` | Approximate input budget |
| `AGENT_MAX_TOOL_ROUNDS` | `6` | Maximum model/tool loop rounds |

Without `LLM_API_URL` and `LLM_API_KEY`, the service still stores conversations and returns a clearly marked `degraded` answer based only on trusted business context.

## Stateful Agent API

Create a thread:

```http
POST /agent/threads
Content-Type: application/json

{"ownerId":"account:1","scopeType":"SCHOOL","scopeId":1}
```

Send a turn (omit `threadId` for the first turn):

```http
POST /agent/messages
Content-Type: application/json

{
  "ownerId": "account:1",
  "scopeType": "SCHOOL",
  "scopeId": 1,
  "message": "附近有哪些适合四年级的资源？",
  "threadId": null,
  "context": {
    "school": {"schoolName": "里庄小学"},
    "resources": [],
    "retrieval": {"retrievalStatus": "empty", "chunks": [], "graphFacts": []},
    "citationCandidates": []
  }
}
```

The response contains `threadId`, `status`, `citations`, `toolExecutions`, related resources, and follow-up questions. Reuse the returned `threadId` for subsequent turns. The public Spring endpoint remains `/api/ai/qa/ask`; Spring supplies the authenticated owner and trusted context before calling this service.

## Legacy workflows

These deterministic routes remain compatible during migration:

- `POST /llm/town/explain`
- `POST /llm/town/ask`
- `POST /llm/school/explain`
- `POST /llm/school/ask`
- `POST /llm/teaching-plan/generate`
- `POST /llm/resource-discovery/classify`

Teaching-plan generation and POI classification are structured workflows, not open-ended conversations. They use the same asynchronous LangChain model adapter and retain local fallbacks.

## Tool and security boundary

The Agent can only call the registered read-only tools for the trusted context supplied by Spring: scope context, approved resources, retrieved knowledge, and graph facts. It cannot execute arbitrary SQL, Cypher, URLs, shell commands, or change the owner/scope. Tool calls are bounded, audited, and citation IDs are filtered against supplied evidence.
