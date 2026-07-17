# Neo4j 同步设计方案

本文档用于说明如何将 [mysql_red_culture_schema.sql](</D:/大创项目/mysql_red_culture_schema.sql>) 中的结构化数据同步到 Neo4j，构建“红色文化时空知识图谱”。

## 1. 设计目标

MySQL 与 Neo4j 的职责建议拆分如下：

- MySQL：作为主业务库，负责数据采集、审核、后台管理、接口查询、媒体资源、来源记录、内容分块
- Neo4j：作为知识图谱库，负责实体关系查询、路径分析、图谱可视化、RAG 检索增强

推荐架构：

1. 采集后的结构化数据先入 MySQL
2. 审核通过后的核心实体与关系同步到 Neo4j
3. Python 问答服务优先从 Neo4j 检索结构化事实，再补充 MySQL 中的内容分块或故事正文

## 2. 同步范围

建议首期只同步“核心图谱数据”，不要把所有业务表都搬进 Neo4j。

首期同步：

- `administrative_region`
- `red_site`
- `hero_person`
- `historical_event`
- `memorial_hall`
- `red_story`
- `tag_info`
- `site_event_rel`
- `site_hero_rel`
- `event_hero_rel`
- `memorial_site_rel`
- `memorial_hero_rel`
- `memorial_event_rel`
- `story_entity_rel`
- `entity_tag_rel`

暂不同步或仅保留在 MySQL：

- `audit_log`
- `content_chunk`
- `resource_media`
- `entity_source_rel`
- `data_source`

说明：

- `content_chunk` 更适合给向量库或 MySQL 全文检索使用
- `resource_media`、`entity_source_rel` 可在需要时通过实体 ID 回查 MySQL

## 3. Neo4j 标签设计

建议图谱节点标签如下：

- `Region`
- `Site`
- `Hero`
- `Event`
- `Memorial`
- `Story`
- `Tag`

建议所有节点都统一保留两个基础字段：

- `id`
- `code`

其中：

- `id` 对应 MySQL 主键，便于回写和关联
- `code` 对应业务编码，如 `SITE_HEB_XBP_001`

## 4. MySQL -> Neo4j 节点映射

### 4.1 行政区域

MySQL 表：`administrative_region`

Neo4j：

```cypher
(:Region {
  id: 4,
  name: "西柏坡镇",
  level: "township",
  adcode: "130131100",
  longitude: 113.9776000,
  latitude: 38.3432000,
  intro: "..."
})
```

字段映射：

| MySQL 字段 | Neo4j 属性 |
|---|---|
| `region_id` | `id` |
| `region_name` | `name` |
| `region_level` | `level` |
| `adcode` | `adcode` |
| `center_longitude` | `longitude` |
| `center_latitude` | `latitude` |
| `intro` | `intro` |

### 4.2 革命遗址

MySQL 表：`red_site`

Neo4j：

```cypher
(:Site {
  id: 1,
  code: "SITE_HEB_XBP_001",
  name: "西柏坡中共中央旧址",
  alias: "西柏坡旧址",
  address: "河北省石家庄市平山县西柏坡镇",
  longitude: 113.9783,
  latitude: 38.3439,
  establishedYear: 1948,
  siteLevel: "national",
  protectionLevel: "全国重点文物保护单位",
  openingTime: "08:30-17:00",
  intro: "...",
  historicalBackground: "..."
})
```

字段映射：

| MySQL 字段 | Neo4j 属性 |
|---|---|
| `site_id` | `id` |
| `site_code` | `code` |
| `site_name` | `name` |
| `site_alias` | `alias` |
| `address` | `address` |
| `longitude` | `longitude` |
| `latitude` | `latitude` |
| `established_year` | `establishedYear` |
| `site_level` | `siteLevel` |
| `protection_level` | `protectionLevel` |
| `opening_time_desc` | `openingTime` |
| `intro` | `intro` |
| `historical_background` | `historicalBackground` |

### 4.3 革命英雄

MySQL 表：`hero_person`

Neo4j：

```cypher
(:Hero {
  id: 1,
  code: "HERO_HEB_DXP_001",
  name: "董存瑞",
  alias: null,
  gender: "male",
  birthYear: 1929,
  deathYear: 1948,
  nativePlace: "河北省张家口市怀来县",
  profileSummary: "...",
  mainDeeds: "..."
})
```

字段映射：

| MySQL 字段 | Neo4j 属性 |
|---|---|
| `hero_id` | `id` |
| `hero_code` | `code` |
| `hero_name` | `name` |
| `hero_alias` | `alias` |
| `gender` | `gender` |
| `birth_year` | `birthYear` |
| `death_year` | `deathYear` |
| `native_place_text` | `nativePlace` |
| `profile_summary` | `profileSummary` |
| `main_deeds` | `mainDeeds` |

### 4.4 革命事件

MySQL 表：`historical_event`

Neo4j：

```cypher
(:Event {
  id: 1,
  code: "EVENT_HEB_SDZY_001",
  name: "三大战役指挥决策",
  alias: "西柏坡时期三大战役指挥",
  eventTimeText: "1948年9月至1949年1月",
  startDate: date("1948-09-12"),
  endDate: date("1949-01-31"),
  startYear: 1948,
  endYear: 1949,
  longitude: 113.9783,
  latitude: 38.3439,
  significance: "...",
  process: "...",
  impact: "..."
})
```

字段映射：

| MySQL 字段 | Neo4j 属性 |
|---|---|
| `event_id` | `id` |
| `event_code` | `code` |
| `event_name` | `name` |
| `event_alias` | `alias` |
| `event_time_text` | `eventTimeText` |
| `start_date` | `startDate` |
| `end_date` | `endDate` |
| `start_year` | `startYear` |
| `end_year` | `endYear` |
| `longitude` | `longitude` |
| `latitude` | `latitude` |
| `historical_significance` | `significance` |
| `event_process` | `process` |
| `result_impact` | `impact` |

### 4.5 纪念馆

MySQL 表：`memorial_hall`

Neo4j：

```cypher
(:Memorial {
  id: 1,
  code: "MEM_HEB_XBP_001",
  name: "西柏坡纪念馆",
  address: "河北省石家庄市平山县西柏坡镇",
  longitude: 113.9791,
  latitude: 38.3445,
  exhibitionContent: "...",
  intro: "...",
  openingTime: "09:00-17:00",
  ticketInfo: "免费开放..."
})
```

### 4.6 红色故事

MySQL 表：`red_story`

Neo4j：

```cypher
(:Story {
  id: 1,
  code: "STORY_HEB_XBP_001",
  title: "西柏坡的“两个务必”精神",
  ageGroup: "college",
  summary: "...",
  content: "..."
})
```

### 4.7 标签

MySQL 表：`tag_info`

Neo4j：

```cypher
(:Tag {
  id: 1,
  name: "解放战争",
  type: "period",
  description: "..."
})
```

## 5. 关系映射设计

### 5.1 区域层级

来源：`administrative_region.parent_region_id`

```cypher
(:Region)-[:HAS_CHILD_REGION]->(:Region)
```

### 5.2 遗址所在区域

来源：`red_site.region_id`

```cypher
(:Site)-[:LOCATED_IN]->(:Region)
```

### 5.3 英雄籍贯区域

来源：`hero_person.native_place_region_id`

```cypher
(:Hero)-[:NATIVE_TO]->(:Region)
```

### 5.4 事件发生区域

来源：`historical_event.primary_region_id`

```cypher
(:Event)-[:HAPPENED_IN]->(:Region)
```

### 5.5 纪念馆所在区域

来源：`memorial_hall.region_id`

```cypher
(:Memorial)-[:LOCATED_IN]->(:Region)
```

### 5.6 故事关联区域

来源：`red_story.related_region_id`

```cypher
(:Story)-[:RELATED_TO_REGION]->(:Region)
```

### 5.7 遗址与事件

来源：`site_event_rel`

```cypher
(:Site)-[:OCCURRED_AT]->(:Event)
(:Site)-[:RELATED_TO]->(:Event)
(:Site)-[:MEMORIALIZED_AT]->(:Event)
```

更推荐统一方向：

```cypher
(:Event)-[:OCCURRED_AT {importanceLevel: 5, remark: "..."}]->(:Site)
```

建议：

- 图谱里尽量统一“事件 -> 遗址”的语义方向
- 关系类型可根据 `relation_type` 动态生成

### 5.8 英雄与遗址

来源：`site_hero_rel`

推荐方向：

```cypher
(:Hero)-[:BORN_IN]->(:Site)
(:Hero)-[:FOUGHT_IN]->(:Site)
(:Hero)-[:MEMORIALIZED_AT]->(:Site)
(:Hero)-[:VISITED]->(:Site)
(:Hero)-[:RELATED_TO]->(:Site)
```

### 5.9 英雄与事件

来源：`event_hero_rel`

推荐方向：

```cypher
(:Hero)-[:PARTICIPATED_IN]->(:Event)
(:Hero)-[:LED]->(:Event)
(:Hero)-[:WITNESSED]->(:Event)
(:Hero)-[:MARTYR_IN]->(:Event)
(:Hero)-[:RELATED_TO]->(:Event)
```

### 5.10 纪念馆与遗址

来源：`memorial_site_rel`

```cypher
(:Memorial)-[:LOCATED_AT]->(:Site)
(:Memorial)-[:DISPLAYS]->(:Site)
(:Memorial)-[:RELATED_TO]->(:Site)
```

### 5.11 纪念馆与英雄

来源：`memorial_hero_rel`

```cypher
(:Memorial)-[:COMMEMORATES]->(:Hero)
(:Memorial)-[:EXHIBITS]->(:Hero)
```

### 5.12 纪念馆与事件

来源：`memorial_event_rel`

```cypher
(:Memorial)-[:COMMEMORATES]->(:Event)
(:Memorial)-[:EXHIBITS]->(:Event)
```

### 5.13 故事与实体

来源：`story_entity_rel`

```cypher
(:Story)-[:ABOUT]->(:Site|Hero|Event|Memorial)
(:Story)-[:MENTIONS]->(:Site|Hero|Event|Memorial)
(:Story)-[:TEACHES]->(:Site|Hero|Event|Memorial)
```

### 5.14 实体与标签

来源：`entity_tag_rel`

```cypher
(:Site)-[:HAS_TAG]->(:Tag)
(:Hero)-[:HAS_TAG]->(:Tag)
(:Event)-[:HAS_TAG]->(:Tag)
(:Memorial)-[:HAS_TAG]->(:Tag)
(:Story)-[:HAS_TAG]->(:Tag)
```

## 6. 推荐的 Neo4j 约束与索引

```cypher
CREATE CONSTRAINT region_id_unique IF NOT EXISTS FOR (n:Region) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT site_id_unique IF NOT EXISTS FOR (n:Site) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT hero_id_unique IF NOT EXISTS FOR (n:Hero) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT event_id_unique IF NOT EXISTS FOR (n:Event) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT memorial_id_unique IF NOT EXISTS FOR (n:Memorial) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT story_id_unique IF NOT EXISTS FOR (n:Story) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT tag_id_unique IF NOT EXISTS FOR (n:Tag) REQUIRE n.id IS UNIQUE;
```

建议额外建索引：

```cypher
CREATE INDEX site_name_index IF NOT EXISTS FOR (n:Site) ON (n.name);
CREATE INDEX hero_name_index IF NOT EXISTS FOR (n:Hero) ON (n.name);
CREATE INDEX event_name_index IF NOT EXISTS FOR (n:Event) ON (n.name);
CREATE INDEX memorial_name_index IF NOT EXISTS FOR (n:Memorial) ON (n.name);
CREATE INDEX story_title_index IF NOT EXISTS FOR (n:Story) ON (n.title);
CREATE INDEX region_name_index IF NOT EXISTS FOR (n:Region) ON (n.name);
```

## 7. 同步策略建议

推荐采用“全量初始化 + 增量同步”两阶段方案。

### 7.1 首次全量同步

适用场景：

- 项目初期
- 数据量较小
- 调试图谱结构

步骤：

1. 从 MySQL 查出所有 `review_status='approved'` 且 `is_active=1` 的核心实体
2. 先写节点，再写关系
3. 所有写入操作使用 `MERGE`

### 7.2 后续增量同步

适用场景：

- 后台管理系统开始录入和修改内容
- 数据逐步增长

建议方式：

1. 通过 `updated_at` 增量抽取
2. 每次同步最近 5 分钟或 1 小时变更的数据
3. 关系表也按 `created_at` 或业务更新时间同步

### 7.3 删除策略

不建议直接从 Neo4j 物理删除节点，建议：

1. MySQL 用 `is_active=0`
2. Neo4j 同步时将节点属性 `active=false`
3. 查询时只查 `active=true`

这样更安全，也方便回滚。

## 8. 推荐同步顺序

为避免关系写入时报“节点不存在”，建议按以下顺序同步：

1. `Region`
2. `Site`
3. `Hero`
4. `Event`
5. `Memorial`
6. `Story`
7. `Tag`
8. 区域层级关系
9. 实体与区域关系
10. 实体之间关系
11. 实体与标签关系

## 9. Python 同步脚本建议结构

建议新建：

```text
scripts/
  sync_mysql_to_neo4j.py
```

推荐模块划分：

- `load_mysql_rows()`
- `sync_regions()`
- `sync_sites()`
- `sync_heroes()`
- `sync_events()`
- `sync_memorials()`
- `sync_stories()`
- `sync_tags()`
- `sync_relations()`

推荐技术栈：

- MySQL：`pymysql` 或 `sqlalchemy`
- Neo4j：`neo4j` 官方 Python 驱动

## 10. 节点同步 Cypher 示例

### 10.1 同步区域节点

```cypher
MERGE (r:Region {id: $id})
SET r.name = $name,
    r.level = $level,
    r.adcode = $adcode,
    r.longitude = $longitude,
    r.latitude = $latitude,
    r.intro = $intro
```

### 10.2 同步遗址节点

```cypher
MERGE (s:Site {id: $id})
SET s.code = $code,
    s.name = $name,
    s.alias = $alias,
    s.address = $address,
    s.longitude = $longitude,
    s.latitude = $latitude,
    s.establishedYear = $establishedYear,
    s.siteLevel = $siteLevel,
    s.protectionLevel = $protectionLevel,
    s.openingTime = $openingTime,
    s.intro = $intro,
    s.historicalBackground = $historicalBackground,
    s.active = $active
```

### 10.3 同步事件发生在遗址

```cypher
MATCH (e:Event {id: $eventId})
MATCH (s:Site {id: $siteId})
MERGE (e)-[r:OCCURRED_AT]->(s)
SET r.importanceLevel = $importanceLevel,
    r.remark = $remark
```

## 11. 问答与图谱联动建议

你们后续做“知识图谱 + LLM”时，建议问答链路如下：

1. 用户输入问题
2. 先做实体识别和意图识别
3. 如果涉及“谁、哪里、发生了什么、和谁有关”，优先查 Neo4j
4. 如果需要长文本讲解，再去 MySQL 查 `red_story` 或 `content_chunk`
5. 将“图谱事实 + 故事文本”一起送入 LLM 生成答案

示例：

- “西柏坡和哪些重要事件有关？”
  先查 `(:Site {name:"西柏坡中共中央旧址"})<-[:OCCURRED_AT]-(:Event)`

- “董存瑞有哪些相关遗址和故事？”
  先查 `(:Hero {name:"董存瑞"})-[]->(:Site)` 和 `(:Story)-[:ABOUT|MENTIONS|TEACHES]->(:Hero)`

## 12. 答辩时可以这样表述

如果你们在答辩里解释技术架构，可以简洁说：

1. MySQL 负责结构化主数据管理与后台业务支撑
2. Neo4j 负责红色文化实体及关系的图谱化组织
3. 平台通过 MySQL 到 Neo4j 的同步机制，将“遗址、英雄、事件、纪念馆、故事、区域”等核心数据构造成时空知识图谱
4. LLM 问答阶段先检索知识图谱获取可靠事实，再结合故事文本生成自然语言回答，从而降低大模型幻觉风险

## 13. 你们下一步最建议补的内容

1. 完善 `scripts/sync_mysql_to_neo4j.py` 原型脚本
2. 再补一组常用 Cypher 查询模板
3. 最后接入 LLM 服务做 RAG 检索
