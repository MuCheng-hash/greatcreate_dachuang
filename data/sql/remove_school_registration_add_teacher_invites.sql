-- Switch from school applications to school-selected account registration.
-- Existing school-registration records are intentionally removed by product decision.
USE red_culture_platform;
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS drop_school_registration_legacy;
DELIMITER $$
CREATE PROCEDURE drop_school_registration_legacy()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'school_user_account' AND column_name = 'registration_id') THEN
    ALTER TABLE school_user_account DROP FOREIGN KEY fk_school_user_account_registration;
    ALTER TABLE school_user_account DROP COLUMN registration_id;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'school_registration') THEN
    DROP TABLE school_registration;
  END IF;
END $$
DELIMITER ;
CALL drop_school_registration_legacy();
DROP PROCEDURE IF EXISTS drop_school_registration_legacy;

-- Schools are curated by administrators; retain existing enabled schools in the public registration catalog.
UPDATE school SET review_status = 'approved' WHERE is_active = 1 AND review_status <> 'approved';

CREATE TABLE IF NOT EXISTS teacher_registration_invite (
  invite_id BIGINT NOT NULL AUTO_INCREMENT,
  school_id BIGINT NOT NULL,
  code_hash CHAR(64) NOT NULL,
  status ENUM('active', 'revoked') NOT NULL DEFAULT 'active',
  expires_at DATETIME NOT NULL,
  max_uses INT NOT NULL DEFAULT 50,
  used_count INT NOT NULL DEFAULT 0,
  created_by_account_id BIGINT NOT NULL,
  revoked_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (invite_id),
  UNIQUE KEY uk_teacher_registration_invite_hash (code_hash),
  KEY idx_teacher_registration_invite_school_status (school_id, status, expires_at),
  CONSTRAINT fk_teacher_registration_invite_school FOREIGN KEY (school_id) REFERENCES school(school_id),
  CONSTRAINT fk_teacher_registration_invite_creator FOREIGN KEY (created_by_account_id) REFERENCES school_user_account(account_id),
  CONSTRAINT chk_teacher_registration_invite_uses CHECK (max_uses > 0 AND used_count >= 0 AND used_count <= max_uses)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
