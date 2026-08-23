-- Persist every completed AI teaching-plan result and the generating teacher's feedback.
-- Run after the school module schema and before deploying the feedback-enabled application.

CREATE TABLE IF NOT EXISTS ai_teaching_plan_generation (
  generation_id BIGINT NOT NULL AUTO_INCREMENT,
  school_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  actor_role VARCHAR(50) NOT NULL,
  thread_id VARCHAR(128) NULL,
  grade VARCHAR(100) NULL,
  theme VARCHAR(200) NOT NULL,
  activity_type VARCHAR(40) NULL,
  duration_minutes INT NULL,
  practice_required TINYINT(1) NULL,
  generation_status VARCHAR(24) NOT NULL,
  retrieval_status VARCHAR(24) NULL,
  llm_provider VARCHAR(100) NULL,
  llm_model VARCHAR(160) NULL,
  prompt_version VARCHAR(100) NULL,
  prompt_run_id VARCHAR(160) NULL,
  prompt_experiment VARCHAR(100) NULL,
  prompt_variant VARCHAR(100) NULL,
  request_json JSON NOT NULL,
  response_json JSON NOT NULL,
  saved_plan_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (generation_id),
  KEY idx_ai_plan_generation_school_time (school_id, created_at),
  KEY idx_ai_plan_generation_account_time (account_id, created_at),
  KEY idx_ai_plan_generation_role_status_time (actor_role, generation_status, created_at),
  KEY idx_ai_plan_generation_saved_plan (saved_plan_id),
  CONSTRAINT fk_ai_plan_generation_school FOREIGN KEY (school_id) REFERENCES school(school_id),
  CONSTRAINT fk_ai_plan_generation_saved_plan FOREIGN KEY (saved_plan_id) REFERENCES teaching_activity_plan(plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS teaching_plan_feedback (
  feedback_id BIGINT NOT NULL AUTO_INCREMENT,
  generation_id BIGINT NOT NULL,
  teacher_account_id BIGINT NOT NULL,
  adopted TINYINT(1) NOT NULL,
  rating TINYINT NOT NULL,
  teacher_note VARCHAR(2000) NULL,
  submitted_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (feedback_id),
  UNIQUE KEY uk_teaching_plan_feedback_generation (generation_id),
  KEY idx_teaching_plan_feedback_teacher_time (teacher_account_id, submitted_at),
  KEY idx_teaching_plan_feedback_adopted_rating (adopted, rating),
  CONSTRAINT chk_teaching_plan_feedback_rating CHECK (rating BETWEEN 1 AND 5),
  CONSTRAINT fk_teaching_plan_feedback_generation FOREIGN KEY (generation_id)
    REFERENCES ai_teaching_plan_generation(generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
