-- 教学方案反馈原因标签兼容迁移，可重复执行。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS add_feedback_reason_column_if_missing $$
CREATE PROCEDURE add_feedback_reason_column_if_missing()
BEGIN
  IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'teaching_plan_feedback')
     AND NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'teaching_plan_feedback' AND COLUMN_NAME = 'reason_codes_json') THEN
    ALTER TABLE teaching_plan_feedback ADD COLUMN reason_codes_json JSON NULL COMMENT '负面反馈原因编码 JSON 数组' AFTER rating;
  END IF;
END $$
DELIMITER ;

CALL add_feedback_reason_column_if_missing();
DROP PROCEDURE IF EXISTS add_feedback_reason_column_if_missing;
