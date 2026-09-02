CREATE TABLE IF NOT EXISTS student_resource_browse_history (
  browse_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  viewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  view_count INT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_student_resource (student_id, resource_id),
  KEY idx_student_browse_time (student_id, viewed_at),
  CONSTRAINT fk_student_browse_student FOREIGN KEY (student_id) REFERENCES student_profile(student_id),
  CONSTRAINT fk_student_browse_resource FOREIGN KEY (resource_id) REFERENCES local_edu_resource(resource_id)
);

-- 兼容已创建的历史表：实体继承 BaseAuditEntity，必须保留审计字段。
DELIMITER //
CREATE PROCEDURE add_browse_history_column_if_missing(
  IN column_name_param VARCHAR(64),
  IN column_definition_param TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'student_resource_browse_history'
      AND column_name = column_name_param
  ) THEN
    SET @statement = CONCAT(
      'ALTER TABLE `student_resource_browse_history` ADD COLUMN `',
      column_name_param, '` ', column_definition_param
    );
    PREPARE stmt FROM @statement;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END //
DELIMITER ;

CALL add_browse_history_column_if_missing(
  'created_at', 'DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP'
);
CALL add_browse_history_column_if_missing(
  'updated_at', 'DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP'
);
DROP PROCEDURE add_browse_history_column_if_missing;
