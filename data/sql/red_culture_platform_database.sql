-- 红色文化智慧教育平台待完善与新增数据库脚本
-- 适用数据库：MySQL 8.x
-- 说明：
-- 1. 新增表使用 CREATE TABLE IF NOT EXISTS，便于重复导入。
-- 2. 现有表补充字段和索引通过临时存储过程判断是否存在，减少重复导入报错。

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =========================================================
-- 一、角色与权限
-- =========================================================

CREATE TABLE IF NOT EXISTS sys_role (
  role_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  role_code VARCHAR(50) NOT NULL COMMENT '角色编码，如 platform_admin、teacher、student',
  role_name VARCHAR(100) NOT NULL COMMENT '角色名称',
  role_scope VARCHAR(50) NOT NULL DEFAULT 'school' COMMENT '角色范围：platform/school/class',
  is_system TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否系统内置角色',
  status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (role_id),
  UNIQUE KEY uk_sys_role_code (role_code),
  KEY idx_sys_role_scope (role_scope),
  KEY idx_sys_role_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色定义表';

CREATE TABLE IF NOT EXISTS sys_permission (
  permission_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  permission_code VARCHAR(100) NOT NULL COMMENT '权限编码，如 admin.school.read',
  permission_name VARCHAR(100) NOT NULL COMMENT '权限名称',
  permission_type VARCHAR(30) NOT NULL DEFAULT 'api' COMMENT '权限类型：menu/api/button/data',
  resource_path VARCHAR(255) NULL COMMENT '页面路径或接口路径',
  parent_id BIGINT NULL COMMENT '上级权限ID',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (permission_id),
  UNIQUE KEY uk_sys_permission_code (permission_code),
  KEY idx_sys_permission_parent_id (parent_id),
  KEY idx_sys_permission_type (permission_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限定义表';

CREATE TABLE IF NOT EXISTS sys_role_permission (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  role_id BIGINT NOT NULL COMMENT '角色ID',
  permission_id BIGINT NOT NULL COMMENT '权限ID',
  data_scope VARCHAR(50) NOT NULL DEFAULT 'school' COMMENT '数据范围：all/school/class/self',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_permission (role_id, permission_id),
  KEY idx_role_permission_role_id (role_id),
  KEY idx_role_permission_permission_id (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关系表';

-- =========================================================
-- 二、教师、学生、班级
-- =========================================================

CREATE TABLE IF NOT EXISTS teacher_profile (
  teacher_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '教师ID',
  account_id BIGINT NOT NULL COMMENT '关联 school_user_account',
  school_id BIGINT NOT NULL COMMENT '所属学校',
  teacher_no VARCHAR(50) NULL COMMENT '教师工号',
  teacher_name VARCHAR(100) NOT NULL COMMENT '教师姓名',
  title VARCHAR(100) NULL COMMENT '职称/岗位',
  status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive/left',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (teacher_id),
  UNIQUE KEY uk_teacher_account_id (account_id),
  UNIQUE KEY uk_teacher_school_no (school_id, teacher_no),
  KEY idx_teacher_school_id (school_id),
  KEY idx_teacher_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='教师档案表';

CREATE TABLE IF NOT EXISTS student_profile (
  student_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '学生ID',
  account_id BIGINT NOT NULL COMMENT '关联 school_user_account',
  school_id BIGINT NOT NULL COMMENT '所属学校',
  student_no VARCHAR(50) NULL COMMENT '学号',
  student_name VARCHAR(100) NOT NULL COMMENT '学生姓名',
  grade_name VARCHAR(50) NULL COMMENT '年级',
  enrollment_year INT NULL COMMENT '入学年份',
  status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active/graduated/transferred',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (student_id),
  UNIQUE KEY uk_student_account_id (account_id),
  UNIQUE KEY uk_student_school_no (school_id, student_no),
  KEY idx_student_school_id (school_id),
  KEY idx_student_grade_name (grade_name),
  KEY idx_student_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学生档案表';

CREATE TABLE IF NOT EXISTS class_info (
  class_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '班级ID',
  school_id BIGINT NOT NULL COMMENT '所属学校',
  class_name VARCHAR(100) NOT NULL COMMENT '班级名称',
  grade_name VARCHAR(50) NULL COMMENT '年级',
  class_type VARCHAR(30) NOT NULL DEFAULT 'administrative' COMMENT '班级类型：administrative/teaching',
  invite_code VARCHAR(50) NULL COMMENT '学生加入班级的邀请码',
  status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active/archived',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (class_id),
  UNIQUE KEY uk_class_invite_code (invite_code),
  KEY idx_class_school_id (school_id),
  KEY idx_class_grade_name (grade_name),
  KEY idx_class_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='班级表';

CREATE TABLE IF NOT EXISTS class_member (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  class_id BIGINT NOT NULL COMMENT '班级ID',
  student_id BIGINT NOT NULL COMMENT '学生ID',
  join_source VARCHAR(30) NOT NULL DEFAULT 'manual' COMMENT '加入来源：manual/import/invite',
  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active/removed',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_class_member (class_id, student_id),
  KEY idx_class_member_class_id (class_id),
  KEY idx_class_member_student_id (student_id),
  KEY idx_class_member_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='班级成员表';

CREATE TABLE IF NOT EXISTS class_teacher (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  class_id BIGINT NOT NULL COMMENT '班级ID',
  teacher_id BIGINT NOT NULL COMMENT '教师ID',
  teacher_role VARCHAR(30) NOT NULL DEFAULT 'subject_teacher' COMMENT '教师角色：head_teacher/subject_teacher',
  status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_class_teacher_role (class_id, teacher_id, teacher_role),
  KEY idx_class_teacher_class_id (class_id),
  KEY idx_class_teacher_teacher_id (teacher_id),
  KEY idx_class_teacher_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='班级教师表';

-- =========================================================
-- 三、学校资源计算与 RAG 索引
-- =========================================================

CREATE TABLE IF NOT EXISTS school_resource_calc_run (
  run_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '计算批次ID',
  school_id BIGINT NOT NULL COMMENT '学校ID',
  radius_km DECIMAL(8,2) NULL COMMENT '计算半径',
  calc_method VARCHAR(50) NOT NULL DEFAULT 'distance' COMMENT '计算方式：distance/amap/ai/manual',
  status VARCHAR(20) NOT NULL DEFAULT 'running' COMMENT '状态：running/success/failed',
  candidate_count INT NOT NULL DEFAULT 0 COMMENT '候选资源数',
  linked_count INT NOT NULL DEFAULT 0 COMMENT '形成关联数',
  started_at DATETIME NULL COMMENT '开始时间',
  finished_at DATETIME NULL COMMENT '结束时间',
  error_message TEXT NULL COMMENT '失败原因',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (run_id),
  KEY idx_resource_calc_school_id (school_id),
  KEY idx_resource_calc_status (status),
  KEY idx_resource_calc_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学校周边资源计算批次表';

CREATE TABLE IF NOT EXISTS rag_index_job (
  job_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '索引任务ID',
  job_type VARCHAR(30) NOT NULL COMMENT '任务类型：full/resource/chunk',
  target_entity_type VARCHAR(50) NULL COMMENT '目标实体类型',
  target_entity_id BIGINT NULL COMMENT '目标实体ID',
  status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态：pending/running/success/failed',
  total_chunks INT NOT NULL DEFAULT 0 COMMENT '总分块数',
  indexed_chunks INT NOT NULL DEFAULT 0 COMMENT '成功分块数',
  failed_chunks INT NOT NULL DEFAULT 0 COMMENT '失败分块数',
  started_by BIGINT NULL COMMENT '操作人账号ID',
  started_at DATETIME NULL COMMENT '开始时间',
  finished_at DATETIME NULL COMMENT '结束时间',
  error_message TEXT NULL COMMENT '失败原因',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (job_id),
  KEY idx_rag_index_job_status (status),
  KEY idx_rag_index_job_target (target_entity_type, target_entity_id),
  KEY idx_rag_index_job_started_by (started_by),
  KEY idx_rag_index_job_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG索引任务表';

CREATE TABLE IF NOT EXISTS rag_retrieval_test_log (
  test_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '测试ID',
  query_text TEXT NOT NULL COMMENT '管理员输入的问题',
  scope_type VARCHAR(30) NOT NULL DEFAULT 'GLOBAL' COMMENT '范围类型：SCHOOL/RESOURCE/REGION/GLOBAL',
  scope_id BIGINT NULL COMMENT '范围ID',
  top_k INT NOT NULL DEFAULT 5 COMMENT '召回数量',
  retrieval_status VARCHAR(20) NOT NULL DEFAULT 'success' COMMENT '检索状态：success/empty/failed',
  result_json JSON NULL COMMENT '召回片段、分数、来源、引用编号',
  created_by BIGINT NULL COMMENT '测试人账号ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '测试时间',
  PRIMARY KEY (test_id),
  KEY idx_retrieval_test_scope (scope_type, scope_id),
  KEY idx_retrieval_test_status (retrieval_status),
  KEY idx_retrieval_test_created_by (created_by),
  KEY idx_retrieval_test_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG检索测试日志表';

-- =========================================================
-- 四、智能体调试
-- =========================================================

CREATE TABLE IF NOT EXISTS agent_debug_session (
  debug_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '调试会话ID',
  question TEXT NOT NULL COMMENT '测试问题',
  scope_type VARCHAR(30) NULL COMMENT '调试范围类型',
  scope_id BIGINT NULL COMMENT '调试范围ID',
  model_id VARCHAR(100) NULL COMMENT '模型ID',
  provider VARCHAR(50) NULL COMMENT '模型供应商',
  answer MEDIUMTEXT NULL COMMENT '最终回答',
  status VARCHAR(20) NOT NULL DEFAULT 'success' COMMENT '状态：success/failed/stopped',
  duration_ms INT NULL COMMENT '总耗时',
  created_by BIGINT NULL COMMENT '管理员账号ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (debug_id),
  KEY idx_agent_debug_scope (scope_type, scope_id),
  KEY idx_agent_debug_status (status),
  KEY idx_agent_debug_created_by (created_by),
  KEY idx_agent_debug_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体调试会话表';

CREATE TABLE IF NOT EXISTS agent_debug_event (
  event_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件ID',
  debug_id BIGINT NOT NULL COMMENT '调试会话ID',
  event_name VARCHAR(80) NOT NULL COMMENT '事件名称，如 retrieval.started、tool.completed',
  event_stage VARCHAR(50) NOT NULL COMMENT '事件阶段：retrieval/model/tool/final',
  status VARCHAR(20) NOT NULL DEFAULT 'started' COMMENT '状态：started/success/failed',
  duration_ms INT NULL COMMENT '节点耗时',
  payload_json JSON NULL COMMENT '事件详情、命中资源、工具参数等',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间',
  PRIMARY KEY (event_id),
  KEY idx_agent_debug_event_debug_id (debug_id),
  KEY idx_agent_debug_event_name (event_name),
  KEY idx_agent_debug_event_stage (event_stage),
  KEY idx_agent_debug_event_status (status),
  KEY idx_agent_debug_event_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体调试事件表';

-- =========================================================
-- 五、现有表建议补充字段
-- =========================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
  )
  AND NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND column_name = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS add_index_if_missing $$
CREATE PROCEDURE add_index_if_missing(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_columns TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
  )
  AND NOT EXISTS (
    SELECT 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND index_name = p_index_name
  ) THEN
    SET @ddl = CONCAT('CREATE INDEX `', p_index_name, '` ON `', p_table_name, '` ', p_index_columns);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS rebuild_content_chunk_fulltext_index $$
CREATE PROCEDURE rebuild_content_chunk_fulltext_index()
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'content_chunk'
  )
  AND EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'content_chunk'
      AND column_name = 'retrieval_text'
  ) THEN
    IF EXISTS (
      SELECT 1
      FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'content_chunk'
        AND index_name = 'ft_chunk_text'
    ) THEN
      ALTER TABLE content_chunk DROP INDEX ft_chunk_text;
    END IF;
    CREATE FULLTEXT INDEX ft_chunk_text
      ON content_chunk (chunk_title, chunk_text, retrieval_text)
      WITH PARSER ngram;
  END IF;
END $$

DELIMITER ;

CALL add_column_if_missing('school', 'logo_url', 'VARCHAR(255) NULL COMMENT ''学校Logo地址''');
CALL add_column_if_missing('school', 'website_url', 'VARCHAR(255) NULL COMMENT ''学校官网地址''');
CALL add_column_if_missing('school', 'contact_email', 'VARCHAR(100) NULL COMMENT ''联系邮箱''');
CALL add_column_if_missing('school', 'admin_contact_name', 'VARCHAR(100) NULL COMMENT ''管理员联系人姓名''');
CALL add_column_if_missing('school', 'default_discovery_radius_km', 'DECIMAL(8,2) NULL COMMENT ''默认周边资源发现半径''');
CALL add_column_if_missing('school', 'resource_calc_enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''是否启用周边资源自动计算''');
CALL add_column_if_missing('school', 'last_resource_calc_at', 'DATETIME NULL COMMENT ''最后一次资源计算时间''');

CALL add_column_if_missing('school_user_account', 'real_name', 'VARCHAR(100) NULL COMMENT ''真实姓名''');
CALL add_column_if_missing('school_user_account', 'email', 'VARCHAR(100) NULL COMMENT ''邮箱''');
CALL add_column_if_missing('school_user_account', 'avatar_url', 'VARCHAR(255) NULL COMMENT ''头像地址''');
CALL add_column_if_missing('school_user_account', 'account_type', 'VARCHAR(30) NULL COMMENT ''账号类型：admin/teacher/student''');
CALL add_column_if_missing('school_user_account', 'employee_no', 'VARCHAR(50) NULL COMMENT ''员工号/教师工号''');
CALL add_column_if_missing('school_user_account', 'student_no', 'VARCHAR(50) NULL COMMENT ''学生学号''');
CALL add_column_if_missing('school_user_account', 'force_password_change', 'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否强制修改密码''');
CALL add_column_if_missing('school_user_account', 'password_updated_at', 'DATETIME NULL COMMENT ''密码更新时间''');
CALL add_column_if_missing('school_user_account', 'last_login_ip', 'VARCHAR(45) NULL COMMENT ''最后登录IP''');

CALL add_column_if_missing('school_resource_rel', 'calc_run_id', 'BIGINT NULL COMMENT ''计算批次ID''');
CALL add_column_if_missing('school_resource_rel', 'calc_method', 'VARCHAR(50) NULL COMMENT ''计算方式：distance/amap/ai/manual''');
CALL add_column_if_missing('school_resource_rel', 'match_score', 'DECIMAL(10,4) NULL COMMENT ''匹配分数''');
CALL add_column_if_missing('school_resource_rel', 'route_distance_meters', 'INT NULL COMMENT ''路线距离，单位米''');
CALL add_column_if_missing('school_resource_rel', 'route_duration_minutes', 'INT NULL COMMENT ''路线耗时，单位分钟''');
CALL add_column_if_missing('school_resource_rel', 'calculated_at', 'DATETIME NULL COMMENT ''计算时间''');
CALL add_column_if_missing('school_resource_rel', 'manual_locked', 'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否人工锁定，防止自动刷新覆盖''');
CALL add_column_if_missing('school_resource_rel', 'last_verified_at', 'DATETIME NULL COMMENT ''最后核验时间''');

CALL add_column_if_missing('content_chunk', 'content_hash', 'VARCHAR(64) NULL COMMENT ''内容哈希''');
CALL add_column_if_missing('content_chunk', 'retrieval_text', 'LONGTEXT NULL COMMENT ''检索元数据文本'' AFTER `chunk_text`');
CALL add_column_if_missing('content_chunk', 'embedding_hash', 'CHAR(64) NULL COMMENT ''向量索引哈希'' AFTER `embedding_status`');
CALL add_column_if_missing('content_chunk', 'embedding_model', 'VARCHAR(100) NULL COMMENT ''向量模型''');
CALL add_column_if_missing('content_chunk', 'embedding_dimensions', 'INT NULL COMMENT ''向量维度''');
CALL add_column_if_missing('content_chunk', 'embedding_index_version', 'VARCHAR(32) NULL COMMENT ''向量索引版本'' AFTER `embedding_dimensions`');
CALL add_column_if_missing('content_chunk', 'qdrant_collection', 'VARCHAR(100) NULL COMMENT ''Qdrant集合名''');
CALL add_column_if_missing('content_chunk', 'vector_point_id', 'VARCHAR(100) NULL COMMENT ''向量库点位ID''');
CALL add_column_if_missing('content_chunk', 'embedded_at', 'DATETIME NULL COMMENT ''向量化完成时间''');
CALL add_column_if_missing('content_chunk', 'embedding_error', 'TEXT NULL COMMENT ''向量化失败原因''');
CALL rebuild_content_chunk_fulltext_index();

CALL add_column_if_missing('teaching_activity_plan', 'owner_account_id', 'BIGINT NULL COMMENT ''方案所属账号ID''');
CALL add_column_if_missing('teaching_activity_plan', 'class_id', 'BIGINT NULL COMMENT ''发布班级ID''');
CALL add_column_if_missing('teaching_activity_plan', 'generation_source', 'VARCHAR(30) NULL COMMENT ''生成来源：manual/ai/import''');
CALL add_column_if_missing('teaching_activity_plan', 'ai_run_id', 'BIGINT NULL COMMENT ''AI生成任务ID''');
CALL add_column_if_missing('teaching_activity_plan', 'published_status', 'VARCHAR(20) NOT NULL DEFAULT ''draft'' COMMENT ''发布状态：draft/published/archived''');
CALL add_column_if_missing('teaching_activity_plan', 'published_at', 'DATETIME NULL COMMENT ''发布时间''');

-- =========================================================
-- 六、建议索引
-- =========================================================

CALL add_index_if_missing('school_resource_rel', 'idx_school_resource_rel_calc_run_id', '(calc_run_id)');
CALL add_index_if_missing('school_resource_rel', 'idx_school_resource_rel_manual_locked', '(manual_locked)');
CALL add_index_if_missing('content_chunk', 'idx_content_chunk_content_hash', '(content_hash)');
CALL add_index_if_missing('content_chunk', 'idx_content_chunk_vector_point_id', '(vector_point_id)');
CALL add_index_if_missing('teaching_activity_plan', 'idx_teaching_activity_plan_owner', '(owner_account_id)');
CALL add_index_if_missing('teaching_activity_plan', 'idx_teaching_activity_plan_class', '(class_id)');
CALL add_index_if_missing('teaching_activity_plan', 'idx_teaching_activity_plan_publish', '(published_status)');

-- =========================================================
-- 七、基础角色初始化数据
-- =========================================================

INSERT INTO sys_role (role_code, role_name, role_scope, is_system, status)
VALUES
  ('platform_admin', '平台管理员', 'platform', 1, 'active'),
  ('school_admin', '学校管理员', 'school', 1, 'active'),
  ('teacher', '教师', 'school', 1, 'active'),
  ('student', '学生', 'school', 1, 'active')
ON DUPLICATE KEY UPDATE
  role_name = VALUES(role_name),
  role_scope = VALUES(role_scope),
  is_system = VALUES(is_system),
  status = VALUES(status),
  updated_at = CURRENT_TIMESTAMP;

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
DROP PROCEDURE IF EXISTS rebuild_content_chunk_fulltext_index;

SET FOREIGN_KEY_CHECKS = 1;
