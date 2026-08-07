-- 管理员资源与图谱目录模块迁移。应用不会自动执行本脚本。

CREATE TABLE IF NOT EXISTS catalog_import_batch (
  batch_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_name VARCHAR(255) NOT NULL,
  created_by BIGINT NULL,
  status VARCHAR(32) NOT NULL,
  total_rows INT NOT NULL DEFAULT 0,
  valid_rows INT NOT NULL DEFAULT 0,
  invalid_rows INT NOT NULL DEFAULT 0,
  duplicate_rows INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_catalog_import_batch_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS catalog_import_row (
  row_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  sheet_name VARCHAR(64) NOT NULL,
  row_no INT NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  payload_json LONGTEXT NOT NULL,
  validation_status VARCHAR(32) NOT NULL,
  validation_message VARCHAR(500) NULL,
  imported_entity_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_catalog_import_row_batch FOREIGN KEY (batch_id) REFERENCES catalog_import_batch(batch_id),
  KEY idx_catalog_import_row_batch_status (batch_id, validation_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS catalog_projection_task (
  task_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type VARCHAR(64) NOT NULL,
  entity_id BIGINT NOT NULL,
  task_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_catalog_projection_task_status (status, updated_at),
  KEY idx_catalog_projection_task_entity (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
