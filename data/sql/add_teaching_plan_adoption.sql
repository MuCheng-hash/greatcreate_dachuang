-- A downloaded teacher plan is considered adopted for actual teaching use.
-- The existing column is an ENUM on legacy databases, so extend it safely.
SET NAMES utf8mb4;

ALTER TABLE teaching_activity_plan
  MODIFY COLUMN review_status ENUM('draft', 'pending', 'approved', 'adopted', 'rejected')
  NOT NULL DEFAULT 'draft';
