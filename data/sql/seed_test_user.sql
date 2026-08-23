-- 用户端测试教师账号
-- 前置条件：已执行基础数据库、用户管理模块和学校数据脚本。
-- 登录账号：test_teacher
-- 登录密码：123456

SET NAMES utf8mb4;

-- 绑定第一所学校，避免硬编码学校 ID；没有学校时不会插入账号。
INSERT INTO school_user_account (
  username, password_hash, role_code, school_id, display_name, contact_name,
  real_name, account_type, force_password_change, password_updated_at, status
)
SELECT
  'test_teacher',
  '$2b$10$blqD0CahbIA0E08KsiXy0OI8EoFYCFEZuo9CheBrykIqglWSiSoD6',
  'teacher',
  s.school_id,
  '测试教师',
  '测试教师',
  '测试教师',
  'teacher',
  0,
  CURRENT_TIMESTAMP,
  'active'
FROM school s
WHERE s.school_id = (SELECT MIN(school_id) FROM school)
  AND NOT EXISTS (
    SELECT 1 FROM school_user_account a WHERE a.username = 'test_teacher'
  );

-- 如果账号已经存在，重新执行脚本也会把密码恢复为 123456。
UPDATE school_user_account
SET password_hash = '$2b$10$blqD0CahbIA0E08KsiXy0OI8EoFYCFEZuo9CheBrykIqglWSiSoD6',
    role_code = 'teacher',
    account_type = 'teacher',
    status = 'active',
    force_password_change = 0,
    password_updated_at = CURRENT_TIMESTAMP
WHERE username = 'test_teacher';

INSERT INTO user_profile (
  account_id, profile_type, real_name, school_id, status, remark
)
SELECT a.account_id, 'teacher', '测试教师', a.school_id, 'active', '用户端测试账号'
FROM school_user_account a
WHERE a.username = 'test_teacher'
  AND NOT EXISTS (
    SELECT 1 FROM user_profile p WHERE p.account_id = a.account_id
  );

INSERT INTO teacher_profile (
  account_id, profile_id, school_id, teacher_no, teacher_name, title, status
)
SELECT
  a.account_id,
  p.profile_id,
  a.school_id,
  CONCAT('TEST', a.school_id),
  '测试教师',
  '思政教师',
  'active'
FROM school_user_account a
JOIN user_profile p ON p.account_id = a.account_id AND p.profile_type = 'teacher'
WHERE a.username = 'test_teacher'
  AND NOT EXISTS (
    SELECT 1 FROM teacher_profile t WHERE t.account_id = a.account_id
  );

INSERT IGNORE INTO sys_account_role (account_id, role_id, data_scope)
SELECT a.account_id, r.role_id, 'school'
FROM school_user_account a
JOIN sys_role r ON r.role_code = 'teacher'
WHERE a.username = 'test_teacher';
