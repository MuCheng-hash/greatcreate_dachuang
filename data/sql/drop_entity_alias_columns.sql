-- Remove obsolete display-alias fields from graph entities and generic resources.
-- Safe to run repeatedly against an existing MySQL database.
DELIMITER $$

DROP PROCEDURE IF EXISTS drop_column_if_exists $$
CREATE PROCEDURE drop_column_if_exists(IN table_name_param VARCHAR(64), IN column_name_param VARCHAR(64))
BEGIN
  IF EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = table_name_param
      AND COLUMN_NAME = column_name_param
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', table_name_param, '` DROP COLUMN `', column_name_param, '`');
    PREPARE statement_to_run FROM @ddl;
    EXECUTE statement_to_run;
    DEALLOCATE PREPARE statement_to_run;
  END IF;
END $$

CALL drop_column_if_exists('local_edu_resource', 'resource_alias') $$
CALL drop_column_if_exists('red_site', 'site_alias') $$
CALL drop_column_if_exists('hero_person', 'hero_alias') $$
CALL drop_column_if_exists('historical_event', 'event_alias') $$

DROP PROCEDURE IF EXISTS drop_column_if_exists $$
DELIMITER ;
