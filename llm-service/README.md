# LLM Service

本目录是独立 LLM 服务入口，负责地图讲解、学校周边资源问答、教学方案生成和 Agent 运行时。Agent 使用 FastAPI + LangChain，业务数据和 RAG 通过 Java 内部受控工具接口访问。

## 启动

```bash
pip install -r requirements.txt
python app.py
```

默认监听：

- `http://127.0.0.1:5050`

已提供接口：

- `POST /llm/town/explain`
- `POST /llm/town/ask`
- `POST /llm/school/explain`
- `POST /llm/school/ask`
- `POST /llm/agent/answer`
- `POST /llm/agent/run`
- `POST /llm/agent/stream`
- `POST /llm/teaching-plan/generate`
- `GET /health/live`
- `GET /health/ready`

`/llm/agent/answer` 保留为兼容接口。新的 `/llm/agent/run` 会使用
LangChain Agent，根据受控工具结果生成结构化答案；`/llm/agent/stream` 通过 SSE
返回运行、工具、模型和最终结果事件。Agent 不执行 SQL 或 Cypher，引用只能来自
工具返回的证据。未配置真实模型或模型响应不可用时，接口返回
`generationStatus=degraded` 和本地结构化兜底答案。

结构化教学方案生成接口支持本地兜底。如果配置了 OpenAI-compatible 模型服务，会优先调用真实模型：

- `LLM_API_URL`：模型接口地址
- `LLM_BASE_URL`：可选的兼容接口基础地址；配置后服务会自动补充 `/chat/completions`
- `LLM_API_KEY`：模型密钥
- `LLM_MODEL`：模型名称，默认 `qwen-plus`
- `LLM_TIMEOUT_SECONDS`：调用超时秒数，默认 `20`

例如使用阿里云百炼兼容接口时，可以在启动 LLM 服务前配置：

```powershell
$env:LLM_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
$env:LLM_API_KEY = "你的百炼API_KEY"
$env:LLM_MODEL = "qwen-plus"
python app.py
```

也可以只配置 `LLM_BASE_URL`，服务会自动补充 `/chat/completions`。API Key 仅通过环境变量传入，不能写入仓库。

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

模型配置优先级为 `AGENT_*` 专用配置，未配置时回退到旧的 `LLM_API_URL`、
`LLM_BASE_URL`、`LLM_API_KEY` 和 `LLM_MODEL`，因此旧启动方式保持兼容。

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

首版使用内存 Checkpointer，服务重启后会话状态会丢失；生产环境再替换为 Redis 或其他持久化 Checkpointer。
运行日志会携带 `runId`、`conversationId`、模型、提示词版本、工具名称、耗时、token usage、
生成/检索状态、降级级别和错误类型；模型未返回 usage 时保持为空，不伪造统计数据。
