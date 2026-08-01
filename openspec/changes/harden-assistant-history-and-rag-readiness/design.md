## Context

See `proposal.md` for motivation. Agent conversations already use SQLite `agent_message.metadata_json` as an extensible metadata store, and Spring forwards this metadata unchanged through the authenticated history endpoint. The missing fidelity comes from the Python persistence layer storing only citation IDs and from the Vue history mapper rebuilding a deliberately minimal message. Live execution details arrive over SSE and may include raw business output that must not become durable history.

RAG is enabled through environment variables and uses Qdrant on port 6333. The current retriever already catches vector failures and loads keyword chunks, but the external response exposes only a coarse retrieval status. The local Docker container and embedding configuration already exist, so no new retrieval service or database is required.

## Goals / Non-Goals

**Goals:**

- Make stored history reproduce the stable final presentation without replaying the live stream.
- Keep one SQLite source of truth and preserve current account and school isolation.
- Report the retrieval methods actually used and make vector RAG readiness observable.
- Keep the answer path fail-open through keyword retrieval.

**Non-Goals:**

- Persisting per-token events, raw tool output, prompts, or complete tool audit payloads.
- Backfilling citation titles and excerpts that were never saved in old messages.
- Introducing a second conversation database, a browser write-back endpoint, or a mandatory Neo4j dependency.

## Decisions

1. Store a nested `responseSnapshot` with `schemaVersion: 1` in existing assistant-message metadata. Existing top-level metadata remains unchanged for compatibility. This avoids a schema migration and gives future snapshot versions an explicit dispatch point. A new table was rejected because it would split one message across two persistence models.

2. Build the snapshot from the final typed Python response before appending the assistant message. The snapshot duplicates no answer body and contains only stable display fields. Tool entries are restricted to name, final status, and duration; SSE trace details and output summaries are excluded.

3. Align Python citation fallback with Spring's validated-evidence fallback. Evidence IDs are ordered as chunks, graph facts, then remaining citation candidates, deduplicated and capped at five. This makes the persisted citation list and Spring's live normalized list deterministic without adding a Spring-to-Python write-back request.

4. Add optional `retrievalMethods` to the Agent response contract. Methods are derived from retrieved chunk metadata and the presence of graph facts, deduplicated in encounter order, and propagated through Python, Spring, SSE final responses, SQLite snapshots, and Vue history models. Older services and messages can omit the field.

5. Reconstruct historical execution summaries in the browser from final snapshot fields. Live messages keep their current streaming trace. Reopened messages start collapsed and contain only context-compaction, tool completion, effective-model, and final-answer entries; no transient phases are replayed.

6. Keep RAG startup operationally fail-open but verifiable. Local startup starts Qdrant first, polls `/healthz` with a bounded timeout, then starts Spring with synchronization enabled. Readiness requires a zero-failure synchronization report and a non-empty configured collection. If Qdrant later fails, the retriever continues using keyword chunks and reports that method.

## Risks / Trade-offs

- [Snapshot metadata grows because it stores full citations] → Cap citations at five and reuse the already validated fields returned to the user.
- [Python and Spring citation ordering diverge] → Add cross-layer contract tests for the same trusted context and fallback order.
- [Old messages cannot show historic source titles] → Use best-effort legacy rendering and never resolve old IDs against potentially changed current data.
- [A stopped container can recur after manual shutdown] → Document and verify the dependency on every full-RAG startup, while preserving runtime keyword fallback.
- [New response fields reach older clients] → Keep every field optional and preserve existing response paths and required fields.

## Migration Plan

1. Deploy the Python optional response field and snapshot writer first; old Java and Vue clients ignore it.
2. Deploy Spring retrieval-method propagation and then the Vue history reader and labels.
3. Start Qdrant, verify health, restart Spring with startup synchronization, and validate the collection before page acceptance.
4. Rollback is code-only: older clients continue reading message content and legacy metadata; no database downgrade is required.

## Acceptance Evidence

2026-08-01 在 Windows 本地环境完成以下分层验收：

- `red-culture-qdrant` 通过 `/healthz`，Spring 启动同步日志为 `total=12, indexed=12, failed=0`；集合 `red_culture_content_chunks` 状态为 `green`、`points_count=12`，抽样向量维度为 1024。
- 使用 Spring 实际发布的前端产物发起真实流式问答，最终响应报告 `vector+hybrid-rerank` 与 `knowledge-graph`，页面展示 5 条业务来源及图谱事实，并显示“向量检索已完成”。
- 实际 Agent SQLite 中对应助手消息包含 `responseSnapshot.schemaVersion=1`、完整来源、检索方式、模型和脱敏工具摘要；快照中没有原始工具输出、token、提示词或学校上下文。
- 使用真实构建页面和与 Spring API 一致的响应外层完成 A → B → A 切换，回到 A 后来源、检索状态、模型、记忆数、追问和折叠执行摘要保持不变，展开摘要仍不显示受控原始输出。
- 本轮仅有平台管理员的已知登录凭据，而历史正文接口按设计要求学校账号，因此未绕过鉴权或重置学校密码。未 mock 的持久化、Spring 嵌套元数据透传和账号/学校隔离分别由 Python、Java 自动化测试覆盖；页面切换只对历史读取响应做受控拦截。
