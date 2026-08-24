CREATE TABLE IF NOT EXISTS student_resource_browse_history (
  browse_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  viewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  view_count INT NOT NULL DEFAULT 1,
  UNIQUE KEY uk_student_resource (student_id, resource_id),
  KEY idx_student_browse_time (student_id, viewed_at),
  CONSTRAINT fk_student_browse_student FOREIGN KEY (student_id) REFERENCES student_profile(student_id),
  CONSTRAINT fk_student_browse_resource FOREIGN KEY (resource_id) REFERENCES local_edu_resource(resource_id)
);
