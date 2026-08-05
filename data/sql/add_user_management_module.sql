-- 用户管理模块增量脚本
-- 执行位置：red_culture_platform 数据库

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
  IN table_name_param VARCHAR(64),
  IN column_name_param VARCHAR(64),
  IN column_definition_param TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = table_name_param
      AND COLUMN_NAME = column_name_param
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', table_name_param, '` ADD COLUMN `', column_name_param, '` ', column_definition_param);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS add_index_if_missing $$
CREATE PROCEDURE add_index_if_missing(
  IN table_name_param VARCHAR(64),
  IN index_name_param VARCHAR(64),
  IN index_definition_param TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = table_name_param
      AND INDEX_NAME = index_name_param
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', table_name_param, '` ADD INDEX `', index_name_param, '` ', index_definition_param);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS drop_index_if_exists $$
CREATE PROCEDURE drop_index_if_exists(
  IN table_name_param VARCHAR(64),
  IN index_name_param VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = table_name_param
      AND INDEX_NAME = index_name_param
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', table_name_param, '` DROP INDEX `', index_name_param, '`');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS normalize_school_account_school_index $$
CREATE PROCEDURE normalize_school_account_school_index()
BEGIN
  IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'school_user_account'
      AND CONSTRAINT_NAME = 'fk_school_user_account_school'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
  ) THEN
    ALTER TABLE school_user_account DROP FOREIGN KEY fk_school_user_account_school;
  END IF;

  IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'school_user_account'
      AND INDEX_NAME = 'uk_school_user_account_school'
  ) THEN
    ALTER TABLE school_user_account DROP INDEX uk_school_user_account_school;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'school_user_account'
      AND INDEX_NAME = 'idx_school_user_account_school_id'
  ) THEN
    ALTER TABLE school_user_account ADD INDEX idx_school_user_account_school_id (school_id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'school_user_account'
      AND CONSTRAINT_NAME = 'fk_school_user_account_school'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
  ) THEN
    ALTER TABLE school_user_account
      ADD CONSTRAINT fk_school_user_account_school
      FOREIGN KEY (school_id) REFERENCES school(school_id);
  END IF;
END $$

DELIMITER ;

-- 用户管理允许同一学校下存在多个账号，移除旧版“一校一账号”的唯一索引。
CALL normalize_school_account_school_index();

CREATE TABLE IF NOT EXISTS user_profile (
  profile_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '统一档案ID',
  account_id BIGINT NOT NULL COMMENT '关联账号ID',
  profile_type VARCHAR(30) NOT NULL COMMENT '档案类型：admin/teacher/student/other',
  real_name VARCHAR(100) NOT NULL COMMENT '真实姓名',
  gender VARCHAR(20) NULL COMMENT '性别',
  phone VARCHAR(50) NULL COMMENT '联系电话',
  email VARCHAR(100) NULL COMMENT '邮箱',
  school_id BIGINT NULL COMMENT '所属学校ID',
  status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive/left/graduated/transferred',
  remark VARCHAR(255) NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (profile_id),
  UNIQUE KEY uk_user_profile_account_id (account_id),
  KEY idx_user_profile_type (profile_type),
  KEY idx_user_profile_school_id (school_id),
  KEY idx_user_profile_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一用户档案表';

CREATE TABLE IF NOT EXISTS sys_account_role (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  account_id BIGINT NOT NULL COMMENT '账号ID',
  role_id BIGINT NOT NULL COMMENT '角色ID',
  data_scope VARCHAR(50) NOT NULL DEFAULT 'school' COMMENT '数据范围：all/school/class/self',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_account_role (account_id, role_id),
  KEY idx_account_role_account_id (account_id),
  KEY idx_account_role_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号角色关系表';

ALTER TABLE user_profile CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE sys_account_role CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CALL add_column_if_missing('teacher_profile', 'profile_id', 'BIGINT NULL COMMENT ''统一档案ID'' AFTER `account_id`');
CALL add_column_if_missing('student_profile', 'profile_id', 'BIGINT NULL COMMENT ''统一档案ID'' AFTER `account_id`');
CALL add_column_if_missing('class_member', 'is_primary', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''是否主班级''');

CALL add_index_if_missing('teacher_profile', 'idx_teacher_profile_id', '(profile_id)');
CALL add_index_if_missing('student_profile', 'idx_student_profile_id', '(profile_id)');
CALL add_index_if_missing('class_member', 'idx_class_member_primary', '(is_primary)');

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

INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, sort_order)
VALUES
  ('admin.account.manage', '账号管理', 'menu', '/admin#user-accounts', 10),
  ('admin.profile.manage', '档案管理', 'menu', '/admin#user-profiles', 20),
  ('admin.role.manage', '角色权限管理', 'menu', '/admin#roles', 30),
  ('admin.account.read', '查看账号', 'api', '/api/admin/user-accounts', 101),
  ('admin.account.write', '维护账号', 'api', '/api/admin/user-accounts', 102),
  ('admin.profile.read', '查看档案', 'api', '/api/admin/user-profiles', 201),
  ('admin.profile.write', '维护档案', 'api', '/api/admin/user-profiles', 202),
  ('admin.role.read', '查看角色权限', 'api', '/api/admin/roles', 301),
  ('admin.role.write', '维护角色权限', 'api', '/api/admin/roles', 302)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  permission_type = VALUES(permission_type),
  resource_path = VALUES(resource_path),
  sort_order = VALUES(sort_order),
  updated_at = CURRENT_TIMESTAMP;

INSERT IGNORE INTO sys_account_role (account_id, role_id, data_scope)
SELECT account_id, role_id,
       CASE WHEN r.role_code = 'platform_admin' THEN 'all' ELSE 'school' END
FROM school_user_account a
JOIN sys_role r ON r.role_code COLLATE utf8mb4_0900_ai_ci = a.role_code COLLATE utf8mb4_0900_ai_ci
WHERE a.role_code IS NOT NULL;

INSERT IGNORE INTO sys_role_permission (role_id, permission_id, data_scope)
SELECT r.role_id, p.permission_id,
       CASE WHEN r.role_code = 'platform_admin' THEN 'all' ELSE 'school' END
FROM sys_role r
JOIN sys_permission p ON p.permission_code COLLATE utf8mb4_0900_ai_ci LIKE 'admin.%'
WHERE r.role_code = 'platform_admin';

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
DROP PROCEDURE IF EXISTS drop_index_if_exists;
DROP PROCEDURE IF EXISTS normalize_school_account_school_index;

SET FOREIGN_KEY_CHECKS = 1;
