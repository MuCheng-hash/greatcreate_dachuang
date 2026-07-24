USE red_culture_platform;

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS school_registration (
  registration_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  apply_account          VARCHAR(100) NOT NULL,
  password_hash          VARCHAR(255) NOT NULL,
  contact_name           VARCHAR(100) NULL,
  contact_phone          VARCHAR(50) NULL,
  contact_email          VARCHAR(120) NULL,
  school_name            VARCHAR(200) NOT NULL,
  school_alias           VARCHAR(200) NULL,
  school_level           ENUM('kindergarten', 'primary', 'junior', 'senior', 'nine_year', 'twelve_year', 'vocational', 'special', 'other') NOT NULL DEFAULT 'primary',
  school_type            VARCHAR(100) NULL,
  school_nature          ENUM('public', 'private', 'other') NOT NULL DEFAULT 'public',
  county_region_id       BIGINT NULL,
  township_region_id     BIGINT NULL,
  address                VARCHAR(300) NULL,
  longitude              DECIMAL(10,7) NULL,
  latitude               DECIMAL(10,7) NULL,
  geo_source_type        ENUM('amap_poi', 'manual', 'school_official', 'government_doc', 'satellite_fix', 'other') NOT NULL DEFAULT 'manual',
  geo_confidence         ENUM('high', 'medium', 'low', 'unknown') NOT NULL DEFAULT 'unknown',
  intro                  TEXT NULL,
  review_status          ENUM('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending',
  review_remark          VARCHAR(255) NULL,
  reviewed_by            VARCHAR(100) NULL,
  reviewed_at            DATETIME NULL,
  linked_school_id       BIGINT NULL,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_school_registration_account UNIQUE (apply_account),
  CONSTRAINT fk_school_registration_county_region
    FOREIGN KEY (county_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_registration_township_region
    FOREIGN KEY (township_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_registration_linked_school
    FOREIGN KEY (linked_school_id) REFERENCES school(school_id),
  KEY idx_school_registration_status (review_status),
  KEY idx_school_registration_school_name (school_name),
  KEY idx_school_registration_geo (longitude, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS school_user_account (
  account_id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  username               VARCHAR(100) NOT NULL,
  password_hash          VARCHAR(255) NOT NULL,
  role_code              VARCHAR(50) NOT NULL DEFAULT 'school_admin',
  school_id              BIGINT NULL,
  registration_id        BIGINT NULL,
  display_name           VARCHAR(120) NULL,
  contact_name           VARCHAR(100) NULL,
  contact_phone          VARCHAR(50) NULL,
  status                 ENUM('pending_activation', 'active', 'disabled') NOT NULL DEFAULT 'active',
  last_login_at          DATETIME NULL,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_school_user_account_username UNIQUE (username),
  CONSTRAINT uk_school_user_account_school UNIQUE (school_id),
  CONSTRAINT fk_school_user_account_school
    FOREIGN KEY (school_id) REFERENCES school(school_id),
  CONSTRAINT fk_school_user_account_registration
    FOREIGN KEY (registration_id) REFERENCES school_registration(registration_id),
  KEY idx_school_user_account_status (status),
  KEY idx_school_user_account_role (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS auth_refresh_token (
  token_id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  account_id           BIGINT NOT NULL,
  token_hash           CHAR(64) NOT NULL,
  token_family_id      VARCHAR(64) NOT NULL,
  issued_at            DATETIME NOT NULL,
  expires_at           DATETIME NOT NULL,
  rotated_at           DATETIME NULL,
  revoked_at           DATETIME NULL,
  revoke_reason        VARCHAR(100) NULL,
  user_agent           VARCHAR(512) NULL,
  client_ip            VARCHAR(45) NULL,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_auth_refresh_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_auth_refresh_token_account
    FOREIGN KEY (account_id) REFERENCES school_user_account(account_id),
  KEY idx_auth_refresh_token_account_status (account_id, revoked_at, expires_at),
  KEY idx_auth_refresh_token_family (token_family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
