-- Teacher-facing class management and self-completed learning tasks.
CREATE TABLE IF NOT EXISTS class_learning_task (
  task_id BIGINT NOT NULL AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  publisher_teacher_id BIGINT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NULL,
  published_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  due_at DATETIME NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'published',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (task_id),
  KEY idx_class_learning_task_class (class_id),
  KEY idx_class_learning_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS student_task_progress (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_student_task_progress (task_id, student_id),
  KEY idx_student_task_progress_student (student_id),
  KEY idx_student_task_progress_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER //
CREATE PROCEDURE add_task_column_if_missing(IN table_name_param VARCHAR(64), IN column_name_param VARCHAR(64), IN column_definition TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = table_name_param AND column_name = column_name_param) THEN
    SET @statement = CONCAT('ALTER TABLE `', table_name_param, '` ADD COLUMN `', column_name_param, '` ', column_definition);
    PREPARE stmt FROM @statement; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END //
DELIMITER ;
CALL add_task_column_if_missing('class_learning_task', 'task_type', "VARCHAR(40) NOT NULL DEFAULT 'red_culture_learning'");
CALL add_task_column_if_missing('class_learning_task', 'submission_rule', "VARCHAR(40) NOT NULL DEFAULT 'text_required'");
CALL add_task_column_if_missing('class_learning_task', 'allow_late_submission', 'TINYINT(1) NOT NULL DEFAULT 1');
DROP PROCEDURE add_task_column_if_missing;

CREATE TABLE IF NOT EXISTS task_resource_rel (
  id BIGINT NOT NULL AUTO_INCREMENT, task_id BIGINT NOT NULL, resource_id BIGINT NOT NULL, sort_order INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_task_resource (task_id, resource_id), KEY idx_task_resource_resource (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS student_task_submission (
  submission_id BIGINT NOT NULL AUTO_INCREMENT, task_id BIGINT NOT NULL, student_id BIGINT NOT NULL, version_no INT NOT NULL,
  content TEXT NULL, selected_resource_ids TEXT NULL, submitted_at DATETIME NULL, is_late TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'draft', is_current TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (submission_id), UNIQUE KEY uk_submission_version (task_id, student_id, version_no),
  KEY idx_submission_task_student (task_id, student_id), KEY idx_submission_current (task_id, student_id, is_current)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS student_task_attachment (
  attachment_id BIGINT NOT NULL AUTO_INCREMENT, submission_id BIGINT NOT NULL, original_filename VARCHAR(255) NOT NULL,
  storage_key VARCHAR(255) NOT NULL, content_type VARCHAR(100) NOT NULL, file_size BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (attachment_id), UNIQUE KEY uk_submission_storage_key (storage_key), KEY idx_submission_attachment (submission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS student_task_review (
  review_id BIGINT NOT NULL AUTO_INCREMENT, submission_id BIGINT NOT NULL, teacher_id BIGINT NOT NULL, review_action VARCHAR(20) NOT NULL,
  comment TEXT NULL, grade VARCHAR(30) NULL, reviewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (review_id), KEY idx_task_review_submission (submission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS teacher_resource_favorite (
  favorite_id BIGINT NOT NULL AUTO_INCREMENT, teacher_id BIGINT NOT NULL, resource_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (favorite_id), UNIQUE KEY uk_teacher_resource_favorite (teacher_id, resource_id), KEY idx_teacher_resource_favorite_resource (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
