USE red_culture_platform;

SET NAMES utf8mb4;

ALTER TABLE local_edu_resource
  ADD COLUMN external_provider VARCHAR(30) NULL AFTER source_id,
  ADD COLUMN external_place_id VARCHAR(100) NULL AFTER external_provider,
  ADD COLUMN source_checked_at DATETIME NULL AFTER external_place_id;

CREATE UNIQUE INDEX uk_local_resource_external_place
  ON local_edu_resource (external_provider, external_place_id);

CREATE TABLE IF NOT EXISTS resource_discovery_run (
  run_id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_id              BIGINT NOT NULL,
  radius_meters          INT NOT NULL,
  provider               VARCHAR(30) NOT NULL DEFAULT 'amap',
  status                 ENUM('pending', 'running', 'completed', 'failed') NOT NULL DEFAULT 'pending',
  forced                 TINYINT(1) NOT NULL DEFAULT 0,
  provider_count         INT NOT NULL DEFAULT 0,
  candidate_count        INT NOT NULL DEFAULT 0,
  analysis_count         INT NOT NULL DEFAULT 0,
  error_message          VARCHAR(500) NULL,
  started_at             DATETIME NULL,
  completed_at           DATETIME NULL,
  cache_expires_at       DATETIME NULL,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_discovery_run_school FOREIGN KEY (school_id) REFERENCES school(school_id),
  KEY idx_discovery_run_cache (school_id, radius_meters, status, cache_expires_at),
  KEY idx_discovery_run_status (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS resource_discovery_candidate (
  candidate_id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_id              BIGINT NOT NULL,
  provider               VARCHAR(30) NOT NULL DEFAULT 'amap',
  provider_place_id      VARCHAR(100) NOT NULL,
  place_name             VARCHAR(200) NOT NULL,
  address                VARCHAR(300) NULL,
  longitude              DECIMAL(10,7) NULL,
  latitude               DECIMAL(10,7) NULL,
  provider_type_code     VARCHAR(50) NULL,
  provider_type_name     VARCHAR(255) NULL,
  contact_phone          VARCHAR(100) NULL,
  opening_hours          VARCHAR(255) NULL,
  distance_meters        INT NULL,
  raw_json               JSON NULL,
  analysis_status        ENUM('unanalyzed', 'completed', 'failed') NOT NULL DEFAULT 'unanalyzed',
  ideological_relevant   TINYINT(1) NULL,
  ai_category            VARCHAR(50) NULL,
  ai_subcategory         VARCHAR(100) NULL,
  ai_confidence          DECIMAL(4,3) NULL,
  ai_rationale           TEXT NULL,
  education_themes_json  JSON NULL,
  target_grades          VARCHAR(255) NULL,
  activity_suggestion    TEXT NULL,
  verification_notes     TEXT NULL,
  decision_status        ENUM('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending',
  matched_resource_id    BIGINT NULL,
  last_error             VARCHAR(500) NULL,
  first_seen_at          DATETIME NOT NULL,
  last_seen_at           DATETIME NOT NULL,
  last_analyzed_at       DATETIME NULL,
  reviewed_by            VARCHAR(100) NULL,
  reviewed_at            DATETIME NULL,
  review_remark          VARCHAR(255) NULL,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_discovery_candidate_place UNIQUE (school_id, provider, provider_place_id),
  CONSTRAINT fk_discovery_candidate_school FOREIGN KEY (school_id) REFERENCES school(school_id),
  CONSTRAINT fk_discovery_candidate_resource FOREIGN KEY (matched_resource_id) REFERENCES local_edu_resource(resource_id),
  KEY idx_discovery_candidate_review (decision_status, analysis_status),
  KEY idx_discovery_candidate_school (school_id, last_seen_at),
  KEY idx_discovery_candidate_geo (longitude, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS resource_discovery_run_item (
  run_id                 BIGINT NOT NULL,
  candidate_id           BIGINT NOT NULL,
  result_rank            INT NOT NULL,
  distance_meters        INT NULL,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id, candidate_id),
  CONSTRAINT fk_discovery_item_run FOREIGN KEY (run_id) REFERENCES resource_discovery_run(run_id) ON DELETE CASCADE,
  CONSTRAINT fk_discovery_item_candidate FOREIGN KEY (candidate_id) REFERENCES resource_discovery_candidate(candidate_id),
  KEY idx_discovery_item_rank (run_id, result_rank)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
