# Stateful Agent Service

本目录是独立 LLM 服务入口，负责地图讲解、学校周边资源问答、教学方案生成和 Agent 运行时。Agent 使用 FastAPI + LangChain/LangGraph，业务数据和 RAG 通过 Java 内部受控工具接口访问。

This service is the model-facing runtime for the platform. It provides typed read-only tools, owner-scoped conversation threads, SQLite persistence for local development, and explicit degraded responses when no model is configured.

## Start

```powershell
python -m venv .venv
& .venv/Scripts/Activate.ps1
python -m pip install -r requirements.txt
python app.py
```

The default address is `http://127.0.0.1:5050`.

项目只保留一个 FastAPI 应用：`app.py` 是兼容启动命令的薄壳，实际应用工厂为
`llm_service.api:create_app`。不要再启动另一个独立的 Agent 进程。

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `LLM_API_URL` | empty | OpenAI-compatible Chat Completions URL; `/chat/completions` may be included |
| `LLM_API_KEY` | empty | Provider credential |
| `LLM_MODEL` | `qwen-plus` | Provider model name |
| `LLM_TIMEOUT_SECONDS` | `20` | Model request timeout |
| `LLM_MAX_OUTPUT_TOKENS` | `512` | Ollama fallback maximum output tokens |
| `DATABASE_PATH` | `data/agent-state.sqlite3` | Durable local conversation store |
| `PROMPT_ADMIN_TOKEN` | empty | Required token for prompt-management APIs |
| `OBSERVABILITY_ADMIN_TOKEN` | empty | Required token for observability APIs |
| `AGENT_INTERNAL_SERVICE_TOKEN` | empty | Required internal token for Agent thread/message APIs; missing configuration returns `503` |
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

流式问答使用 `POST /agent/messages/stream`，事件统一为
`run.started`、`model.started`、`tool.started`、`tool.completed`、`token`、`final`、`error`、`done`。
调用 Agent 接口时应携带 `X-Agent-Service-Token`；服务端不会信任外部请求伪造的 `ownerId` 或学校范围。

## Unified task workflows

FastAPI 只保留一套 Stateful Agent 接口族，所有任务共用线程持久化、owner/scope
隔离、LangChain 工具、PromptManager、观测和模型降级链路：

- `POST /agent/messages`：同步任务入口
- `POST /agent/messages/stream`：SSE 流式任务入口
- `GET /health/live`、`GET /health/ready`：健康检查

请求通过 `taskType` 区分任务：

- `CHAT`：智能问答和地图问答
- `TEACHING_PLAN`：教学方案生成，参数放在 `taskPayload` 中
- `RESOURCE_DISCOVERY`：候选地点思政教育价值分类，参数放在 `taskPayload` 中

同步响应统一包含 `threadId`、`taskType`、`status`、`answer`、`citations` 和
`toolExecutions`；教学方案结果位于 `teachingPlan`，资源发现结果位于
`resourceDiscovery`。流式事件统一为 `run.started`、`model.started`、
`tool.started`、`tool.completed`、`token`、`final`、`error`、`done`。

调用 `/agent/messages*` 必须携带 `X-Agent-Service-Token`。Java 业务服务负责
JWT、学校范围和可信上下文校验，再向 FastAPI 转发；浏览器不能直接调用 FastAPI。
未配置真实模型或模型响应不可用时，接口返回 `generationStatus=degraded` 并使用
可信业务数据生成有意义的本地兜底响应。旧 `/llm/*` 路径已删除，调用会返回 `404`。

## Prompt 管理与效果评估

教学方案和资源发现 prompt 不再写在 Python 源码中。仓库中的
`prompts/teaching-plan/v1/system.md` 与 `prompts/resource-discovery/v1/system.md`
只负责首次初始化；运行时版本和调用记录均保存在 `DATABASE_PATH` 指向的 SQLite
数据库中。管理操作需要请求头：

```http
X-Prompt-Admin-Token: <PROMPT_ADMIN_TOKEN>
```

发布并激活新版本：

```http
POST /admin/prompts/teaching-plan/versions
Content-Type: application/json

{"version":"v2","content":"新版提示词...\n{{context_json}}","createdBy":"admin","notes":"强化安全约束"}
```

```http
POST /admin/prompts/teaching-plan/versions/v2/activate
```

配置稳定分流的 A/B 实验；同一学校会通过哈希持续命中同一版本：

```http
PUT /admin/prompts/teaching-plan/experiment
Content-Type: application/json

{
  "experimentKey":"teaching-plan-v2-2026-07",
  "active":true,
  "variants":[{"version":"v1","weight":50},{"version":"v2","weight":50}]
}
```

效果评估接口：

- `GET /admin/prompts/teaching-plan/metrics`：按版本返回调用量、成功率、平均耗时和平均人工评分。
- `POST /admin/prompt-runs/{runId}/feedback`：提交 `qualityScore`（0-5）和 `feedback`。
- `GET /admin/prompts/teaching-plan/versions`：查看所有版本与当前活动版本。

教学方案最终响应会包含 `promptVersion`、`promptRunId`、`promptExperiment` 和 `promptVariant`，用于把用户反馈准确归因到版本和实验组。

推荐使用 `AGENT_PRIMARY_*` 和 `AGENT_FALLBACK_*` 配置统一模型链。`LLM_API_URL`、
`LLM_BASE_URL`、`LLM_API_KEY` 和 `LLM_MODEL` 仅作为配置别名保留，不代表旧
`/llm/*` 接口仍存在。

例如使用阿里云百炼主模型、Ollama 降级模型时，可以在启动 FastAPI Agent 前配置：

```powershell
$env:AGENT_PRIMARY_PROVIDER = "bailian"
$env:AGENT_PRIMARY_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:AGENT_PRIMARY_API_KEY = "你的百炼API_KEY"
$env:AGENT_PRIMARY_MODEL = "qwen-plus"
$env:AGENT_FALLBACK_PROVIDER = "ollama"
$env:AGENT_FALLBACK_BASE_URL = "http://127.0.0.1:11434/v1"
$env:AGENT_FALLBACK_MODEL = "本地已有模型名"
python app.py
```

API Key 仅通过环境变量传入，不能写入仓库；Ollama 不需要下载新模型，`AGENT_FALLBACK_MODEL`
必须填写为本机已经存在的模型名。

Agent 运行时支持为主模型和降级模型分别配置 provider、模型、地址和密钥。推荐的真实 Agent 链路是“百炼 qwen-plus → Ollama qwen3:8b → 本地结构化兜底”：

```powershell
$env:AGENT_PRIMARY_PROVIDER = "bailian"
$env:AGENT_PRIMARY_MODEL = "qwen-plus"
$env:AGENT_PRIMARY_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:AGENT_PRIMARY_API_KEY = "你的百炼API_KEY"

$env:AGENT_FALLBACK_PROVIDER = "ollama"
$env:AGENT_FALLBACK_MODEL = "qwen3:8b"
$env:AGENT_FALLBACK_BASE_URL = "http://127.0.0.1:11434/v1"
$env:AGENT_FALLBACK_API_KEY = "ollama"
python app.py
```

其中 API Key 只通过环境变量传入，不写入仓库。Ollama 的 `api_key` 只是兼容
`ChatOpenAI` 接口所需的占位值，不是密钥。未配置降级模型时，仍然使用本地结构化兜底。

模型配置优先级为 `AGENT_*` 专用配置，未配置时才读取上述 `LLM_*` 配置别名。

Agent 运行时通用配置：

- `INTERNAL_BUSINESS_BASE_URL`：Java business-service 地址，默认 `http://127.0.0.1:8080`
- `AGENT_INTERNAL_SERVICE_TOKEN`：FastAPI 调用 Java 内部工具的服务令牌
- `AGENT_PRIMARY_PROVIDER`：主模型供应商，默认 `openai-compatible`
- `AGENT_PRIMARY_MODEL`：主模型，默认读取 `LLM_MODEL`
- `AGENT_PRIMARY_BASE_URL`：主模型 OpenAI-compatible 基础地址
- `AGENT_PRIMARY_API_KEY`：主模型 API Key
- `AGENT_FALLBACK_PROVIDER`：降级模型供应商
- `AGENT_FALLBACK_MODEL`：可选降级模型
- `AGENT_FALLBACK_BASE_URL`：降级模型 OpenAI-compatible 基础地址
- `AGENT_FALLBACK_API_KEY`：降级模型 API Key；Ollama 通常填 `ollama`
- `AGENT_MAX_ITERATIONS`：Agent 最大工具循环次数，默认 4
- `AGENT_MAX_HISTORY_MESSAGES`：单会话保留的最大消息数，默认 20
- `AGENT_PROMPT_VERSION`：提示词版本，默认 `v1`，文件位于 `prompts/agent/<version>/system.md`

兼容模型服务配置：

当前本地运行使用 SQLite 持久化 thread、message 和工具审计；生产环境可在不改变 HTTP 协议的前提下替换为 Redis 或 PostgreSQL 等持久化后端。
运行日志会携带 `runId`、`conversationId`、模型、提示词版本、工具名称、耗时、token usage、
生成/检索状态、降级级别和错误类型；模型未返回 usage 时保持为空，不伪造统计数据。

## Tool and security boundary

The Agent can only call the registered read-only tools for the trusted context supplied by Spring: scope context, approved resources, retrieved knowledge, and graph facts. It cannot execute arbitrary SQL, Cypher, URLs, shell commands, or change the owner/scope. Tool calls are bounded, audited, and citation IDs are filtered against supplied evidence.

## LLM observability

Every provider call is recorded in the `llm_trace` SQLite table. Agent tool loops create
one span for each provider request under the same trace. Prompt and response bodies are
not stored. Each record contains user, session, feature, provider, model, status, typed
error, provider token usage, calculated cost, total latency, time to first token, and JSON
validity. Runtime log events are emitted as one JSON object per line.

Configuration:

| Variable | Default | Purpose |
| --- | --- | --- |
| `OBSERVABILITY_ADMIN_TOKEN` | falls back to `PROMPT_ADMIN_TOKEN` | Protects trace and aggregate APIs |
| `LLM_MODEL_PRICING` | `{}` | JSON map of per-million-token USD prices |

Example pricing configuration (replace values with the current provider contract):

```powershell
$env:LLM_MODEL_PRICING = '{"qwen-plus":{"input":1.0,"output":2.0},"*":{"input":0.5,"output":1.0}}'
$env:OBSERVABILITY_ADMIN_TOKEN = "change-me"
```

Provider-reported token usage is used when available. Calls without provider usage remain
`tokenSource=unavailable`; they are never filled with fabricated estimates. Cost remains
unpriced until a matching model or `*` price is configured.

Operational endpoints:

- `GET /admin/observability/traces`: paginated call details.
- `GET /admin/observability/summary`: calls, success/valid-JSON rates, P50/P95/P99,
  tokens, cost, typed errors, and per-user/session/feature groups.
- `GET /metrics`: low-cardinality Prometheus counters without user or session labels.

Both admin endpoints accept `userId`, `sessionId`, `feature`, `model`, `status`, `traceId`,
`startedAfter`, and `startedBefore` filters and require:

```http
X-Observability-Admin-Token: <OBSERVABILITY_ADMIN_TOKEN>
```

Error types include `timeout`, `network`, `authentication`, `rate_limit`, `json_parse`,
`schema_validation`, `invalid_response`, `provider_error`, and `cancelled`. JSON/schema failures and cancellation
log at warning level; provider, network, authentication, rate-limit, and timeout failures
log at error level.

## Model fallback chain

Structured generation no longer converts a provider exception directly into a hardcoded
response. Every request uses this ordered chain:

1. Primary model (`LLM_*` / `AGENT_PRIMARY_*`).
2. Lower-cost cloud fallback (`LLM_FALLBACK_*` / `AGENT_FALLBACK_*`).
3. Lightweight or local model (`LLM_LIGHTWEIGHT_*` / `AGENT_LIGHTWEIGHT_*`).
4. Evidence-bound deterministic response after all configured models fail.

Each failed attempt has its own LLM trace and typed error. A transition emits an
`llm_model_fallback` warning. Exhausting the chain emits an `llm_fallback_exhausted` error
and optionally posts the same metadata-only payload to `LLM_ALERT_WEBHOOK_URL`. Prompts and
responses are never included in alerts.

Example:

```powershell
$env:LLM_MODEL = "gpt-4"
$env:LLM_API_URL = "https://primary.example/v1"
$env:LLM_API_KEY = "primary-key"

$env:LLM_FALLBACK_MODEL = "gpt-3.5"
$env:LLM_FALLBACK_API_URL = "https://fallback.example/v1"
$env:LLM_FALLBACK_API_KEY = "fallback-key"

$env:LLM_LIGHTWEIGHT_PROVIDER = "ollama"
$env:LLM_LIGHTWEIGHT_MODEL = "qwen3:8b"
$env:LLM_LIGHTWEIGHT_API_URL = "http://127.0.0.1:11434/v1"
$env:LLM_ALERT_WEBHOOK_URL = "https://alerts.example/hooks/llm"
```

For the tool-based Agent use the equivalent `AGENT_PRIMARY_*`, `AGENT_FALLBACK_*`, and
`AGENT_LIGHTWEIGHT_*` variables. Ollama uses `http://127.0.0.1:11434/v1` and the placeholder
API key `ollama` automatically when its lightweight model is configured.

Streaming fallback emits a `model.failed` SSE event with `reset=true` after partial output.
Java forwards the boundary and the Vue clients clear the incomplete draft before rendering
tokens from the next model.
