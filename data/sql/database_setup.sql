-- 开发环境标准初始化入口
-- 执行方式：在 data/sql 的上级目录启动 MySQL 客户端后执行 SOURCE data/sql/database_setup.sql;
-- 注意：mysql_red_culture_all_in_one.sql 会重建基础业务表，仅适用于可重建的开发数据库。

SOURCE data/sql/mysql_red_culture_all_in_one.sql;
SOURCE data/sql/red_culture_platform_database.sql;
SOURCE data/sql/add_user_management_module.sql;
SOURCE data/sql/add_teacher_class_management.sql;
SOURCE data/sql/add_student_home_browse_history.sql;
SOURCE data/sql/add_teaching_plan_feedback.sql;
SOURCE data/sql/add_teaching_plan_feedback_reasons.sql;
SOURCE data/sql/add_knowledge_ingestion_mvp.sql;
SOURCE data/sql/add_knowledge_ingestion_observability.sql;
SOURCE data/sql/add_rag_authoritative_web_sources.sql;
SOURCE data/sql/add_agent_action_idempotency.sql;
SOURCE data/sql/add_admin_catalog_module.sql;
SOURCE data/sql/mysql_ai_poi_resource_discovery.sql;
SOURCE data/sql/seed_teacher_student_profiles.sql;
SOURCE data/sql/seed_test_user.sql;
SOURCE data/sql/seed_test_student.sql;
