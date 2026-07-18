# 红韵乡途项目结构

本仓库按“业务服务 + LLM 服务 + 数据资料 + 脚本工具 + 设计文档”整理，避免把前端、后端、数据清洗和大模型能力混在根目录。

## 目录说明

- `business-service/`：Spring Boot 业务服务，负责学校、资源、地图、后台管理、Neo4j 图查询和静态前端页面。
- `llm-service/`：独立 LLM 服务，负责问答、讲解、教学方案生成等后续大模型能力入口。
- `data/sql/`：MySQL 建表、初始化和样例数据脚本。
- `data/templates/`：数据采集模板。
- `data/raw/`：原始资料，如 PDF、政策或统计文件。
- `data/samples/`：样例 GeoJSON、边界数据等演示素材。
- `scripts/`：数据处理、模板生成、MySQL 到 Neo4j 同步等脚本。
- `docs/`：需求、数据库、接口、Neo4j 同步等设计文档。

## 运行顺序

1. 启动 MySQL，并导入 `data/sql/` 中的建表和初始化脚本。
2. 启动 `business-service/`，默认监听 `http://localhost:8080`。
3. 打开 `http://localhost:8080/`，不要直接打开本地 `file://` 页面，也不要使用 `5173`、`5500` 等其他前端端口访问认证页面。
4. 用浏览器访问 `http://localhost:8080/api/auth/me` 做健康检查；返回 JSON 即表示业务服务已响应，未登录时 `data` 为 `null` 是正常的。
5. 如需图谱能力，再运行 `scripts/sync_mysql_to_neo4j.py` 同步 Neo4j；Neo4j 不启动时，学校注册、审核和登录仍使用 MySQL，可独立工作。
6. 如需问答或教学方案，再启动 `llm-service/`，默认监听 `http://127.0.0.1:5050`。

学校注册成功后，管理员从同源地址 `http://localhost:8080/admin.html` 登录并审核申请。认证请求使用 `/api/...` 相对路径和浏览器 Session，因此页面必须从业务服务的 8080 地址打开。

## 服务边界

业务服务只负责稳定业务数据、地图和后台流程；LLM 服务只负责把检索结果组织成自然语言答案或教学方案。后续接入 RAG、Agent、向量库时，优先放在 `llm-service/`，不要塞回业务服务。
