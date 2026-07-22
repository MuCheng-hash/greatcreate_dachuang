# 红韵乡途

红韵乡途是面向乡村学校周边思政教育资源的数字地图平台。本仓库按“业务服务 + LLM 服务 + 数据资料 + 脚本工具 + 设计文档”组织，避免把前端、后端、数据清洗和大模型能力混在根目录。

## 目录说明

- `business-service/`：Spring Boot 业务服务，负责学校、资源、地图、后台管理、Neo4j 图查询和静态前端页面。
- `llm-service/`：独立 LLM 服务，负责问答、讲解和教学方案生成；当前不配置外部模型也可返回本地兜底结果。
- `data/sql/`：MySQL 建表、初始化和样例数据脚本。
- `data/templates/`：数据采集模板。
- `data/raw/`：原始资料，如 PDF、政策或统计文件。
- `data/samples/`：样例 GeoJSON、边界数据等演示素材。
- `scripts/`：数据处理、模板生成、MySQL 到 Neo4j 同步等脚本。
- `docs/`：需求、数据库、接口、Neo4j 同步等设计文档。

## 运行环境

本项目以 Windows 本地开发环境为主，以下命令均在 PowerShell 中执行。

| 依赖 | 要求 | 是否必需 | 说明 |
| --- | --- | --- | --- |
| JDK | 21 | 是 | `business-service/pom.xml` 指定 Java 21 |
| Maven | 可运行 `mvn` 命令 | 是 | 仓库暂未提供 Maven Wrapper |
| MySQL | 8.0 | 是 | 初始化脚本使用 MySQL 8.0 排序规则和 JSON 类型 |
| Python | 3.9 或更高版本 | 启动 LLM 时必需 | 用于 Flask LLM 服务和数据脚本 |
| Neo4j | 支持 Bolt 连接 | 否 | 只在使用图谱查询和同步时需要 |

首次启动前可以检查本机环境：

```powershell
java -version
mvn -version
mysql --version
python --version
```

## 服务与端口

| 服务 | 默认地址或端口 | 是否必需 |
| --- | --- | --- |
| MySQL | `localhost:3306` | 是 |
| Spring Boot 业务服务 | `http://localhost:8080` | 是 |
| FastAPI Stateful Agent 服务 | `http://127.0.0.1:5050` | 否 |
| Neo4j Bolt | `bolt://127.0.0.1:7687` | 否 |

最小可运行组合是 MySQL + Spring Boot 业务服务。LLM 和 Neo4j 可以根据需要再启动。

## 完整启动步骤

以下步骤默认当前目录为仓库根目录。

### 1. 初始化 MySQL

确认 MySQL 已启动，然后从仓库根目录进入 MySQL 客户端：

```powershell
mysql --default-character-set=utf8mb4 -u root -p
```

在 MySQL 客户端中导入全量脚本：

```sql
SOURCE data/sql/mysql_red_culture_all_in_one.sql;
```

该脚本会创建 `red_culture_platform` 数据库、表结构和演示数据。

已有数据库升级到 AI 周边资源发现功能时，不要重置全库，改为执行增量脚本：

```sql
SOURCE data/sql/mysql_ai_poi_resource_discovery.sql;
```

> **数据重置警告：** `mysql_red_culture_all_in_one.sql` 会先执行 `DROP TABLE`，删除并重建项目相关表。它只适合首次初始化或数据可以丢弃的本地开发库，不要对包含有效数据的数据库直接执行。全量脚本已经合并学校、认证和样例数据内容，导入后不要再重复执行 `data/sql/` 下的拆分脚本。

如果 `SOURCE` 无法识别相对路径，请改用仓库的绝对路径，并统一使用正斜杠，例如：

```sql
SOURCE D:/path/to/greatcreate_dachuang/data/sql/mysql_red_culture_all_in_one.sql;
```

### 2. 配置业务服务

业务服务配置文件位于 `business-service/src/main/resources/application.yml`。默认连接：

- 数据库：`jdbc:mysql://localhost:3306/red_culture_platform`
- 数据库用户：`root`
- 数据库密码：`root`

如果本机 MySQL 账号、密码、端口或数据库地址不同，请先修改该文件中的 `spring.datasource` 配置。

其他运行参数可以在启动业务服务前通过 PowerShell 环境变量覆盖：

| 环境变量 | 用途 | 默认行为 |
| --- | --- | --- |
| `APP_ADMIN_USERNAME` | 平台管理员账号 | `admin` |
| `APP_ADMIN_PASSWORD` | 平台管理员密码 | `admin123456` |
| `APP_ADMIN_DISPLAY_NAME` | 平台管理员显示名 | `平台管理员` |
| `AMAP_WEB_KEY` | 高德地图 Web 端 Key | 建议配置自己的 Key |
| `AMAP_SECURITY_JS_CODE` | 高德地图安全密钥 | 默认空值 |
| `AMAP_WEB_SERVICE_KEY` | 服务端高德 Web 服务 Key，用于周边 POI 检索和详情 | 空；未配置时仅显示正式资源 |
| `LLM_SERVICE_BASE_URL` | 前端访问 LLM 服务的地址 | `http://127.0.0.1:5050` |
| `NEO4J_URI` | Neo4j Bolt 地址 | `bolt://127.0.0.1:7687` |
| `NEO4J_USERNAME` | Neo4j 用户名 | `neo4j` |
| `NEO4J_PASSWORD` | Neo4j 密码 | 本地开发默认值见配置文件 |

例如，在当前 PowerShell 会话中设置管理员和地图配置：

```powershell
$env:APP_ADMIN_USERNAME = "admin"
$env:APP_ADMIN_PASSWORD = "请替换为本地开发密码"
$env:APP_ADMIN_DISPLAY_NAME = "平台管理员"
$env:AMAP_WEB_KEY = "请替换为高德地图 Web 端 Key"
$env:AMAP_SECURITY_JS_CODE = "请替换为高德地图安全密钥"
$env:AMAP_WEB_SERVICE_KEY = "请替换为具有 Web 服务权限的高德 Key"
```

默认管理员账号 `admin / admin123456` 仅用于本地开发。非本地环境必须通过 `APP_ADMIN_USERNAME` 和 `APP_ADMIN_PASSWORD` 覆盖默认值。

`AMAP_WEB_SERVICE_KEY` 只由业务服务读取，不会返回浏览器。教师进入“地图资源”后会自动读取 24 小时缓存或启动周边候选发现；未配置该 Key 时，正式资源地图仍可正常使用。

### 3. 启动业务服务

打开一个新的 PowerShell 窗口，从仓库根目录执行：

```powershell
Set-Location "business-service"
mvn spring-boot:run
```

看到服务启动完成后，业务服务默认监听 `http://localhost:8080`。静态前端由 Spring Boot 同源提供，不需要单独启动 Vite、Live Server 或其他前端开发服务器。

### 4. 启动 LLM 服务（可选）

问答、讲解和教学方案生成功能需要独立 LLM 服务。打开另一个 PowerShell 窗口，从仓库根目录执行：

```powershell
Set-Location "llm-service"
python -m venv ".venv"
& ".venv/Scripts/Activate.ps1"
python -m pip install -r "requirements.txt"
python "app.py"
```

服务默认监听 `http://127.0.0.1:5050`。未配置外部模型时，当前实现会返回本地结构化兜底结果；如需调用 OpenAI-compatible 模型服务，可在启动前设置：

| 环境变量 | 用途 | 默认值 |
| --- | --- | --- |
| `LLM_API_URL` | OpenAI-compatible Chat Completions 地址 | 空，表示不调用外部模型 |
| `LLM_API_KEY` | 模型服务密钥 | 空 |
| `LLM_MODEL` | 模型名称 | `qwen-plus` |
| `LLM_TIMEOUT_SECONDS` | 调用超时秒数 | `20` |

不要把真实的 `LLM_API_KEY` 写入 README 或提交到仓库。

### 5. 启动 Neo4j 并同步数据（可选）

只有图谱查询需要 Neo4j。学校注册、管理员审核、登录和基于 MySQL 的业务流程不依赖 Neo4j，可以独立运行。

需要图谱能力时：

1. 启动 Neo4j，并确认 Bolt 端口可用。
2. 检查 `scripts/sync_mysql_to_neo4j.py` 顶部的 `MYSQL_CONFIG` 和 `NEO4J_CONFIG`，使其与本机环境一致。
3. 在 Python 环境中安装同步脚本依赖并运行：

```powershell
python -m pip install pymysql neo4j
python "scripts/sync_mysql_to_neo4j.py"
```

业务服务连接 Neo4j 时使用 `NEO4J_URI`、`NEO4J_USERNAME` 和 `NEO4J_PASSWORD`。

## 启动验证

业务服务启动后，依次检查：

- 平台首页：<http://localhost:8080/>
- 管理后台：<http://localhost:8080/admin.html>
- 当前登录状态：<http://localhost:8080/api/auth/me>

`/api/auth/me` 返回 JSON 即表示业务服务已经响应；未登录时 `data` 为 `null` 属于正常情况。学校注册成功后，管理员可从同源的管理后台登录并审核申请。

LLM 服务启动后，可以在 PowerShell 中发送一个最小请求：

```powershell
$body = @{
    regionName = "西柏坡镇"
    question = "请介绍这里的红色文化"
    markers = @()
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
    -Uri "http://127.0.0.1:5050/llm/town/ask" `
    -ContentType "application/json; charset=utf-8" `
    -Body $body
```

返回包含 `answer` 的 JSON 即表示 LLM 服务工作正常。

## 常见问题

### 业务服务提示数据库连接失败

- 确认 MySQL 服务已启动且监听 `3306`。
- 确认已经导入 `mysql_red_culture_all_in_one.sql`。
- 核对 `application.yml` 中的数据库地址、用户名和密码。
- 确认当前账号有访问 `red_culture_platform` 的权限。

### `8080` 或 `5050` 端口被占用

先停止占用端口的旧进程，再重新启动对应服务。业务端口由 `server.port` 控制；LLM 服务端口目前在 `llm-service/app.py` 的启动入口中定义。

### 页面打开后接口请求失败或无法保持登录状态

必须从 `http://localhost:8080/` 打开页面。不要直接双击 HTML 形成 `file://` 地址，也不要通过 `5173`、`5500` 等其他端口访问认证页面。认证接口使用 `/api/...` 相对路径和浏览器 Session，需要与业务服务保持同源。

### LLM 服务未启动

地图、学校注册、管理员审核和登录等业务仍可运行，但问答、讲解和教学方案生成功能无法访问 `5050` 上的独立服务。需要这些功能时再启动 `llm-service`。

### Neo4j 未启动

基于 MySQL 的核心业务仍可运行，只有图谱查询或同步会失败。暂不使用图谱能力时可以不启动 Neo4j。

### PowerShell 无法激活 Python 虚拟环境

可以不激活虚拟环境，直接使用其中的解释器：

```powershell
& ".venv/Scripts/python.exe" -m pip install -r "requirements.txt"
& ".venv/Scripts/python.exe" "app.py"
```

## 服务边界与详细文档

业务服务负责稳定业务数据、地图、认证和后台流程；LLM 服务负责把检索结果组织成自然语言答案或教学方案。后续接入 RAG、Agent 或向量库时，优先放在 `llm-service/`，不要塞回业务服务。

- [业务服务说明](business-service/README.md)
- [LLM 服务说明](llm-service/README.md)
