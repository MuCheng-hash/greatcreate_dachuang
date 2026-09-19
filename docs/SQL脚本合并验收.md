# SQL 脚本合并验收

验证日期：2026-09-18。三个入口、旧脚本清理、文档和隔离测试配置已落实；IDEA 实际点击执行未验证。现有业务库仅读取结构，没有执行迁移、修改账号或修复孤立档案。未提交或推送 Git。

## 使用步骤

在 IDEA 中选择目标 MySQL 数据源及 schema，用独立控制台执行 `SELECT DATABASE();` 确认目标，随后执行整个文件，包括 `DELIMITER` 和存储过程定义。

| 数据库状态 | 完整执行的文件 | 行为 |
| --- | --- | --- |
| 空数据库 | [database_setup.sql](../data/sql/database_setup.sql) | 创建 68 张表及必要角色权限；非空库拒绝初始化 |
| 已有数据库 | [database_setup_existing.sql](../data/sql/database_setup_existing.sql) | 备份后补齐结构，保留业务数据；冲突报错后修正并重跑 |
| 需要本地演示数据 | [demo_data.sql](../data/sql/demo_data.sql) | 前两种操作成功后单独导入，账号为 test_teacher、test_student，初始密码均为 123456 |

无须 SOURCE 或命令行导入；入口不创建或切换数据库。DDL 无法整体事务回滚，升级前须备份并停止相关应用写入。使用细节见 [SQL 目录说明](../data/sql/README.md)。

## 结构与数据保护

- 对现有字段执行 826 项升级前检查，覆盖类型、长度、可空性、默认值和自动生成行为。发现不兼容定义时停止，不自动收窄、截断或猜测数据转换；有数据的表缺少无默认值必填字段时拒绝补造值。
- 根据当前实体继承关系，补齐审计日志和八张关联表原先遗漏的 updated_at。学校行政区、资源发现、RAG、班级任务、学生足迹、教学方案 adopted、邀请码和两类 Outbox 已包含在统一结构中。
- 已知枚举仅扩展必要值，保留自定义枚举值。校验索引、外键映射及删除/更新规则、CHECK 表达式及启用状态；重复唯一键和孤立引用由数据库拒绝，不删除冲突记录。
- 保留历史额外字段和已有注册表，不执行旧学校精简、资源发现删除、别名删除或学校状态批量修改。账号对学校为多对一关系。
- 演示数据按稳定业务标识查询 ID；检查数据归属、账号与统一档案和师生档案关联。冲突或孤立档案导致事务回滚；重复导入不重置密码。学校坐标修正仅用于新演示学校。
- 正常及异常路径恢复会话设置并释放锁。失败后完整重跑会重新创建辅助存储过程；不要并发执行这些脚本。

**全文索引实现取舍：** MySQL 的 INFORMATION_SCHEMA.STATISTICS 不提供全文解析器，纯 SQL 存储过程无法直接将 SHOW CREATE TABLE 的结果作为普通查询读取。统一升级入口对已有全文索引保守执行原子重建，确保列集合和 ngram 解析器；即使索引已经正确，重复升级仍会产生重建开销。因此“重复执行安全”不代表“没有 DDL 或性能开销”，大表需要维护窗口。新库直接创建正确索引。参考 [MySQL 索引元数据](https://dev.mysql.com/doc/mysql-infoschema-excerpt/8.0/en/information-schema-statistics-table.html)。

## 旧脚本删除与覆盖清单

以下 23 个旧文件已从 SQL 目录删除；本表仅作为历史清单，不是执行指引。旧脚本定义的全部建表对象、58 个通用补字段调用已核对；其余专项列和状态变更结合当前实体、Mapper 与脚本检查。

| 已删除文件 | 覆盖方式或不再执行的行为 |
| --- | --- |
| mysql_red_culture_all_in_one.sql | 基础与扩展结构合入两个结构入口，演示数据独立设计，不执行原删表重建流程 |
| red_culture_platform_database.sql | 角色权限、师生、班级、RAG 运维、Agent 调试等结构合并 |
| add_user_management_module.sql | 统一档案、账号角色、字段及多账号学校关系合并 |
| add_admin_catalog_module.sql | 目录导入、导入明细和投影任务结构合并 |
| add_agent_action_idempotency.sql | Agent 幂等记录和动作 Outbox 合并 |
| add_knowledge_ingestion_mvp.sql | 知识文档、分块、图片和导入任务结构合并 |
| add_knowledge_ingestion_observability.sql | metadata_json、restart_from 合并 |
| add_knowledge_rocketmq.sql | generation、execution_attempts 和导入 Outbox 合并 |
| add_rag_authoritative_web_sources.sql | 权威来源结构合并 |
| add_student_home_browse_history.sql | 学生浏览足迹结构合并 |
| add_teacher_class_management.sql | 任务、提交、附件、点评、进度、收藏结构合并 |
| add_teaching_plan_adoption.sql | adopted 状态合并，已有枚举采用追加扩展 |
| add_teaching_plan_feedback.sql | 生成记录与反馈结构合并 |
| add_teaching_plan_feedback_reasons.sql | reason_codes_json 合并 |
| mysql_ai_poi_resource_discovery.sql | 发现运行、候选、结果与资源外部标识合并 |
| mysql_xibaipo_coordinate_correction.sql | 修正坐标写入新演示学校，不覆盖已有学校 |
| seed_test_user.sql | 演示教师账号合并，移除重置已有密码行为 |
| seed_test_student.sql | 演示学生账号合并，移除重置已有密码行为 |
| seed_teacher_student_profiles.sql | 师生档案与班级演示流程合并，关联由实际 ID 查询 |
| remove_school_registration_add_teacher_invites.sql | 仅保留创建邀请码表的有效结构，不删除已有注册数据或批量变更状态 |
| simplify_school_table_region_hierarchy.sql | 淘汰，不删除当前实体仍使用的学校字段 |
| remove_ai_resource_discovery.sql | 淘汰，不删除当前资源发现表 |
| drop_entity_alias_columns.sql | 淘汰，已有历史别名字段保留 |

根 README、本地启动指南、SQL 目录说明、数据库设计、学校模块说明和 RocketMQ 迁移说明已切换到新流程。隔离测试 Compose 改为挂载统一初始化入口，不自动导入演示账号。PostgreSQL Agent 迁移不属于本次清理范围。

## 实际验证结果

本机 MySQL 8.0.45 使用专用临时数据库；独立 Docker 项目使用 MySQL 8.4.8 和随机本机端口。业务库只复制结构，测试记录为合成数据。

| 检查 | 结果与验证边界 |
| --- | --- |
| 空库与重复初始化 | 通过；68 张表，第二次初始化拒绝，原表结构和行数不变 |
| 未选数据库、缺少基础表 | 通过；三个入口未选库均报错；升级缺少基础结构拒绝执行 |
| 实体映射 | 58 个实体、651 个持久化字段（含继承和约定映射）无缺失 |
| 自定义 Mapper SQL | 20 条 SQL 经临时库 EXPLAIN 通过；包含 6 条动态 SQL 的代表性展开，不等同于所有动态参数组合或应用端到端测试 |
| 业务结构升级 | 复制当前业务库结构后升级两次，合成账号密码、学校关联及地址保留 |
| 重复执行 | 升级前后表结构快照和行数稳定；保留额外历史字段 |
| 部分迁移恢复 | 删除测试库的可空观测字段后重跑补齐，再次升级成功 |
| 类型、可空性、默认值冲突 | 通过；错误指出表和字段，拒绝隐式修改 |
| 非空表缺少必填字段 | 通过；拒绝凭空补造值，已有行数不变 |
| 重复唯一键、孤立外键 | 通过；拒绝建立不合法约束，冲突记录保留 |
| CHECK 约束 | 同名但未启用的 CHECK 被拒绝；启用恢复后正常执行 |
| 枚举扩展 | 补入 adopted，同时保留测试自定义枚举值 |
| 全文解析器 | 同列但无 ngram 的全文索引升级后恢复为 ngram |
| 演示重复导入 | 各表数量稳定，教师学生与账号、统一档案关联正确 |
| 密码 | bcrypt 验证初始密码为 123456；修改教师密码后重导入保持修改值 |
| 冲突与孤立档案 | 拒绝导入；晚期教学方案冲突导致此前本次插入的学校资源关系回滚 |
| 会话与锁 | 成功与异常路径恢复检查开关和 sql_mode，释放 schema 锁 |
| Compose | 配置解析通过；真实官方 MySQL 容器入口初始化成功，初始账号数为零，随后升级和演示导入通过 |
| 静态检查 | 三个入口无 SOURCE、固定 USE、业务 DROP TABLE/TRUNCATE/DELETE；操作文档和测试配置无旧入口引用，差异格式检查通过 |

## 清理及剩余事项

- 已按创建记录、服务地址和精确名称清理本轮 7 个临时库；旧任务的 canonical、init、upgrade 三个库核对保存结构、表集合和合成账号后清理。没有按前缀批量删除数据库。
- 独立 Docker 项目 gc-sql-verify-1376aa7c 的容器、网络和临时卷已清理；原有测试项目未执行删除操作。本轮启动了 Docker Desktop。
- 辅助检查脚本和 JSON 证据保存在本机任务目录 D:/CodexDownload/documents/2026-09-18/sql-consolidation，不属于应用业务代码。
- **未验证 IDEA 的实际点击操作**；已验证 MySQL 真实执行及 Docker 官方初始化入口，不能据此宣称所有 IDEA 版本的语句选择行为均已验证。
- **现有业务库尚未升级**。用户仍需备份、选择维护窗口，再按入口执行；历史孤立师生档案需要单独核实和处理，本次未更改。SQL 演示导入也不代表远程模型、向量化及完整前端演示链路已验证。
- Java Controller、已有 Agent 文档和简历脚本等原有工作区改动保留，未纳入本次修改；未提交或推送。
