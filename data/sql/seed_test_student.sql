-- 用户端测试学生账号
-- 前置条件：已执行基础数据库、用户管理模块、学校和班级数据脚本。
-- 登录账号：test_student
-- 登录密码：123456

SET NAMES utf8mb4;

SET @test_student_password = '$2b$10$blqD0CahbIA0E08KsiXy0OI8EoFYCFEZuo9CheBrykIqglWSiSoD6';

-- 绑定第一所学校。没有学校时不会创建账号。
INSERT INTO school_user_account (
  username, password_hash, role_code, school_id, display_name, contact_name,
  real_name, account_type, force_password_change, password_updated_at, status
)
SELECT
  'test_student', @test_student_password, 'student', s.school_id,
  '测试学生', '测试学生', '测试学生', 'student', 0, CURRENT_TIMESTAMP, 'active'
FROM school s
WHERE s.school_id = (SELECT MIN(school_id) FROM school)
  AND NOT EXISTS (
    SELECT 1 FROM school_user_account a WHERE a.username = 'test_student'
  );

-- 重复执行时恢复为可登录的测试账号。
UPDATE school_user_account
SET password_hash = @test_student_password,
    role_code = 'student',
    account_type = 'student',
    display_name = '测试学生',
    real_name = '测试学生',
    status = 'active',
    force_password_change = 0,
    password_updated_at = CURRENT_TIMESTAMP
WHERE username = 'test_student';

INSERT INTO user_profile (
  account_id, profile_type, real_name, school_id, status, remark
)
SELECT a.account_id, 'student', '测试学生', a.school_id, 'active', '用户端学生测试账号'
FROM school_user_account a
WHERE a.username = 'test_student'
  AND NOT EXISTS (
    SELECT 1 FROM user_profile p WHERE p.account_id = a.account_id
  );

UPDATE user_profile p
JOIN school_user_account a ON a.account_id = p.account_id
SET p.profile_type = 'student', p.real_name = '测试学生', p.school_id = a.school_id,
    p.status = 'active', p.remark = '用户端学生测试账号'
WHERE a.username = 'test_student';

INSERT INTO student_profile (
  account_id, profile_id, school_id, student_no, student_name, grade_name,
  enrollment_year, status
)
SELECT
  a.account_id,
  p.profile_id,
  a.school_id,
  CONCAT('TESTSTU', a.school_id),
  '测试学生',
  '小学四年级',
  2026,
  'active'
FROM school_user_account a
JOIN user_profile p ON p.account_id = a.account_id AND p.profile_type = 'student'
WHERE a.username = 'test_student'
  AND NOT EXISTS (
    SELECT 1 FROM student_profile sp WHERE sp.account_id = a.account_id
  );

UPDATE student_profile sp
JOIN school_user_account a ON a.account_id = sp.account_id
SET sp.school_id = a.school_id, sp.student_no = CONCAT('TESTSTU', a.school_id),
    sp.student_name = '测试学生', sp.grade_name = '小学四年级',
    sp.enrollment_year = 2026, sp.status = 'active'
WHERE a.username = 'test_student';

-- 学生角色关联。
INSERT IGNORE INTO sys_account_role (account_id, role_id, data_scope)
SELECT a.account_id, r.role_id, 'school'
FROM school_user_account a
JOIN sys_role r ON r.role_code = 'student'
WHERE a.username = 'test_student';

-- 自动加入该校第一个有效班级，便于测试班级和任务页面。
INSERT IGNORE INTO class_member (class_id, student_id, join_source, is_primary, status)
SELECT c.class_id, sp.student_id, 'import', 1, 'active'
FROM student_profile sp
JOIN school_user_account a ON a.account_id = sp.account_id
JOIN class_info c ON c.school_id = sp.school_id AND c.status = 'active'
WHERE a.username = 'test_student'
  AND c.class_id = (SELECT MIN(c2.class_id) FROM class_info c2 WHERE c2.school_id = sp.school_id AND c2.status = 'active');

-- 为新学生补发所在班级已发布的任务。
INSERT IGNORE INTO student_task_progress (task_id, student_id, status)
SELECT t.task_id, sp.student_id, 'pending'
FROM student_profile sp
JOIN school_user_account a ON a.account_id = sp.account_id
JOIN class_member cm ON cm.student_id = sp.student_id AND cm.status = 'active'
JOIN class_learning_task t ON t.class_id = cm.class_id AND t.status = 'published'
WHERE a.username = 'test_student';
