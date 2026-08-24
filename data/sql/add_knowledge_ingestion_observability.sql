-- 知识库导入观测字段兼容迁移，可重复执行。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS add_ingest_observability_column_if_missing $$
CREATE PROCEDURE add_ingest_observability_column_if_missing(
  IN column_name_param VARCHAR(64), IN column_definition_param TEXT
)
BEGIN
  IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_ingest_job')
     AND NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_ingest_job' AND COLUMN_NAME = column_name_param) THEN
    SET @ddl = CONCAT('ALTER TABLE knowledge_ingest_job ADD COLUMN `', column_name_param, '` ', column_definition_param);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END $$
DELIMITER ;

CALL add_ingest_observability_column_if_missing('metadata_json', 'JSON NULL AFTER error_summary');
CALL add_ingest_observability_column_if_missing('restart_from', 'VARCHAR(40) NULL AFTER metadata_json');
DROP PROCEDURE IF EXISTS add_ingest_observability_column_if_missing;
