# RAG 与 Agent 接口约定

> 版本：v1.0
>
> 状态：待双方确认后冻结
>
> 适用范围：RAG 检索模块与 Agent 智能问答模块之间的协作接口

## 1. 文档目的

本文档用于冻结 RAG 同学与 Agent 同学之间的协作边界、请求字段、响应字段、引用规则和联调样例。

本协议的目标是让两个人可以并行开发：RAG 可以先使用 Mock 数据或独立实现检索，Agent 可以先依赖固定响应完成编排和问答流程，双方最后只需要替换实现而不改变接口结构。

本文档只约定 RAG 与 Agent 的交互，不详细冻结 Agent 与 LLM 服务之间的内部接口。

## 2. 当前项目背景

当前项目已经具备以下相关基础：

- `business-service` 负责学校、资源、审核、地图和主业务数据。
- `content_chunk`、`data_source`、`entity_source_rel` 用于内容分块和来源管理。
- Neo4j 用于图谱实体关系查询和图谱事实补充。
- 教学方案生成已有独立的上下文对象和接口，不应与智能问答协议混用。
- Agent 智能问答需要在学校、区域或资源范围内完成检索增强回答。

现有教学方案接口保持不变：

```http
POST /api/ai/teaching-plans/generate
```

本协议新增的检索能力必须能够被教学方案生成和 Agent 问答分别复用，但不要求本次修改教学方案接口。

## 3. 服务边界

```text
用户问题
   ↓
Agent：识别范围、识别意图、编排步骤
   ↓
KnowledgeRetriever：检索内容分块和图谱事实
   ↓
Agent：合并学校、资源和检索证据
   ↓
LLM：根据受控上下文生成答案
   ↓
Agent：校验引用并返回结构化结果
```

### 3.1 RAG 模块负责

- 根据 Agent 传入的查询条件检索内容分块。
- 根据可用数据查询 Neo4j 图谱事实。
- 返回检索分数、检索方式、实体关联和来源信息。
- 生成稳定的 `citationId` 和引用候选。
- 在 Neo4j 不可用时，尽可能返回 MySQL 内容，并标记降级状态。

### 3.2 Agent 模块负责

- 识别学校、区域或资源范围。
- 识别用户问题意图。
- 调用学校、资源等业务服务获取已审核的业务数据。
- 调用 `KnowledgeRetriever` 获取检索证据。
- 组织有限的上下文并调用 LLM 生成答案。
- 校验模型返回的引用，只允许使用真实存在的 `citationId`。
- 向前端返回自然语言答案、相关资源、推荐主题、引用和后续追问。

### 3.3 明确禁止

- RAG 不负责生成最终自然语言答案。
- Agent 不允许让 LLM 直接生成任意 SQL 或 Cypher 并执行。
- Agent 不允许信任模型自造的来源名称、URL 或引用 ID。
- 前端不直接拼接数据库数据作为最终可信上下文。
- 不因为本协议新增聊天记录表、向量数据库表或其他数据库结构。

## 4. RAG 检索接口

### 4.1 Java 内部接口

当前仓库没有独立 RAG 进程，优先采用 Java 内部接口：

```java
KnowledgeRetrieveResult retrieve(KnowledgeRetrieveRequest request);
```

推荐接口位置：

```text
business-service/src/main/java/com/redculture/platform/service/KnowledgeRetriever.java
```

请求和响应 DTO 使用独立的 `vo.ai` 或 `vo.ai.qa` 类型，不直接复用教学方案专用的 `TeachingPlanContextVO`。

### 4.2 等价 HTTP 接口

如果 RAG 最终独立为 Python 服务，则使用同一套 JSON 字段：

```http
POST /internal/rag/retrieve
Content-Type: application/json
```

Java 内部接口和 HTTP 接口只实现一种即可，不要求同一份代码同时维护两套检索实现。

### 4.3 请求结构

```json
{
  "query": "西柏坡镇适合四年级的红色教育资源",
  "scopeType": "SCHOOL",
  "scopeId": 1,
  "grade": "四年级",
  "theme": "红色文化",
  "topK": 5
}
```

字段约定：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `query` | `String` | 是 | 用户原始问题或 Agent 改写后的检索问题，不得作为 SQL/Cypher 执行 |
| `scopeType` | `String` | 是 | 只允许 `SCHOOL`、`REGION`、`RESOURCE` |
| `scopeId` | `Long` | 是 | 与 `scopeType` 对应的学校、区域或资源 ID |
| `grade` | `String` | 否 | 适用年级，例如 `四年级` |
| `theme` | `String` | 否 | 教学主题，例如 `红色文化`、`敬老志愿服务` |
| `topK` | `Integer` | 否 | 默认 `5`，最大 `8` |

约束：

- `query` 为空时，Agent 不调用 RAG，直接返回参数错误或澄清状态。
- `scopeType` 与 `scopeId` 必须同时存在。
- `topK` 小于等于 0 时使用默认值 `5`；大于 `8` 时按 `8` 处理。
- 所有 JSON 属性使用 lower camel case，与 Java DTO 的 Jackson 序列化结果保持一致。

## 5. RAG 检索响应

### 5.1 顶层结构

```json
{
  "retrievalStatus": "ok",
  "chunks": [
    {
      "citationId": "chunk:1",
      "chunkId": 1,
      "title": "西柏坡红色教育资料",
      "text": "内容片段……",
      "score": 0.91,
      "retrievalMethod": "keyword",
      "entityType": "resource",
      "entityId": 5,
      "sourceId": 3
    }
  ],
  "graphFacts": [
    {
      "citationId": "graph:school:1-resource:5",
      "text": "该学校与该教育资源存在周边资源关系。",
      "subjectId": 1,
      "predicate": "SCHOOL_NEAR_RESOURCE",
      "objectId": 5
    }
  ],
  "citationCandidates": [
    {
      "citationId": "chunk:1",
      "title": "西柏坡红色教育资料",
      "sourceType": "content_chunk",
      "relatedEntityType": "resource",
      "relatedEntityId": 5,
      "excerpt": "内容片段……",
      "url": null
    }
  ]
}
```

### 5.2 顶层字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `retrievalStatus` | `String` | 只允许 `ok`、`empty`、`degraded` |
| `chunks` | `Array` | 内容分块证据，无结果时返回 `[]` |
| `graphFacts` | `Array` | Neo4j 或其他结构化关系事实，无结果时返回 `[]` |
| `citationCandidates` | `Array` | 给 Agent 和 LLM 使用的可展示引用候选，无结果时返回 `[]` |

响应规则：

- 所有数组字段必须返回空数组，不返回 `null`。
- RAG 响应不得包含 `answer`、`message` 等最终回答字段。
- `ok` 表示至少返回一类有效证据。
- `empty` 表示检索正常完成但没有命中证据。
- `degraded` 表示部分检索能力不可用，但仍返回了可用的部分证据。
- Neo4j 不可用时，不得让整个请求失败；如果 MySQL 内容仍可用，返回 `degraded`。

### 5.3 内容分块字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `citationId` | `String` | 稳定引用 ID，例如 `chunk:1` |
| `chunkId` | `Long` | `content_chunk.chunk_id` |
| `title` | `String` | 内容分块标题 |
| `text` | `String` | 可供 LLM 使用的内容，建议限制长度 |
| `score` | `Double` | 检索分数；图谱事实可为空 |
| `retrievalMethod` | `String` | 例如 `keyword`、`fulltext`、`vector` |
| `entityType` | `String` | 关联实体类型，例如 `school`、`resource` |
| `entityId` | `Long` | 关联实体 ID |
| `sourceId` | `Long` | `data_source` 主键，可为空 |

### 5.4 图谱事实字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `citationId` | `String` | 稳定引用 ID，例如 `graph:school:1-resource:5` |
| `text` | `String` | 面向 Agent/LLM 的事实描述 |
| `subjectId` | `Long` | 关系主体 ID |
| `predicate` | `String` | 关系类型，例如 `SCHOOL_NEAR_RESOURCE` |
| `objectId` | `Long` | 关系客体 ID |

### 5.5 引用候选字段

引用候选字段与现有 `GeneratedTeachingPlanCitationVO` 保持兼容：

| 字段 | 类型 | 说明 |
|---|---|---|
| `citationId` | `String` | 唯一且稳定，Agent 只能引用此字段中的值 |
| `title` | `String` | 展示标题 |
| `sourceType` | `String` | 例如 `content_chunk`、`entity_source`、`graph_fact` |
| `relatedEntityType` | `String` | 关联实体类型 |
| `relatedEntityId` | `Long` | 关联实体 ID |
| `excerpt` | `String` | 来源摘要 |
| `url` | `String` | 来源 URL，可为空 |

## 6. Agent 调用 RAG 的标准流程

```text
1. 接收用户问题
2. 确定学校、区域或资源范围
3. 识别问题意图
4. 生成 KnowledgeRetrieveRequest
5. 调用 KnowledgeRetriever
6. 合并已审核的学校/资源业务数据和 RAG 证据
7. 调用 LLM 生成结构化答案
8. 校验 LLM 返回的 citationId
9. 删除非法引用或使用已知引用候选补充
10. 返回最终问答结果
```

### 6.1 首版意图

首版只实现以下意图：

| 意图 | 说明 |
|---|---|
| `NEARBY_RESOURCE` | 查询学校或区域附近的教育资源 |
| `TEACHING_SUGGESTION` | 根据资源生成教学活动建议 |
| `RESOURCE_EXPLANATION` | 解释某个资源、人物、事件或故事 |
| `RELATION_QUERY` | 查询学校、资源、人物和事件之间的关系 |
| `UNKNOWN` | 无法确定意图，需要澄清或返回有限兜底 |

### 6.2 Agent 引用规则

- LLM 返回的引用 ID 必须存在于 `citationCandidates`。
- 不存在的引用 ID必须被删除，不能直接展示给用户。
- LLM 生成的来源名称、URL 或书名不能单独视为可信引用。
- 如果 LLM 没有返回引用，Agent 可以根据实际使用的证据补充最多 5 个引用。
- 最终答案中的事实应能追溯到学校/资源业务数据、内容分块或图谱事实。

## 7. 双方责任和代码边界

### 7.1 RAG 同学负责

- `KnowledgeRetriever` 接口的具体实现。
- 内容分块的关键词、全文或向量检索。
- Neo4j 图谱事实检索。
- `citationId` 生成和引用候选组装。
- RAG 响应状态的准确标记。
- RAG 模块的单元测试和 Mock 响应。

### 7.2 Agent 同学负责

- 学校、区域和资源范围解析。
- 问题意图识别。
- Agent 编排流程。
- RAG 调用和异常降级。
- LLM 调用和结构化答案组装。
- 引用过滤和最终响应校验。
- `/api/ai/qa/ask` 对外接口。
- 前端问答联调。

### 7.3 双方共同确认

- 请求和响应 DTO 字段。
- `scopeType`、`retrievalStatus` 和意图枚举。
- `citationId` 命名格式。
- topK、文本长度和引用数量上限。
- 四个联调问题及其预期结果。

### 7.4 修改边界

- RAG 同学不要修改 Agent 的 Controller、前端问答渲染和最终响应结构。
- Agent 同学不要直接修改 RAG 的检索算法或数据库查询实现。
- 双方都不要为了本协议修改数据库表结构。
- 现有 `/api/ai/teaching-plans/generate` 和 `/llm/teaching-plan/generate` 保持兼容。

## 8. 联调样例

以下样例用于双方使用 Mock RAG 数据进行接口联调。

### 8.1 查询周边资源

用户问题：

```text
某小学附近有哪些适合四年级的红色资源？
```

Agent 意图：`NEARBY_RESOURCE`

RAG 请求重点：

```json
{
  "scopeType": "SCHOOL",
  "scopeId": 1,
  "grade": "四年级",
  "theme": "红色资源"
}
```

预期：返回学校与资源关系、资源教育价值和至少一个可追溯引用。无命中时返回 `empty`，不得编造资源。

### 8.2 设计敬老志愿服务

用户问题：

```text
这所学校可以怎样开展敬老志愿服务？
```

Agent 意图：`TEACHING_SUGGESTION`

RAG 请求重点：

```json
{
  "scopeType": "SCHOOL",
  "scopeId": 1,
  "theme": "敬老志愿服务"
}
```

预期：结合学校周边公益资源、适用年级、活动建议和安全提示生成回答，并返回使用过的引用。

### 8.3 解释单个资源

用户问题：

```text
某个资源适合设计成什么样的思政课？
```

Agent 意图：`RESOURCE_EXPLANATION`

RAG 请求重点：

```json
{
  "scopeType": "RESOURCE",
  "scopeId": 5,
  "grade": "小学高年级"
}
```

预期：回答必须基于资源简介、教育价值、目标年级和相关内容分块；无法找到内容时返回 `empty` 或 `degraded`。

### 8.4 查询人物和学校的关系

用户问题：

```text
某个人物和本地学校有哪些教学关联？
```

Agent 意图：`RELATION_QUERY`

RAG 请求重点：

```json
{
  "scopeType": "SCHOOL",
  "scopeId": 1,
  "theme": "人物与学校教学关联"
}
```

预期：优先使用图谱关系事实，再补充内容分块；Neo4j 不可用时可以降级为 MySQL 内容回答，并明确标记 `degraded`。

## 9. 失败和降级约定

| 场景 | RAG 状态 | Agent 行为 |
|---|---|---|
| 检索命中内容或图谱事实 | `ok` | 正常组装答案 |
| 检索正常但无结果 | `empty` | 返回无法确认的说明和澄清问题，不编造事实 |
| Neo4j 不可用但 MySQL 有结果 | `degraded` | 使用 MySQL 内容继续回答，并说明图谱事实不可用 |
| RAG 服务异常 | 无响应或异常 | Agent 使用已有业务上下文兜底，标记回答降级 |
| 学校/区域/资源范围不明确 | 不调用 RAG | Agent 返回 `UNKNOWN` 或要求用户补充范围 |
| LLM 返回非法引用 | 不影响 RAG 状态 | Agent 删除非法引用后再返回答案 |

## 10. 版本和兼容性

- 本协议版本为 `v1.0`。
- 新增字段必须保持向后兼容，消费者应忽略未知字段。
- 不得直接重命名或删除已冻结字段；需要变更时升级协议版本。
- `retrievalMethod` 可以扩展，例如从 `keyword` 增加 `fulltext`、`vector`，不改变顶层响应结构。
- 后续引入向量数据库时，只替换 RAG 实现，不改变 Agent 请求和响应契约。
- 如果 RAG 从 Java 内部接口迁移为 Python HTTP 服务，必须保持本文档中的 JSON 字段和语义不变。

## 11. 验收清单

- [ ] 双方确认请求字段和字段必填规则。
- [ ] 双方确认 `scopeType`、`retrievalStatus` 和意图枚举。
- [ ] 双方确认 `citationId` 格式和唯一性规则。
- [ ] RAG 可以用 Mock 数据返回本文档定义的响应。
- [ ] Agent 可以仅依赖 Mock RAG 响应完成一次完整问答。
- [ ] 无结果、Neo4j 不可用和非法引用场景均有明确行为。
- [ ] 不修改现有教学方案接口和数据库结构。
- [ ] 代码联调前双方各自完成本模块测试。
