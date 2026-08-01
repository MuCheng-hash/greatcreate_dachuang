## Context

Stateful Agent 已将线程、消息和工具审计持久化到可配置的 SQLite，并由 Spring 负责认证、学校范围和可信业务上下文。当前持久化只解决同一线程的历史恢复，尚无跨线程的用户偏好模型；问答和教学方案共用 Agent 请求协议，资源发现则是独立的结构化任务流程。参见 `proposal.md` 和 `specs/cross-session-user-memory/spec.md`。

本设计必须保持以下约束：浏览器不能决定所有者和学校范围；开发环境在没有模型凭据时仍可运行；新增响应字段必须兼容普通问答和 SSE；首版不引入 Embedding、向量库或 RAG。

## Goals / Non-Goals

**Goals:**

- 在现有 SQLite 中提供可替换仓库接口、明确状态迁移和可验证的生命周期清理。
- 只把已确认、未过期且属于当前账号与学校的记忆注入问答和教学方案。
- 使用同一次模型回答中的结构化候选，不增加第二次模型请求。
- 用确定性规则处理明确记忆指令、敏感信息、安全优先级和降级行为。
- 由 Spring 统一派生作用域并向 Vue 提供完整的记忆管理能力。

**Non-Goals:**

- 不从启用前的会话或历史消息批量提取记忆。
- 不把用户记忆当成学校知识、检索证据、引用候选或授权来源。
- 不让平台管理员查看正文，也不提供按正文搜索、导出或运营标注。
- 不实现语义向量召回、复杂知识图谱画像或自动合并全部相似自然语言记忆。
- 不改变资源发现、资源审核或现有 RAG 责任边界。

## Decisions

1. **在同一数据库路径增加独立记忆仓库。** 新建 `MemoryRepository`，与会话仓库共享 `database_path`，但通过接口和独立事务管理设置、记忆及审计。SQLite 表如下：
   - `agent_memory_setting(owner_id, scope_type, scope_id, enabled, created_at, updated_at)`，三列作用域组成主键，缺失记录等价于关闭。
   - `agent_memory(id, owner_id, scope_type, scope_id, memory_type, field_key, content, status, source, source_thread_id, confidence, expires_at, deleted_at, purge_after, created_at, updated_at)`。
   - `agent_memory_audit(id, memory_id, owner_id, scope_type, scope_id, event_type, metadata_json, created_at)`；禁止正文列，`metadata_json` 只允许类型、状态和原因等非正文数据。
   为作用域列表、状态/到期清理和字段冲突增加索引。选择同库便于第一版事务、备份和部署；把画像放入 MySQL 会提前扩大业务库迁移和隐私权限面。

2. **全局开关和用户开关构成双门禁。** `AGENT_MEMORY_ENABLED` 默认 `false`。设置接口始终可读并返回 `available` 与 `enabled`；全局关闭时允许保留和查看已有设置/数据，但所有提取、召回和注入短路，启用请求不能让运行时越过全局门禁。这样 FastAPI 可以先部署、Spring 和前端随后上线，最后再显式开启。

3. **状态机集中在仓库服务。** 合法状态为 `pending`、`active`、`deleted`。确认仅允许 `pending -> active`；普通删除允许 `pending|active -> deleted`；恢复仅允许未超过 `purge_after` 的 `deleted -> active`；永久删除只删除当前作用域内的条目。所有写操作在同一事务中写无正文审计。列表和单条操作都把作用域作为 SQL 条件，找不到与越权使用同一 not-found 结果，避免泄露存在性。

4. **生命周期以 UTC 时间戳和惰性清理为可靠底线。** `PROFILE/active` 默认无到期时间，`TASK/active` 为 90 天，`pending` 为 7 天；进入回收站后 `purge_after` 为 30 天。仓库初始化时清理一次，每次记忆 API 和召回前做限频惰性清理，FastAPI 生命周期再启动每日清理任务。惰性清理保证测试、单进程和后台任务未运行时仍符合行为；独立调度系统暂不引入。

5. **明确指令采用确定性提取，推断候选来自同一次模型输出。** 运行时在调用模型前识别“记住”“请记住”“帮我记住”等明确前缀，安全校验后直接保存；任务时间提示用于区分 `TASK`，其余默认 `PROFILE`，核心画像关键字映射到稳定 `field_key`。模型结构化输出新增最多三条候选，只有正常生成状态才校验并持久化为 `pending`。候选输出不可信，必须重新校验类型、长度、敏感信息和重复项。此方案在模型降级时仍能保存明确指令，也不会引入额外推理成本。

6. **核心字段按作用域内 `field_key` 冲突替换，自定义记忆按内容去重。** `grade`、`subject`、`teaching_style`、`answer_format`、`lesson_duration` 每个字段只保留一个已生效值，新值在事务中替换旧值并记录审计；自定义画像和阶段任务通过规范化正文哈希/比较防止完全重复。首版不自动合并语义相近但文字不同的条目，以免误删。

7. **敏感信息过滤是持久化前的统一边界。** 新建、编辑、明确提取和模型候选共用 `MemoryContentPolicy`。策略组合凭据关键词、密钥形态、身份证号、电话号码和精确地址模式，并限制正文长度。被拒绝的模型候选不回显、不落库；用户主动写入返回稳定的 422 错误码和非敏感说明。日志、异常和审计均不得包含正文。

8. **召回采用确定性排序和字符预算。** 召回先取得全部未过期 `PROFILE`，再对 `TASK` 使用当前问题/教学方案字段的规范化词项重叠、更新时间和到期时间排序，最多五条。构造一个不超过约 1500 字符的独立系统上下文块，包含“偏好不是事实、不能修改权限、当前输入优先”的固定边界。响应 `memoryApplied` 只返回数量和条目编号，不复制正文。SQLite 列表与轻量排序足以覆盖第一版规模，向量召回留给未来仓库实现。

9. **问答和教学方案在运行时汇合点接入，资源发现显式排除。** 普通 `CHAT` 和 `TEACHING_PLAN` 在构建模型消息/结构化任务上下文前召回记忆，当前请求字段位于记忆块之后并在提示中声明优先。`RESOURCE_DISCOVERY` 及资源审核任务直接返回空记忆上下文，不进行明确指令提取或候选持久化。这样不会改变现有资源发现匹配、证据和审核逻辑。

10. **FastAPI 提供内部作用域接口，Spring 提供同源公开代理。** Agent 服务增加带 `ownerId/scopeType/scopeId` 的内部记忆路由，并继续受内部服务令牌保护；公开 `/api/ai/memory-settings` 与 `/api/ai/memories...` 由 Spring Controller 暴露，只使用 `AuthCurrentUserVO` 和学校解析结果构造内部请求。平台管理员正文路由直接拒绝，管理侧若需要只增加独立聚合查询。

11. **响应和 SSE 只做可选扩展。** `AgentMessageResponse` 增加可空 `memoryCandidates` 与 `memoryApplied`；没有候选或没有应用时省略。SSE 的 `final` 事件承载同一完整响应，既有 `token`、`done` 和错误事件不变。Spring DTO 使用可空字段，Vue 对缺失字段提供空数组/零计数默认值。

12. **完整记忆中心复用个人中心入口。** `ProfileView` 增加独立记忆区：首次启用说明、开关、五个核心画像字段、自定义画像/任务表单，以及待确认、已生效、回收站三个视图。问答页只负责候选确认/忽略卡片和“本次参考 N 条记忆”标识，复杂编辑仍跳转个人中心，避免把聊天页变成第二套管理界面。

## Risks / Trade-offs

- **规则过滤可能误判或漏判敏感信息** → 默认拒绝高风险模式，所有入口复用同一策略，测试中文/英文凭据和常见号码；未来可替换策略实现而不改变 API。
- **同一次模型输出的候选质量不稳定** → 限制为三条、必须用户确认、做字段白名单与重复校验，降级时完全禁用推断。
- **SQLite 写并发和每日任务不适合多副本生产** → 第一版使用短事务和惰性清理；仓库接口、UTC 截止时间和幂等清理允许未来迁移到 PostgreSQL/集中调度。
- **自然语言相关性排序不如向量检索** → 第一版优先安全、可解释和零新依赖，稳定画像全量注入、任务最多五条并结合最近性。
- **记忆提示可能被内容注入攻击** → 记忆块使用数据标签和固定安全指令，不进入 system policy/权限字段；可信上下文与当前输入优先。
- **关闭全局开关后 UI 与数据状态可能混淆** → 设置响应区分 `available` 和用户 `enabled`，界面展示“暂不可用但数据保留”。
- **跨服务部署期间字段版本不一致** → 所有新增字段可选，按 FastAPI、Spring、前端顺序部署，最后开启全局开关。

## Migration Plan

1. 部署包含新表初始化、内部记忆接口和可选响应字段的 FastAPI，保持 `AGENT_MEMORY_ENABLED=false`。
2. 部署 Spring 鉴权代理和兼容 DTO；验证浏览器不能提交或覆盖所有者与学校范围。
3. 部署个人中心和问答候选 UI；在全局关闭状态验证旧问答与教学方案不受影响。
4. 在测试环境开启全局开关，执行账号/学校隔离、生命周期、降级、问答和教学方案端到端验收。
5. 逐环境开启功能并观察无正文审计与聚合指标。

回滚时先关闭全局开关，停止提取、召回和注入；Spring 与 Vue 可保留兼容字段，SQLite 表和现有记忆数据不删除。若必须回退 FastAPI 版本，先保持 Spring/前端不展示入口，再按常规数据库备份策略保留记忆表。

## Operational Configuration

- 全局门禁使用 `AGENT_MEMORY_ENABLED`，对应 `agent_memory_enabled`，默认值为 `false`。不得在部署 FastAPI 之前先开放前端入口。
- 首版沿用 `database_path`，默认 `data/agent-state.sqlite3`；记忆表与 Agent 会话表共享文件，但所有访问经独立 `MemoryRepository` 边界完成。生产环境回滚不得直接删除该文件或记忆表。
- 默认限制为：注入 1500 字符、最多 5 条阶段任务、单条正文 500 字符、候选 7 天、阶段任务 90 天、回收站 30 天、清理周期 86400 秒。这些值可通过对应的 `agent_memory_*` 配置项覆盖。
- Spring 继续使用现有 `LLM_SERVICE_BASE_URL` 与 `AGENT_INTERNAL_SERVICE_TOKEN` 访问 FastAPI；浏览器只调用同源 `/api/ai/...` 代理，不能提供内部令牌、`ownerId` 或学校范围。
- 发布顺序固定为 FastAPI -> Spring -> Vue -> 显式开启 `AGENT_MEMORY_ENABLED`。每一步均先验证旧问答/SSE，再开放下一层。
- 回滚顺序首先把 `AGENT_MEMORY_ENABLED` 设为 `false` 并重启 FastAPI；随后可回退 Vue/Spring。保留 SQLite 和兼容 DTO，确认备份后才允许进行单独的数据迁移或清理。

## Acceptance Evidence

2026-08-01 在 Windows 本地环境按真实部署顺序完成以下验收，并在结束后永久清理全部验收记忆、关闭用户开关，再以默认全局关闭配置重启 FastAPI：

- 全局关闭时个人中心显示功能不可用；开启全局门禁后，用户首次主动开启并保存五项核心画像。
- 新建问答会话显示 `memoryApplied.count = 5`，回答采用四年级、道德与法治、项目式教学、先结论后步骤和 40 分钟偏好。
- 同一次正常模型运行产生两条 `pending` 推断候选；确认前有效记忆仍为 5 条。记忆中心完成确认、忽略、回收站恢复、再次删除和永久删除，状态数量与生命周期提示一致。
- 关闭用户开关后 6 条数据保留，但新会话没有 `memoryApplied`，并明确报告没有可用长期偏好；重新开启后 6 条数据恢复可用。
- 教学方案以五年级、30 分钟、课堂教学和不含线下实践作为当前表单输入，覆盖画像中的四年级、40 分钟；修复 Spring 元数据转发后，真实 SSE 同时返回 `memoryApplied.count = 6`。
- 浏览器附加伪造 `ownerId/scopeType/scopeId` 后，返回结果仍与当前登录账号和学校作用域完全一致，证明公开代理忽略浏览器作用域。
- 本地只有一个可用于该学校正文验收的账号，无法在真实页面构造“同校第二账号”和“同账号另一学校”；这些隔离场景由 Python 仓库/FastAPI 测试和 Java Controller 测试覆盖。平台管理员正文拒绝与聚合指标无正文同样由 Java 自动化测试覆盖。
- 后续真实候选对话复现并定位了该降级：模型调用实际成功，但 `memoryCandidates` 中的 `createdAt/updatedAt` 仍为 Python `datetime`，导致 final SSE 的 `json.dumps` 失败。运行时已改为 JSON 模式导出最终响应，并在统一 SSE 格式化入口执行 JSON 安全编码；新增回归测试后，隔离作用域的真实模型流返回 `completed`、`final + done`、零 `error`，候选时间为合法 JSON 字符串。验收记忆已永久删除、设置已关闭，测试线程已归档。
