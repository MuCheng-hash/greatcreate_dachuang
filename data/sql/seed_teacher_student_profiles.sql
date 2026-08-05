-- 教师与学生档案示例数据脚本
-- 执行前请先执行 add_user_management_module.sql
-- 默认密码均为：123456

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DELIMITER $$

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

-- 用户管理需要同一学校存在多个教师/学生账号。
CALL normalize_school_account_school_index();

-- 1. 确保基础角色存在
INSERT INTO sys_role (role_code, role_name, role_scope, is_system, status)
VALUES
  ('teacher', '教师', 'school', 1, 'active'),
  ('student', '学生', 'school', 1, 'active')
ON DUPLICATE KEY UPDATE
  role_name = VALUES(role_name),
  role_scope = VALUES(role_scope),
  status = VALUES(status),
  updated_at = CURRENT_TIMESTAMP;

-- 2. 为每所学校生成 3 个示例班级
INSERT IGNORE INTO class_info (school_id, class_name, grade_name, class_type, invite_code, status)
SELECT s.school_id, c.class_name, c.grade_name, 'administrative',
       CONCAT('S', s.school_id, 'C', c.class_no),
       'active'
FROM school s
JOIN (
  SELECT 1 AS class_no, '五年级一班' AS class_name, '五年级' AS grade_name
  UNION ALL SELECT 2, '六年级一班', '六年级'
  UNION ALL SELECT 3, '七年级一班', '七年级'
) c
WHERE s.school_id IS NOT NULL;

-- 3. 为每所学校生成 3 名教师账号
INSERT IGNORE INTO school_user_account (
  username, password_hash, role_code, school_id, display_name, contact_name,
  contact_phone, real_name, account_type, force_password_change, password_updated_at, status
)
SELECT
  CONCAT('teacher_', s.school_id, '_', t.no),
  '$2a$10$Dow1Ykw1Dx3BF2hLfFtUSuCE7t/iOnCfjaJrKf2s72Q9BmcYorLd2',
  'teacher',
  s.school_id,
  t.teacher_name,
  t.teacher_name,
  CONCAT('1380001', LPAD(s.school_id % 100, 2, '0'), LPAD(t.no, 2, '0')),
  t.teacher_name,
  'teacher',
  1,
  CURRENT_TIMESTAMP,
  'active'
FROM school s
JOIN (
  SELECT 1 AS no, '李红梅' AS teacher_name, '思政教师' AS title
  UNION ALL SELECT 2, '王建国', '班主任'
  UNION ALL SELECT 3, '赵晓丽', '实践活动教师'
) t;

-- 4. 写入教师统一档案
INSERT IGNORE INTO user_profile (
  account_id, profile_type, real_name, phone, school_id, status, remark
)
SELECT
  a.account_id,
  'teacher',
  a.real_name,
  a.contact_phone,
  a.school_id,
  'active',
  '系统生成的教师示例档案'
FROM school_user_account a
WHERE a.username LIKE 'teacher\_%\_%'
  AND a.account_type = 'teacher';

-- 5. 写入教师扩展档案
INSERT IGNORE INTO teacher_profile (
  account_id, profile_id, school_id, teacher_no, teacher_name, title, status
)
SELECT
  a.account_id,
  p.profile_id,
  a.school_id,
  CONCAT('T', a.school_id, LPAD(SUBSTRING_INDEX(a.username, '_', -1), 3, '0')),
  p.real_name,
  CASE SUBSTRING_INDEX(a.username, '_', -1)
    WHEN '1' THEN '思政教师'
    WHEN '2' THEN '班主任'
    ELSE '实践活动教师'
  END,
  'active'
FROM school_user_account a
JOIN user_profile p ON p.account_id = a.account_id
WHERE a.username LIKE 'teacher\_%\_%'
  AND p.profile_type = 'teacher';

-- 6. 将教师关联到本校示例班级
INSERT IGNORE INTO class_teacher (class_id, teacher_id, teacher_role, status)
SELECT
  c.class_id,
  tp.teacher_id,
  CASE
    WHEN tp.title = '班主任' THEN 'head_teacher'
    ELSE 'subject_teacher'
  END,
  'active'
FROM teacher_profile tp
JOIN class_info c ON c.school_id = tp.school_id
WHERE tp.teacher_no LIKE CONCAT('T', tp.school_id, '%')
  AND c.invite_code LIKE CONCAT('S', tp.school_id, 'C%');

-- 7. 为每所学校生成 12 名学生账号
INSERT IGNORE INTO school_user_account (
  username, password_hash, role_code, school_id, display_name, contact_name,
  contact_phone, real_name, account_type, force_password_change, password_updated_at, status
)
SELECT
  CONCAT('student_', s.school_id, '_', LPAD(n.no, 2, '0')),
  '$2a$10$Dow1Ykw1Dx3BF2hLfFtUSuCE7t/iOnCfjaJrKf2s72Q9BmcYorLd2',
  'student',
  s.school_id,
  names.student_name,
  names.student_name,
  CONCAT('1390001', LPAD(s.school_id % 100, 2, '0'), LPAD(n.no, 2, '0')),
  names.student_name,
  'student',
  1,
  CURRENT_TIMESTAMP,
  'active'
FROM school s
JOIN (
  SELECT 1 AS no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
  UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
) n
JOIN (
  SELECT 1 AS no, '张明' AS student_name UNION ALL SELECT 2, '李欣怡'
  UNION ALL SELECT 3, '王浩然' UNION ALL SELECT 4, '赵雨桐'
  UNION ALL SELECT 5, '刘子涵' UNION ALL SELECT 6, '陈思远'
  UNION ALL SELECT 7, '杨佳宁' UNION ALL SELECT 8, '周晨曦'
  UNION ALL SELECT 9, '吴一诺' UNION ALL SELECT 10, '孙嘉乐'
  UNION ALL SELECT 11, '马若溪' UNION ALL SELECT 12, '郭宇航'
) names ON names.no = n.no;

-- 8. 写入学生统一档案
INSERT IGNORE INTO user_profile (
  account_id, profile_type, real_name, phone, school_id, status, remark
)
SELECT
  a.account_id,
  'student',
  a.real_name,
  a.contact_phone,
  a.school_id,
  'active',
  '系统生成的学生示例档案'
FROM school_user_account a
WHERE a.username LIKE 'student\_%\_%'
  AND a.account_type = 'student';

-- 9. 写入学生扩展档案
INSERT IGNORE INTO student_profile (
  account_id, profile_id, school_id, student_no, student_name, grade_name, enrollment_year, status
)
SELECT
  a.account_id,
  p.profile_id,
  a.school_id,
  CONCAT('S', a.school_id, '2026', LPAD(SUBSTRING_INDEX(a.username, '_', -1), 3, '0')),
  p.real_name,
  CASE
    WHEN CAST(SUBSTRING_INDEX(a.username, '_', -1) AS UNSIGNED) BETWEEN 1 AND 4 THEN '五年级'
    WHEN CAST(SUBSTRING_INDEX(a.username, '_', -1) AS UNSIGNED) BETWEEN 5 AND 8 THEN '六年级'
    ELSE '七年级'
  END,
  2026,
  'active'
FROM school_user_account a
JOIN user_profile p ON p.account_id = a.account_id
WHERE a.username LIKE 'student\_%\_%'
  AND p.profile_type = 'student';

-- 10. 将学生按序分配到本校 3 个示例班级
INSERT IGNORE INTO class_member (class_id, student_id, join_source, is_primary, status)
SELECT
  c.class_id,
  sp.student_id,
  'import',
  1,
  'active'
FROM student_profile sp
JOIN school_user_account a ON a.account_id = sp.account_id
JOIN class_info c
  ON c.school_id = sp.school_id
 AND c.invite_code = CONCAT(
      'S', sp.school_id, 'C',
      CASE
        WHEN CAST(SUBSTRING_INDEX(a.username, '_', -1) AS UNSIGNED) BETWEEN 1 AND 4 THEN 1
        WHEN CAST(SUBSTRING_INDEX(a.username, '_', -1) AS UNSIGNED) BETWEEN 5 AND 8 THEN 2
        ELSE 3
      END
    )
WHERE a.username LIKE 'student\_%\_%';

-- 11. 账号角色关联
INSERT IGNORE INTO sys_account_role (account_id, role_id, data_scope)
SELECT a.account_id, r.role_id, 'school'
FROM school_user_account a
JOIN sys_role r ON r.role_code COLLATE utf8mb4_0900_ai_ci = a.role_code COLLATE utf8mb4_0900_ai_ci
WHERE a.username LIKE 'teacher\_%\_%'
   OR a.username LIKE 'student\_%\_%';

SET FOREIGN_KEY_CHECKS = 1;

DROP PROCEDURE IF EXISTS drop_index_if_exists;
DROP PROCEDURE IF EXISTS normalize_school_account_school_index;
