-- School-centered module schema proposal for red_culture_platform
-- This file is a design proposal and is not yet merged into mysql_red_culture_all_in_one.sql.

USE red_culture_platform;

SET NAMES utf8mb4;

-- 1. Extend shared polymorphic tables so school/resource/activity data
-- can reuse tags, media, sources, content chunks, and audit logs.

ALTER TABLE entity_tag_rel
MODIFY COLUMN entity_type ENUM('site', 'hero', 'event', 'memorial', 'story', 'school', 'resource', 'activity_plan') NOT NULL;

ALTER TABLE resource_media
MODIFY COLUMN entity_type ENUM('site', 'hero', 'event', 'memorial', 'story', 'school', 'resource', 'activity_plan') NOT NULL;

ALTER TABLE entity_source_rel
MODIFY COLUMN entity_type ENUM('site', 'hero', 'event', 'memorial', 'story', 'school', 'resource', 'activity_plan') NOT NULL;

ALTER TABLE content_chunk
MODIFY COLUMN entity_type ENUM('site', 'hero', 'event', 'memorial', 'story', 'school', 'resource', 'activity_plan') NOT NULL;

-- Existing installations: rebuild the Chinese-capable FULLTEXT index once.
ALTER TABLE content_chunk
  ADD COLUMN retrieval_text LONGTEXT NULL AFTER chunk_text,
  ADD COLUMN embedding_hash CHAR(64) NULL AFTER embedding_status,
  ADD COLUMN embedding_model VARCHAR(100) NULL AFTER embedding_hash,
  ADD COLUMN embedding_dimensions INT NULL AFTER embedding_model,
  ADD COLUMN embedding_index_version VARCHAR(32) NULL AFTER embedding_dimensions,
  ADD COLUMN embedded_at DATETIME NULL AFTER embedding_index_version,
  DROP INDEX ft_chunk_text;
CREATE FULLTEXT INDEX ft_chunk_text
  ON content_chunk (chunk_title, chunk_text, retrieval_text)
  WITH PARSER ngram;

ALTER TABLE audit_log
MODIFY COLUMN entity_type ENUM('region', 'site', 'hero', 'event', 'memorial', 'story', 'tag', 'school', 'resource', 'activity_plan') NOT NULL;

ALTER TABLE story_entity_rel
MODIFY COLUMN entity_type ENUM('site', 'hero', 'event', 'memorial', 'school', 'resource') NOT NULL;

-- 2. School main table

CREATE TABLE IF NOT EXISTS school (
  school_id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_code            VARCHAR(50) NOT NULL,
  school_name            VARCHAR(200) NOT NULL,
  school_alias           VARCHAR(200) NULL,
  region_id              BIGINT NULL,
  county_region_id       BIGINT NULL,
  township_region_id     BIGINT NULL,
  village_region_id      BIGINT NULL,
  school_level           ENUM('kindergarten', 'primary', 'junior', 'senior', 'nine_year', 'twelve_year', 'vocational', 'special', 'other') NOT NULL DEFAULT 'primary',
  school_type            VARCHAR(100) NULL,
  school_nature          ENUM('public', 'private', 'other') NOT NULL DEFAULT 'public',
  is_rural_school        TINYINT(1) NOT NULL DEFAULT 1,
  is_teaching_point      TINYINT(1) NOT NULL DEFAULT 0,
  address                VARCHAR(300) NULL,
  postcode               VARCHAR(20) NULL,
  contact_phone          VARCHAR(50) NULL,
  principal_name         VARCHAR(100) NULL,
  longitude              DECIMAL(10,7) NULL,
  latitude               DECIMAL(10,7) NULL,
  geo_source_type        ENUM('amap_poi', 'manual', 'school_official', 'government_doc', 'satellite_fix', 'other') NOT NULL DEFAULT 'government_doc',
  poi_name               VARCHAR(200) NULL,
  poi_address            VARCHAR(300) NULL,
  poi_type               VARCHAR(200) NULL,
  geo_confidence         ENUM('high', 'medium', 'low', 'unknown') NOT NULL DEFAULT 'unknown',
  geo_verified           TINYINT(1) NOT NULL DEFAULT 0,
  intro                  TEXT NULL,
  source_id              BIGINT NULL,
  review_status          ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_school_code UNIQUE (school_code),
  CONSTRAINT fk_school_region
    FOREIGN KEY (region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_county_region
    FOREIGN KEY (county_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_township_region
    FOREIGN KEY (township_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_village_region
    FOREIGN KEY (village_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  KEY idx_school_name (school_name),
  KEY idx_school_region (region_id),
  KEY idx_school_county (county_region_id),
  KEY idx_school_township (township_region_id),
  KEY idx_school_status (review_status, is_active),
  KEY idx_school_geo (longitude, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. School coordinate audit/history table

CREATE TABLE IF NOT EXISTS school_geo_record (
  geo_record_id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_id              BIGINT NOT NULL,
  longitude              DECIMAL(10,7) NOT NULL,
  latitude               DECIMAL(10,7) NOT NULL,
  source_type            ENUM('amap_poi', 'manual', 'school_official', 'government_doc', 'satellite_fix', 'other') NOT NULL DEFAULT 'amap_poi',
  poi_name               VARCHAR(200) NULL,
  poi_address            VARCHAR(300) NULL,
  poi_type               VARCHAR(200) NULL,
  confidence_level       ENUM('high', 'medium', 'low', 'unknown') NOT NULL DEFAULT 'unknown',
  is_manual_reviewed     TINYINT(1) NOT NULL DEFAULT 0,
  review_result          ENUM('pending', 'confirmed', 'corrected', 'rejected') NOT NULL DEFAULT 'pending',
  reviewer_name          VARCHAR(100) NULL,
  reviewed_at            DATETIME NULL,
  is_current             TINYINT(1) NOT NULL DEFAULT 1,
  remark                 VARCHAR(255) NULL,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_school_geo_record_school
    FOREIGN KEY (school_id) REFERENCES school(school_id),
  KEY idx_school_geo_record_school (school_id),
  KEY idx_school_geo_record_current (school_id, is_current)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. Generic local educational resource table

CREATE TABLE IF NOT EXISTS local_edu_resource (
  resource_id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  resource_code          VARCHAR(50) NOT NULL,
  resource_name          VARCHAR(200) NOT NULL,
  resource_alias         VARCHAR(200) NULL,
  resource_category      ENUM('red_culture', 'intangible_culture', 'traditional_culture', 'local_history', 'public_culture', 'labor_education', 'public_welfare', 'ecological_civilization', 'patriotism_base', 'social_practice', 'other') NOT NULL DEFAULT 'other',
  resource_subcategory   VARCHAR(100) NULL,
  region_id              BIGINT NULL,
  county_region_id       BIGINT NULL,
  township_region_id     BIGINT NULL,
  address                VARCHAR(300) NULL,
  longitude              DECIMAL(10,7) NULL,
  latitude               DECIMAL(10,7) NULL,
  organization_name      VARCHAR(200) NULL,
  contact_phone          VARCHAR(50) NULL,
  opening_time_desc      VARCHAR(255) NULL,
  reservation_required   TINYINT(1) NOT NULL DEFAULT 0,
  recommended_visit_minutes INT NULL,
  intro                  TEXT NULL,
  education_value        TEXT NULL,
  activity_suggestion    LONGTEXT NULL,
  target_grade           VARCHAR(100) NULL,
  safety_note            TEXT NULL,
  source_id              BIGINT NULL,
  review_status          ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_local_edu_resource_code UNIQUE (resource_code),
  CONSTRAINT fk_local_edu_resource_region
    FOREIGN KEY (region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_local_edu_resource_county_region
    FOREIGN KEY (county_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_local_edu_resource_township_region
    FOREIGN KEY (township_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_local_edu_resource_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  KEY idx_local_edu_resource_name (resource_name),
  KEY idx_local_edu_resource_category (resource_category, resource_subcategory),
  KEY idx_local_edu_resource_region (region_id),
  KEY idx_local_edu_resource_status (review_status, is_active),
  KEY idx_local_edu_resource_geo (longitude, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. School-to-resource relation table

CREATE TABLE IF NOT EXISTS school_resource_rel (
  rel_id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_id              BIGINT NOT NULL,
  resource_id            BIGINT NOT NULL,
  relation_type          ENUM('nearby', 'cooperation', 'practice', 'curriculum_support', 'volunteer_base', 'research_route', 'other') NOT NULL DEFAULT 'nearby',
  distance_meters        INT NULL,
  recommended_travel_mode ENUM('walk', 'bike', 'bus', 'drive', 'mixed', 'unknown') NOT NULL DEFAULT 'unknown',
  estimated_duration_minutes INT NULL,
  reachability_level     ENUM('near', 'medium', 'far', 'very_far', 'unknown') NOT NULL DEFAULT 'unknown',
  priority_level         TINYINT NOT NULL DEFAULT 3,
  education_theme_summary VARCHAR(255) NULL,
  source_id              BIGINT NULL,
  review_status          ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_school_resource_rel_school
    FOREIGN KEY (school_id) REFERENCES school(school_id),
  CONSTRAINT fk_school_resource_rel_resource
    FOREIGN KEY (resource_id) REFERENCES local_edu_resource(resource_id),
  CONSTRAINT fk_school_resource_rel_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  CONSTRAINT uk_school_resource_rel UNIQUE (school_id, resource_id, relation_type),
  KEY idx_school_resource_rel_school (school_id),
  KEY idx_school_resource_rel_resource (resource_id),
  KEY idx_school_resource_rel_distance (distance_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. Teaching activity plan table

CREATE TABLE IF NOT EXISTS teaching_activity_plan (
  plan_id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_code              VARCHAR(50) NOT NULL,
  school_id              BIGINT NOT NULL,
  resource_id            BIGINT NULL,
  theme                  VARCHAR(200) NOT NULL,
  activity_type          ENUM('classroom', 'field_trip', 'volunteer_service', 'research_study', 'labor_practice', 'club_activity', 'school_based_course', 'other') NOT NULL DEFAULT 'classroom',
  suitable_grade         VARCHAR(100) NULL,
  objective_text         TEXT NULL,
  activity_content       LONGTEXT NOT NULL,
  preparation_text       TEXT NULL,
  safety_text            TEXT NULL,
  expected_outcome       TEXT NULL,
  duration_minutes       INT NULL,
  source_id              BIGINT NULL,
  review_status          ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_teaching_activity_plan_code UNIQUE (plan_code),
  CONSTRAINT fk_teaching_activity_plan_school
    FOREIGN KEY (school_id) REFERENCES school(school_id),
  CONSTRAINT fk_teaching_activity_plan_resource
    FOREIGN KEY (resource_id) REFERENCES local_edu_resource(resource_id),
  CONSTRAINT fk_teaching_activity_plan_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  KEY idx_teaching_activity_plan_school (school_id),
  KEY idx_teaching_activity_plan_resource (resource_id),
  KEY idx_teaching_activity_plan_theme (theme),
  KEY idx_teaching_activity_plan_status (review_status, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6.1 AI teaching-plan generation snapshots and teacher feedback

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
  reason_codes_json JSON NULL,
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

-- 7. Administrator catalog import and projection records
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
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
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

-- 8. RAG authoritative Web search source whitelist
CREATE TABLE IF NOT EXISTS rag_web_source (
  source_id BIGINT NOT NULL AUTO_INCREMENT,
  display_name VARCHAR(120) NOT NULL,
  domain VARCHAR(255) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 100,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (source_id),
  UNIQUE KEY uk_rag_web_source_domain (domain),
  KEY idx_rag_web_source_enabled_sort (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 权威 Web 检索域名白名单';

-- 7. Optional sample seed records for pilot schools
-- Keep this section commented until the design is confirmed.
--
-- INSERT INTO school
--   (school_code, school_name, county_region_id, township_region_id, school_level, school_type, school_nature,
--    is_rural_school, is_teaching_point, address, longitude, latitude, geo_source_type, poi_name, poi_type,
--    geo_confidence, geo_verified, review_status, is_active)
-- VALUES
--   ('SCH_SJZ_GC_0001', '石家庄市藁城区常安镇里庄小学', NULL, NULL, 'primary', '村小', 'public',
--    1, 0, '河北省石家庄市藁城区常安镇里庄村振兴大街西220号', 114.9537180, 38.0271030, 'amap_poi',
--    '石家庄市藁城区常安镇里庄小学', '科教文化服务;学校;小学', 'high', 0, 'draft', 1);
