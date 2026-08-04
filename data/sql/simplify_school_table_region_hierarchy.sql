-- Simplify school table for address-based AMap geocoding.
-- Target database: MySQL 8.x
--
-- New school location model:
--   province_region_id  -> administrative_region.region_id
--   city_region_id      -> administrative_region.region_id
--   county_region_id    -> administrative_region.region_id
--   township_region_id  -> administrative_region.region_id
--   address             -> user-entered detailed address
--   longitude/latitude  -> resolved by AMap from the full address
--
-- This script only changes the school table. Application code, entities,
-- DTOs, admin forms, registration flow, seed data, and sync scripts must be
-- updated separately to stop referencing removed columns.

USE red_culture_platform;
SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS drop_column_if_exists $$
CREATE PROCEDURE drop_column_if_exists(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP COLUMN `', p_column_name, '`');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS drop_fk_if_exists $$
CREATE PROCEDURE drop_fk_if_exists(
    IN p_table_name VARCHAR(64),
    IN p_constraint_name VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.TABLE_CONSTRAINTS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND CONSTRAINT_NAME = p_constraint_name
          AND CONSTRAINT_TYPE = 'FOREIGN KEY'
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP FOREIGN KEY `', p_constraint_name, '`');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS drop_index_if_exists $$
CREATE PROCEDURE drop_index_if_exists(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND INDEX_NAME = p_index_name
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS add_fk_if_missing $$
CREATE PROCEDURE add_fk_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_constraint_name VARCHAR(64),
    IN p_fk_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.TABLE_CONSTRAINTS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND CONSTRAINT_NAME = p_constraint_name
          AND CONSTRAINT_TYPE = 'FOREIGN KEY'
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD CONSTRAINT `', p_constraint_name, '` ', p_fk_definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DELIMITER ;

-- =========================================================
-- 1. Drop constraints/indexes that depend on removed columns
-- =========================================================

CALL drop_fk_if_exists('school', 'fk_school_region');
CALL drop_fk_if_exists('school', 'fk_school_county_region');
CALL drop_fk_if_exists('school', 'fk_school_township_region');
CALL drop_fk_if_exists('school', 'fk_school_village_region');
CALL drop_fk_if_exists('school', 'fk_school_source');

CALL drop_index_if_exists('school', 'uk_school_code');
CALL drop_index_if_exists('school', 'idx_school_region');
CALL drop_index_if_exists('school', 'idx_school_status');

-- =========================================================
-- 2. Add province/city hierarchy fields
-- =========================================================

CALL add_column_if_missing('school', 'province_region_id', '`province_region_id` BIGINT NULL AFTER `school_name`');
CALL add_column_if_missing('school', 'city_region_id', '`city_region_id` BIGINT NULL AFTER `province_region_id`');

-- Optional backfill from existing county/township hierarchy.
-- This assumes administrative_region is a province/city/county/township tree.
UPDATE school s
LEFT JOIN administrative_region county ON county.region_id = s.county_region_id
LEFT JOIN administrative_region city_from_county ON city_from_county.region_id = county.parent_region_id
LEFT JOIN administrative_region province_from_city ON province_from_city.region_id = city_from_county.parent_region_id
SET
    s.city_region_id = COALESCE(s.city_region_id, city_from_county.region_id),
    s.province_region_id = COALESCE(s.province_region_id, province_from_city.region_id)
WHERE s.county_region_id IS NOT NULL;

UPDATE school s
LEFT JOIN administrative_region township ON township.region_id = s.township_region_id
LEFT JOIN administrative_region county_from_township ON county_from_township.region_id = township.parent_region_id
LEFT JOIN administrative_region city_from_township ON city_from_township.region_id = county_from_township.parent_region_id
LEFT JOIN administrative_region province_from_township ON province_from_township.region_id = city_from_township.parent_region_id
SET
    s.county_region_id = COALESCE(s.county_region_id, county_from_township.region_id),
    s.city_region_id = COALESCE(s.city_region_id, city_from_township.region_id),
    s.province_region_id = COALESCE(s.province_region_id, province_from_township.region_id)
WHERE s.township_region_id IS NOT NULL;

-- =========================================================
-- 3. Remove old/unnecessary school columns
-- =========================================================

CALL drop_column_if_exists('school', 'region_id');
CALL drop_column_if_exists('school', 'village_region_id');

CALL drop_column_if_exists('school', 'school_code');
CALL drop_column_if_exists('school', 'school_alias');
CALL drop_column_if_exists('school', 'school_level');
CALL drop_column_if_exists('school', 'school_nature');
CALL drop_column_if_exists('school', 'is_rural_school');
CALL drop_column_if_exists('school', 'is_teaching_point');
CALL drop_column_if_exists('school', 'postcode');
CALL drop_column_if_exists('school', 'geo_source_type');
CALL drop_column_if_exists('school', 'poi_name');
CALL drop_column_if_exists('school', 'poi_address');
CALL drop_column_if_exists('school', 'poi_type');
CALL drop_column_if_exists('school', 'geo_confidence');
CALL drop_column_if_exists('school', 'geo_verified');
CALL drop_column_if_exists('school', 'source_id');
CALL drop_column_if_exists('school', 'review_status');

-- =========================================================
-- 4. Add useful indexes and foreign keys
-- =========================================================

CALL drop_index_if_exists('school', 'idx_school_province');
CALL drop_index_if_exists('school', 'idx_school_city');
CALL drop_index_if_exists('school', 'idx_school_county');
CALL drop_index_if_exists('school', 'idx_school_township');
CALL drop_index_if_exists('school', 'idx_school_active');

CREATE INDEX idx_school_province ON school (province_region_id);
CREATE INDEX idx_school_city ON school (city_region_id);
CREATE INDEX idx_school_county ON school (county_region_id);
CREATE INDEX idx_school_township ON school (township_region_id);
CREATE INDEX idx_school_active ON school (is_active);

CALL add_fk_if_missing(
    'school',
    'fk_school_province_region',
    'FOREIGN KEY (`province_region_id`) REFERENCES `administrative_region`(`region_id`)'
);

CALL add_fk_if_missing(
    'school',
    'fk_school_city_region',
    'FOREIGN KEY (`city_region_id`) REFERENCES `administrative_region`(`region_id`)'
);

CALL add_fk_if_missing(
    'school',
    'fk_school_county_region',
    'FOREIGN KEY (`county_region_id`) REFERENCES `administrative_region`(`region_id`)'
);

CALL add_fk_if_missing(
    'school',
    'fk_school_township_region',
    'FOREIGN KEY (`township_region_id`) REFERENCES `administrative_region`(`region_id`)'
);

-- =========================================================
-- 5. Cleanup helper procedures
-- =========================================================

DROP PROCEDURE IF EXISTS add_fk_if_missing;
DROP PROCEDURE IF EXISTS drop_index_if_exists;
DROP PROCEDURE IF EXISTS drop_fk_if_exists;
DROP PROCEDURE IF EXISTS drop_column_if_exists;
DROP PROCEDURE IF EXISTS add_column_if_missing;
