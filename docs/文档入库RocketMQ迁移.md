# 文档入库 RocketMQ 迁移

## 架构与保证

上传原文件到 MinIO 后，Java 在同一 MySQL 事务中写入文档、任务和
`knowledge_ingest_outbox`。后台发布器领取事件，使用 RocketMQ 5.x 普通消息发送，
Broker 确认后标记 `PUBLISHED`。发送失败自动补发，发布者崩溃后可回收过期租约。
这是一条至少一次投递链路，不承诺跨 MySQL、MinIO、Qdrant 的 exactly-once。

消息体：`schemaVersion=1`、`eventId`、`jobId`、`documentId`、`generation`。
正文和文件不进入消息队列。Topic 为 `knowledge-ingest`，消费组为
`knowledge-ingest-worker`。Java SDK 5.0.8、Python `rocketmq-python-client==5.1.1`，
Broker/Proxy 镜像固定为 `apache/rocketmq:5.3.2`。

Worker 每次领取一条消息，持有 `knowledge-document:<id>` MySQL 连接级锁执行完整流程。
重试和删除使用相同锁；文档正在处理时返回现有异常响应，调用方稍后重试。
任务 ID 与文档 ID 分开查询。旧执行批次及已完成任务不重复执行。
不可见时间默认 120 秒，每 30 秒续期；连接/续期丢失或停止信号会阻止后续写入与 ACK。
已经发出的外部请求不能撤回，因此进程/网络故障后的部分外部写入通过完整重跑修复。

每批次最多实际执行 3 次。可恢复异常返回待处理并重投；不可恢复的空文本/格式错误，
以及耗尽执行次数的任务持久化为 `FAILED` 后确认。Broker 重投上限设为 16，
专门的 `knowledge-ingest-worker-dead` 消费组读取 `%DLQ%knowledge-ingest-worker`，
将异常耗尽投递的同批次任务同步为 `FAILED`。原始消息格式错误只记录 messageId，确认后丢弃。
日志和错误摘要不输出文件正文及模型凭据。
死信同步组不执行入库，重投上限单独设置为 1000000，避免 MySQL 暂时不可用时很快
进入无人处理的二级死信队列。仍需监控死信积压和 Broker 保留期限，不能将消息队列视为永久档案。

成功/降级状态在同一数据库事务内写入后 ACK。图片记录 UPSERT；正文分块批量写入；
向量使用确定性 ID，完整重跑前清理该文档旧向量。文件 Hash 相同的不同上传保留各自索引，
避免删除其中一个文档后另一个文档失去内容。图片 Hash 去重继续保留。
重试不是 LangGraph checkpoint 恢复；兼容接收旧 `restartFrom`，实际统一为 `VALIDATE`。

## 部署与切换

1. 暂停上传/重试入口并停止旧、新 Worker，备份数据库。
2. 在 IDEA 选择目标 MySQL 数据库后，完整执行 `data/sql/database_setup_existing.sql`；空库则执行 `data/sql/database_setup.sql`。
   统一入口包含任务观测列、generation、execution_attempts 和独立 Outbox 表，也核对其他业务结构；不删除现有数据，不在应用启动时自动执行。失败后修正冲突并重跑完整脚本。详见 [SQL 使用说明](../data/sql/README.md)。
3. 配置现有 Compose 所需 PostgreSQL 环境变量，执行：

   ```powershell
   docker compose -f docker-compose.rag.yml up -d rocketmq-init
   ```

   NameServer 和 Broker 无宿主机公开端口；Proxy 内外端口统一为 `18081`，仅映射
   `127.0.0.1:18081`，避免路由响应返回错误端口。
   初始化容器设置专用卷权限、创建 Topic 和消费组。此为单 Broker 本地开发部署，
   不是生产高可用集群。默认关闭 TLS/认证，仅限可信本地环境。

   等待 `rocketmq-init` 退出码为 0 后才启用 Java 发布器。初始化会为正常/死信消费组的
   所有队列设置最早消费位置，避免首次启动跳过积压消息。一次性标记保存在
   `rocketmq_store/ingestion-bootstrap`，后续启动不重置位置。必须连同 Broker 数据卷
   一起保留；不要单独删除标记，也不要在已运行的集群上盲目执行首次初始化。
   这是对 [RocketMQ 5.3.2 Proxy 首次 POP 消费行为](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/proxy/src/main/java/org/apache/rocketmq/proxy/grpc/v2/consumer/ReceiveMessageActivity.java)
   的适配，不能仅依赖消费组的 `consumeFromMinEnable`。

4. Java 配置 `ROCKETMQ_ENDPOINTS=localhost:18081`；Compose Worker 使用
   `rocketmq-proxy:18081`。双方 `KNOWLEDGE_QDRANT_COLLECTION` 必须一致，默认延续
   `RAG_QDRANT_COLLECTION` / `red_culture_content_chunks`，不会搬迁原向量数据。
   两端请求超时通过 `ROCKETMQ_REQUEST_TIMEOUT_SECONDS` 设置，默认 10 秒；Worker
   `INGEST_MAX_ATTEMPTS` 只允许 1–3，默认 3。可见性与续期间隔分别使用
   `INGEST_INVISIBLE_SECONDS` / `INGEST_RENEW_SECONDS`，默认 120 / 30 秒。
5. 在有 PyMySQL 和 redis 包的迁移环境设置 `REDIS_URL`、`MYSQL_HOST`、`MYSQL_PORT`、
   `MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_DATABASE`，执行：

   ```powershell
   python scripts/migrate_knowledge_queue.py
   python scripts/migrate_knowledge_queue.py --apply
   ```

   第一次只盘点；第二次为 `PENDING/RUNNING` 任务补建 Outbox。按任务和批次唯一约束
   保证重复执行不会新增重复事件。不弹出、不删除旧 Redis 消息；成功和降级完成任务不迁移。
   历史 `FAILED` 任务通过重试 API 显式创建新批次。
6. 启动新版 Java，观察 Outbox 发送状态，再启动新版 Worker 并恢复上传：

   ```powershell
   docker compose -f docker-compose.rag.yml up -d --build ingestion-worker
   ```

7. 核对未完成任务数量、失败摘要和分块结果后完成切换。不要同时运行 Redis 与 RocketMQ
   两套消费者。回退前同样停止入口和 Worker，核对 MySQL 未完成任务后再恢复旧链路；
   不直接重放全部旧 Redis 消息，不回滚或删除新增数据库列。

## 验证

测试栈仅使用独立项目 `knowledge-mq-test` 和 `knowledge_ingest_test` 数据库。
MySQL 端口 13316、MinIO 19000、Qdrant 16333，模型向量使用固定测试桩：

```powershell
docker compose -p knowledge-mq-test -f docker-compose.rag.yml -f docker-compose.ingestion-test.yml up -d rocketmq-init ingestion-test-mysql minio qdrant
$env:INGEST_INTEGRATION='true'
python -m pytest ingestion-worker/test_runtime.py ingestion-worker/test_integration.py -q
# 在 business-service 目录、JDK 21 下执行：
mvn '-Dskip.npm=true' -Pingestion-tests '-Dtest=KnowledgeOutboxIntegrationTest' test
```

Python 测试依赖通过 `pip install -r ingestion-worker/requirements-test.txt` 安装。
等待测试 MySQL 健康检查通过、`rocketmq-init` 成功退出后再执行测试。
默认未设置 `INGEST_INTEGRATION=true`
时，真实服务测试跳过。`ingestion-tests` Maven profile 只编译知识入库测试，不掩盖
默认全量测试中的其他模块错误。

验证场景包括事务回滚、发布失败补发、发布确认后崩溃与租约恢复、并发手动重试、
删除互斥、三种文件格式、重复消息与旧批次、三次执行失败、进程终止、ACK 丢失、
Broker 重启、死信同步、首次积压消费、图片与分块/向量重跑、旧任务幂等迁移。
故障测试允许 Broker 重启期间临时路由缺失和失效 ACK 凭据，但只有重新投递并确认成功
才计入完成；超过测试期限仍会失败。

模型侧使用可控向量桩；PDF 实际验证了 `pypdf` 文本提取降级路径。未验证真实 MinerU、
视觉模型和嵌入模型服务，因此不据此宣称复杂扫描 PDF 或真实模型效果已经通过验收。
当前默认 Java 全量测试编译被原有 `AuthServiceImplTest` 引用缺失的
`SchoolRegistrationMapper` 阻塞，本次没有修改该无关测试。

### 本次验证结果（2026-09-15）

- Java 入库集成测试 6 项通过；使用独立真实 MySQL 和官方 Java Producer。
  发布器异常通过可控故障注入验证，Broker 确认后崩溃通过中断状态更新模拟。
- Python 总回归 24 项通过（13 项运行时测试、11 项真实服务场景）；随后又将
  Markdown、DOCX、PDF 三项格式测试改为经真实 MQ 收取，补验 3 项全部通过。
- MySQL、RocketMQ、MinIO、Qdrant 均为实际容器；处理进程终止及 Broker 重启实际执行。
- Worker 镜像构建成功，容器输出 `Ingestion consumer ready`，收到停止信号后退出码为 0。
- Compose 配置和 `git diff --check` 通过。初始化再次执行确认两组均不重置消费位置。
- 所有数据库写入限于独立测试库；未迁移现有业务库，未清空旧 Redis，未提交或推送 Git。

本地 JUnit 报告在 `business-service/target/surefire-reports/`，Python 报告在
`tmp/ingest-final.xml` 和 `tmp/ingest-formats.xml`。这些运行产物不纳入版本控制。

Windows 的临时目录如果被启动宿主重定向，JDK NIO 可能报
`Unable to establish loopback connection / UnixDomainSockets.connect0`。
本次通过在当前 PowerShell 中将 `$env:TEMP`、`$env:TMP` 指向已存在的项目 `tmp`
目录完成真实 Java SDK 收发；无需更改全局系统配置或 JDK Selector 实现。

## 排障

- `PENDING` 不执行：检查 Outbox 是否积压、发布器是否启用、Proxy 是否可达。
- Outbox 长期 `PROCESSING`：检查 `lease_expires_at`，过期后应由发布器领取。
- `RUNNING` 长时间未结束：检查 Worker、消息续期和消费者堆积；禁止直接并发启动同一任务。
- `FAILED`：查看 `error_summary/current_node/execution_attempts`，修复配置后手动重试。
- MQ 不可用时上传仍可创建待处理任务；MySQL 事务失败则上传失败并尝试清理新文件。
- Broker 卷不可写：检查 `rocketmq-volume-init`；Proxy 找不到集群：检查
  `proxy.json` 的 `rocketMQClusterName` 与 Broker 配置一致。
