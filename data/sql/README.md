# SQL 脚本目录说明

## 推荐执行入口

新建开发数据库使用 `database_setup.sql`。该入口按依赖顺序执行基础结构、用户管理、班级任务、知识库和其他业务扩展，最后写入测试数据。

已有数据库升级时，不要重新执行会删除表的初始化脚本，只执行 `database_setup_existing.sql` 中列出的增量迁移。

## 脚本分类

### 初始化脚本

- `mysql_red_culture_all_in_one.sql`：完整基础数据和学校资源模块初始化，包含 `DROP TABLE`，仅用于可重建的开发库。

### 核心增量迁移

- `add_user_management_module.sql`：账号扩展字段、统一档案和角色权限。
- `add_teacher_class_management.sql`：班级、任务和学生提交。
- `add_student_home_browse_history.sql`：学生资源浏览足迹。
- `add_teaching_plan_feedback.sql` + `add_teaching_plan_feedback_reasons.sql`：教学方案反馈及原因标签。
- `add_knowledge_ingestion_mvp.sql` + `add_knowledge_ingestion_observability.sql`：知识库导入及可观测字段。
- 其他 `add_*.sql`：独立业务能力迁移，按入口脚本顺序执行。

### 数据脚本

- `seed_teacher_student_profiles.sql`：批量示例教师、学生、班级和任务数据。
- `seed_test_user.sql`：测试教师账号。
- `seed_test_student.sql`：测试学生账号。

### 兼容/历史脚本

- 学校模块设计稿、样例数据和独立认证 schema 已删除；对应结构与样例数据已并入 `mysql_red_culture_all_in_one.sql`。
- `remove_ai_resource_discovery.sql`：清理已废弃资源发现表，仅按需执行。
- `simplify_school_table_region_hierarchy.sql`：地址层级兼容迁移，仅按需执行。

## 重要约定

- 同一学校允许多个账号；`school_user_account.school_id` 只能建立普通索引，不能建立唯一索引。
- 用户名、学生账号、班级邀请码等业务唯一约束仍然保留。
- 所有增量脚本应保持可重复执行；非幂等的 `ALTER TABLE ... ADD COLUMN` 只用于明确的一次性迁移。
- 不要把初始化脚本和增量脚本混在同一个已有生产库执行。
