# Business Service

本目录是平台业务服务，负责稳定业务数据、地图展示、学校注册审核、后台管理、Neo4j 图查询和静态前端页面。

## 职责边界

- 负责 MySQL 主业务数据的增删改查、审核和聚合。
- 负责地图、学校周边资源、教学活动方案等业务接口。
- 负责调用 Neo4j 查询图谱关系，但不直接负责大模型生成。
- 负责向前端暴露 `llmServiceBaseUrl`，由前端请求独立的 LLM 服务。

## 目录说明

- `src/main/java/com/redculture/platform/controller`：HTTP 接口层。
- `src/main/java/com/redculture/platform/service`：业务服务接口。
- `src/main/java/com/redculture/platform/service/impl`：业务服务实现。
- `src/main/java/com/redculture/platform/entity`：MySQL 实体。
- `src/main/java/com/redculture/platform/mapper`：MyBatis-Plus Mapper。
- `src/main/java/com/redculture/platform/config`：业务服务配置。
- `src/main/java/com/redculture/platform/vo`：前端和接口返回对象。
- `src/main/resources/static`：当前静态前端页面。

## 启动

```bash
mvn spring-boot:run
```

默认端口：

- `http://localhost:8080`

常用配置：

- `LLM_SERVICE_BASE_URL`：独立 LLM 服务地址，默认 `http://127.0.0.1:5050`。
- `NEO4J_URI`、`NEO4J_USERNAME`、`NEO4J_PASSWORD`：Neo4j 连接配置。
- `AMAP_WEB_KEY`、`AMAP_SECURITY_JS_CODE`：高德地图前端配置。
