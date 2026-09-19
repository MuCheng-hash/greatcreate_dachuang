-- MySQL 8.0；在 IDEA 选择目标数据库后，作为完整脚本执行（包括 DELIMITER）。
-- 使用独立控制台，确保没有未提交事务；不要与应用启动/其他迁移并发执行。
-- 本文件自包含，不创建/切换数据库，不删除业务表或重置账号密码。
-- DDL 隐式提交，失败不能整体回滚；已有库务必先备份，再修正报错后重跑。
-- 未选择数据库时 MySQL 会在创建辅助过程前报 No database selected。
DELIMITER $$
DROP PROCEDURE IF EXISTS gc_sql_apply$$
CREATE PROCEDURE gc_sql_apply()
SQL SECURITY INVOKER
BEGIN
  DECLARE lock_acquired INT DEFAULT 0;
  DECLARE old_fk INT DEFAULT @@SESSION.foreign_key_checks;
  DECLARE old_unique INT DEFAULT @@SESSION.unique_checks;
  DECLARE old_mode TEXT DEFAULT @@SESSION.sql_mode;
  DECLARE error_message VARCHAR(128);
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    SET SESSION foreign_key_checks=old_fk;
    SET SESSION unique_checks=old_unique;
    SET SESSION sql_mode=old_mode;
    IF lock_acquired=1 THEN DO RELEASE_LOCK(CONCAT('gc_schema:',DATABASE())); END IF;
    RESIGNAL;
  END;
  IF DATABASE() IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Select the target database in IDEA first';
  END IF;
  SELECT GET_LOCK(CONCAT('gc_schema:',DATABASE()),0) INTO lock_acquired;
  IF COALESCE(lock_acquired,0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Another schema migration is running';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Initialization requires an empty database; use database_setup_existing.sql';
  END IF;
  SET SESSION foreign_key_checks=1;
  SET SESSION unique_checks=1;
  SET SESSION sql_mode=CONCAT_WS(',',NULLIF(old_mode,''),'STRICT_ALL_TABLES');

  CREATE TABLE `administrative_region` (
  `region_id` bigint NOT NULL AUTO_INCREMENT,
  `parent_region_id` bigint DEFAULT NULL,
  `region_name` varchar(100) NOT NULL,
  `region_level` enum('province','city','county','township','village') NOT NULL,
  `adcode` varchar(20) DEFAULT NULL,
  `center_longitude` decimal(10,7) DEFAULT NULL,
  `center_latitude` decimal(10,7) DEFAULT NULL,
  `boundary_geojson` json DEFAULT NULL,
  `intro` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`region_id`),
  UNIQUE KEY `uk_region_adcode` (`adcode`),
  KEY `idx_region_parent` (`parent_region_id`),
  KEY `idx_region_name_level` (`region_name`,`region_level`),
  CONSTRAINT `fk_region_parent` FOREIGN KEY (`parent_region_id`) REFERENCES `administrative_region` (`region_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `agent_action_idempotency` (
  `action_id` varchar(96) NOT NULL,
  `turn_id` varchar(64) NOT NULL,
  `operation` varchar(160) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `request_json` json DEFAULT NULL,
  `status` varchar(24) NOT NULL,
  `response_json` json DEFAULT NULL,
  `resource_reference` varchar(255) DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `payload_redacted_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`action_id`),
  KEY `idx_agent_action_idempotency_turn` (`turn_id`,`created_at`),
  KEY `idx_agent_action_idempotency_cleanup` (`completed_at`,`payload_redacted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `agent_action_outbox` (
  `event_id` varchar(64) NOT NULL,
  `action_id` varchar(96) NOT NULL,
  `event_type` varchar(160) NOT NULL,
  `payload_json` json NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lease_owner` varchar(128) DEFAULT NULL,
  `lease_expires_at` datetime DEFAULT NULL,
  `published_at` datetime DEFAULT NULL,
  `error_summary` varchar(1000) DEFAULT NULL,
  `payload_redacted_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_agent_action_outbox_action_type` (`action_id`,`event_type`),
  KEY `idx_agent_action_outbox_claim` (`status`,`next_attempt_at`,`lease_expires_at`),
  KEY `idx_agent_action_outbox_cleanup` (`published_at`,`payload_redacted_at`),
  CONSTRAINT `fk_agent_action_outbox_action` FOREIGN KEY (`action_id`) REFERENCES `agent_action_idempotency` (`action_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `agent_debug_event` (
  `event_id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
  `debug_id` bigint NOT NULL COMMENT '调试会话ID',
  `event_name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件名称，如 retrieval.started、tool.completed',
  `event_stage` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件阶段：retrieval/model/tool/final',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'started' COMMENT '状态：started/success/failed',
  `duration_ms` int DEFAULT NULL COMMENT '节点耗时',
  `payload_json` json DEFAULT NULL COMMENT '事件详情、命中资源、工具参数等',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间',
  PRIMARY KEY (`event_id`),
  KEY `idx_agent_debug_event_debug_id` (`debug_id`),
  KEY `idx_agent_debug_event_name` (`event_name`),
  KEY `idx_agent_debug_event_stage` (`event_stage`),
  KEY `idx_agent_debug_event_status` (`status`),
  KEY `idx_agent_debug_event_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体调试事件表';

  CREATE TABLE `agent_debug_session` (
  `debug_id` bigint NOT NULL AUTO_INCREMENT COMMENT '调试会话ID',
  `question` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '测试问题',
  `scope_type` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '调试范围类型',
  `scope_id` bigint DEFAULT NULL COMMENT '调试范围ID',
  `model_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型ID',
  `provider` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型供应商',
  `answer` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '最终回答',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'success' COMMENT '状态：success/failed/stopped',
  `duration_ms` int DEFAULT NULL COMMENT '总耗时',
  `created_by` bigint DEFAULT NULL COMMENT '管理员账号ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`debug_id`),
  KEY `idx_agent_debug_scope` (`scope_type`,`scope_id`),
  KEY `idx_agent_debug_status` (`status`),
  KEY `idx_agent_debug_created_by` (`created_by`),
  KEY `idx_agent_debug_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体调试会话表';

  CREATE TABLE `audit_log` (
  `log_id` bigint NOT NULL AUTO_INCREMENT,
  `entity_type` enum('region','site','hero','event','memorial','story','tag','school','resource','activity_plan') NOT NULL,
  `entity_id` bigint NOT NULL,
  `operation_type` enum('insert','update','delete','review') NOT NULL,
  `operator_name` varchar(100) DEFAULT NULL,
  `change_summary` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`log_id`),
  KEY `idx_audit_entity` (`entity_type`,`entity_id`),
  KEY `idx_audit_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `catalog_import_batch` (
  `batch_id` bigint NOT NULL AUTO_INCREMENT,
  `file_name` varchar(255) NOT NULL,
  `created_by` bigint DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `total_rows` int NOT NULL DEFAULT '0',
  `valid_rows` int NOT NULL DEFAULT '0',
  `invalid_rows` int NOT NULL DEFAULT '0',
  `duplicate_rows` int NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `catalog_import_row` (
  `row_id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `sheet_name` varchar(64) NOT NULL,
  `row_no` int NOT NULL,
  `entity_type` varchar(32) NOT NULL,
  `payload_json` longtext NOT NULL,
  `validation_status` varchar(32) NOT NULL,
  `validation_message` varchar(500) DEFAULT NULL,
  `imported_entity_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`row_id`),
  KEY `fk_catalog_import_row_batch` (`batch_id`),
  CONSTRAINT `fk_catalog_import_row_batch` FOREIGN KEY (`batch_id`) REFERENCES `catalog_import_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `catalog_projection_task` (
  `task_id` bigint NOT NULL AUTO_INCREMENT,
  `entity_type` varchar(64) NOT NULL,
  `entity_id` bigint NOT NULL,
  `task_type` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `attempt_count` int NOT NULL DEFAULT '0',
  `last_error` varchar(500) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`task_id`),
  KEY `idx_catalog_projection_task_status` (`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `class_info` (
  `class_id` bigint NOT NULL AUTO_INCREMENT COMMENT '班级ID',
  `school_id` bigint NOT NULL COMMENT '所属学校',
  `class_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '班级名称',
  `grade_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '年级',
  `class_type` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'administrative' COMMENT '班级类型：administrative/teaching',
  `invite_code` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '学生加入班级的邀请码',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态：active/archived',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`class_id`),
  UNIQUE KEY `uk_class_invite_code` (`invite_code`),
  KEY `idx_class_school_id` (`school_id`),
  KEY `idx_class_grade_name` (`grade_name`),
  KEY `idx_class_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='班级表';

  CREATE TABLE `class_learning_task` (
  `task_id` bigint NOT NULL AUTO_INCREMENT,
  `class_id` bigint NOT NULL,
  `publisher_teacher_id` bigint DEFAULT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `published_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `due_at` datetime DEFAULT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'published',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `task_type` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'red_culture_learning',
  `submission_rule` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'text_required',
  `allow_late_submission` tinyint(1) NOT NULL DEFAULT '1',
  `start_at` datetime DEFAULT NULL,
  `material_filename` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `material_storage_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `material_content_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`task_id`),
  KEY `idx_class_learning_task_class` (`class_id`),
  KEY `idx_class_learning_task_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE `class_member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  `class_id` bigint NOT NULL COMMENT '班级ID',
  `student_id` bigint NOT NULL COMMENT '学生ID',
  `join_source` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'manual' COMMENT '加入来源：manual/import/invite',
  `joined_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态：active/removed',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_primary` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否主班级',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_class_member` (`class_id`,`student_id`),
  KEY `idx_class_member_class_id` (`class_id`),
  KEY `idx_class_member_student_id` (`student_id`),
  KEY `idx_class_member_status` (`status`),
  KEY `idx_class_member_primary` (`is_primary`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='班级成员表';

  CREATE TABLE `class_teacher` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  `class_id` bigint NOT NULL COMMENT '班级ID',
  `teacher_id` bigint NOT NULL COMMENT '教师ID',
  `teacher_role` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'subject_teacher' COMMENT '教师角色：head_teacher/subject_teacher',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_class_teacher_role` (`class_id`,`teacher_id`,`teacher_role`),
  KEY `idx_class_teacher_class_id` (`class_id`),
  KEY `idx_class_teacher_teacher_id` (`teacher_id`),
  KEY `idx_class_teacher_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='班级教师表';

  CREATE TABLE `data_source` (
  `source_id` bigint NOT NULL AUTO_INCREMENT,
  `source_name` varchar(200) NOT NULL,
  `source_type` enum('government','encyclopedia','news','museum','paper','other') NOT NULL,
  `organization_name` varchar(200) DEFAULT NULL,
  `base_url` varchar(500) DEFAULT NULL,
  `reliability_level` tinyint NOT NULL DEFAULT '3',
  `license_note` varchar(255) DEFAULT NULL,
  `crawl_allowed` tinyint(1) NOT NULL DEFAULT '1',
  `last_crawled_at` datetime DEFAULT NULL,
  `remark` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`source_id`),
  KEY `idx_source_type` (`source_type`),
  KEY `idx_source_name` (`source_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `entity_source_rel` (
  `rel_id` bigint NOT NULL AUTO_INCREMENT,
  `entity_type` enum('site','hero','event','memorial','story','school','resource','activity_plan') NOT NULL,
  `entity_id` bigint NOT NULL,
  `source_id` bigint NOT NULL,
  `source_url` varchar(500) DEFAULT NULL,
  `captured_at` datetime DEFAULT NULL,
  `source_excerpt` text,
  `credibility_score` tinyint NOT NULL DEFAULT '3',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`rel_id`),
  UNIQUE KEY `uk_entity_source` (`entity_type`,`entity_id`,`source_id`,`source_url`(255)),
  KEY `idx_entity_source_lookup` (`entity_type`,`entity_id`),
  KEY `idx_entity_source_source` (`source_id`),
  CONSTRAINT `fk_entity_source_rel_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `hero_person` (
  `hero_id` bigint NOT NULL AUTO_INCREMENT,
  `hero_code` varchar(50) NOT NULL,
  `hero_name` varchar(100) NOT NULL,
  `gender` enum('male','female','unknown') NOT NULL DEFAULT 'unknown',
  `birth_year` smallint DEFAULT NULL,
  `death_year` smallint DEFAULT NULL,
  `birth_date_text` varchar(50) DEFAULT NULL,
  `death_date_text` varchar(50) DEFAULT NULL,
  `native_place_region_id` bigint DEFAULT NULL,
  `native_place_text` varchar(200) DEFAULT NULL,
  `profile_summary` text,
  `main_deeds` longtext,
  `official_url` varchar(500) DEFAULT NULL,
  `review_status` enum('draft','pending','approved','rejected') NOT NULL DEFAULT 'draft',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`hero_id`),
  UNIQUE KEY `uk_hero_code` (`hero_code`),
  KEY `idx_hero_name` (`hero_name`),
  KEY `idx_hero_birth_death` (`birth_year`,`death_year`),
  KEY `idx_hero_region` (`native_place_region_id`),
  CONSTRAINT `fk_hero_native_region` FOREIGN KEY (`native_place_region_id`) REFERENCES `administrative_region` (`region_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `historical_event` (
  `event_id` bigint NOT NULL AUTO_INCREMENT,
  `event_code` varchar(50) NOT NULL,
  `event_name` varchar(200) NOT NULL,
  `primary_region_id` bigint DEFAULT NULL,
  `event_time_text` varchar(100) DEFAULT NULL,
  `start_date` date DEFAULT NULL,
  `end_date` date DEFAULT NULL,
  `start_year` smallint DEFAULT NULL,
  `end_year` smallint DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `latitude` decimal(10,7) DEFAULT NULL,
  `historical_significance` text,
  `event_process` longtext,
  `result_impact` text,
  `official_url` varchar(500) DEFAULT NULL,
  `review_status` enum('draft','pending','approved','rejected') NOT NULL DEFAULT 'draft',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_event_code` (`event_code`),
  KEY `idx_event_name` (`event_name`),
  KEY `idx_event_time` (`start_date`,`end_date`),
  KEY `idx_event_region` (`primary_region_id`),
  KEY `idx_event_geo` (`longitude`,`latitude`),
  CONSTRAINT `fk_event_region` FOREIGN KEY (`primary_region_id`) REFERENCES `administrative_region` (`region_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `school_id` bigint DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `original_filename` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint NOT NULL,
  `sha256` char(64) DEFAULT NULL,
  `object_key` varchar(512) NOT NULL,
  `markdown_object_key` varchar(512) DEFAULT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'PENDING',
  `published_at` datetime DEFAULT NULL,
  `indexed_at` datetime DEFAULT NULL,
  `created_by` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_document_scope_hash` (`school_id`,`sha256`),
  KEY `idx_knowledge_document_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `knowledge_document_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` bigint NOT NULL,
  `sha256` char(64) NOT NULL,
  `object_key` varchar(512) NOT NULL,
  `alt_text` varchar(1000) DEFAULT NULL,
  `description` mediumtext,
  `status` varchar(24) NOT NULL DEFAULT 'PENDING',
  `model` varchar(255) DEFAULT NULL,
  `error_summary` varchar(1000) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_image_document_hash` (`document_id`,`sha256`),
  KEY `idx_knowledge_image_hash` (`sha256`),
  CONSTRAINT `fk_knowledge_image_document` FOREIGN KEY (`document_id`) REFERENCES `knowledge_document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `knowledge_ingest_job` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` bigint NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'PENDING',
  `current_node` varchar(40) NOT NULL DEFAULT 'VALIDATE',
  `retry_count` int NOT NULL DEFAULT '0',
  `error_summary` varchar(1000) DEFAULT NULL,
  `metadata_json` json DEFAULT NULL,
  `restart_from` varchar(40) DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `generation` bigint NOT NULL DEFAULT '1',
  `execution_attempts` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_ingest_job_document` (`document_id`),
  CONSTRAINT `fk_knowledge_ingest_job_document` FOREIGN KEY (`document_id`) REFERENCES `knowledge_document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `knowledge_ingest_outbox` (
  `event_id` char(36) NOT NULL,
  `job_id` bigint NOT NULL,
  `document_id` bigint NOT NULL,
  `generation` bigint NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lease_owner` char(36) DEFAULT NULL,
  `lease_expires_at` datetime DEFAULT NULL,
  `error_summary` varchar(1000) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` datetime DEFAULT NULL,
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_ingest_event_generation` (`job_id`,`generation`),
  KEY `idx_ingest_outbox_ready` (`status`,`next_attempt_at`,`lease_expires_at`),
  CONSTRAINT `fk_ingest_outbox_job` FOREIGN KEY (`job_id`) REFERENCES `knowledge_ingest_job` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `local_edu_resource` (
  `resource_id` bigint NOT NULL AUTO_INCREMENT,
  `resource_code` varchar(50) NOT NULL,
  `resource_name` varchar(200) NOT NULL,
  `resource_category` enum('red_culture','intangible_culture','traditional_culture','local_history','public_culture','labor_education','public_welfare','ecological_civilization','patriotism_base','social_practice','other') NOT NULL DEFAULT 'other',
  `resource_subcategory` varchar(100) DEFAULT NULL,
  `region_id` bigint DEFAULT NULL,
  `county_region_id` bigint DEFAULT NULL,
  `township_region_id` bigint DEFAULT NULL,
  `address` varchar(300) DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `latitude` decimal(10,7) DEFAULT NULL,
  `organization_name` varchar(200) DEFAULT NULL,
  `contact_phone` varchar(50) DEFAULT NULL,
  `opening_time_desc` varchar(255) DEFAULT NULL,
  `reservation_required` tinyint(1) NOT NULL DEFAULT '0',
  `recommended_visit_minutes` int DEFAULT NULL,
  `intro` text,
  `education_value` text,
  `activity_suggestion` longtext,
  `target_grade` varchar(100) DEFAULT NULL,
  `safety_note` text,
  `source_id` bigint DEFAULT NULL,
  `review_status` enum('draft','pending','approved','rejected') NOT NULL DEFAULT 'draft',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `external_provider` varchar(30) DEFAULT NULL,
  `external_place_id` varchar(100) DEFAULT NULL,
  `source_checked_at` datetime DEFAULT NULL,
  PRIMARY KEY (`resource_id`),
  UNIQUE KEY `uk_local_edu_resource_code` (`resource_code`),
  UNIQUE KEY `uk_local_resource_external_place` (`external_provider`,`external_place_id`),
  KEY `fk_local_edu_resource_county_region` (`county_region_id`),
  KEY `fk_local_edu_resource_township_region` (`township_region_id`),
  KEY `fk_local_edu_resource_source` (`source_id`),
  KEY `idx_local_edu_resource_name` (`resource_name`),
  KEY `idx_local_edu_resource_category` (`resource_category`,`resource_subcategory`),
  KEY `idx_local_edu_resource_region` (`region_id`),
  KEY `idx_local_edu_resource_status` (`review_status`,`is_active`),
  KEY `idx_local_edu_resource_geo` (`longitude`,`latitude`),
  CONSTRAINT `fk_local_edu_resource_county_region` FOREIGN KEY (`county_region_id`) REFERENCES `administrative_region` (`region_id`),
  CONSTRAINT `fk_local_edu_resource_region` FOREIGN KEY (`region_id`) REFERENCES `administrative_region` (`region_id`),
  CONSTRAINT `fk_local_edu_resource_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`),
  CONSTRAINT `fk_local_edu_resource_township_region` FOREIGN KEY (`township_region_id`) REFERENCES `administrative_region` (`region_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `memorial_hall` (
  `memorial_id` bigint NOT NULL AUTO_INCREMENT,
  `memorial_code` varchar(50) NOT NULL,
  `memorial_name` varchar(200) NOT NULL,
  `region_id` bigint DEFAULT NULL,
  `address` varchar(300) DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `latitude` decimal(10,7) DEFAULT NULL,
  `exhibition_content` longtext,
  `intro` text,
  `opening_time_desc` varchar(255) DEFAULT NULL,
  `ticket_info` varchar(255) DEFAULT NULL,
  `contact_phone` varchar(50) DEFAULT NULL,
  `official_url` varchar(500) DEFAULT NULL,
  `review_status` enum('draft','pending','approved','rejected') NOT NULL DEFAULT 'draft',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`memorial_id`),
  UNIQUE KEY `uk_memorial_code` (`memorial_code`),
  KEY `idx_memorial_name` (`memorial_name`),
  KEY `idx_memorial_region` (`region_id`),
  KEY `idx_memorial_geo` (`longitude`,`latitude`),
  CONSTRAINT `fk_memorial_region` FOREIGN KEY (`region_id`) REFERENCES `administrative_region` (`region_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `memorial_hero_rel` (
  `rel_id` bigint NOT NULL AUTO_INCREMENT,
  `memorial_id` bigint NOT NULL,
  `hero_id` bigint NOT NULL,
  `relation_type` enum('commemorates','exhibits','related_to') NOT NULL DEFAULT 'commemorates',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`rel_id`),
  UNIQUE KEY `uk_memorial_hero` (`memorial_id`,`hero_id`,`relation_type`),
  KEY `idx_memorial_hero_hero` (`hero_id`),
  CONSTRAINT `fk_memorial_hero_hero` FOREIGN KEY (`hero_id`) REFERENCES `hero_person` (`hero_id`),
  CONSTRAINT `fk_memorial_hero_memorial` FOREIGN KEY (`memorial_id`) REFERENCES `memorial_hall` (`memorial_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `rag_index_job` (
  `job_id` bigint NOT NULL AUTO_INCREMENT COMMENT '索引任务ID',
  `job_type` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务类型：full/resource/chunk',
  `target_entity_type` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标实体类型',
  `target_entity_id` bigint DEFAULT NULL COMMENT '目标实体ID',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending' COMMENT '状态：pending/running/success/failed',
  `total_chunks` int NOT NULL DEFAULT '0' COMMENT '总分块数',
  `indexed_chunks` int NOT NULL DEFAULT '0' COMMENT '成功分块数',
  `failed_chunks` int NOT NULL DEFAULT '0' COMMENT '失败分块数',
  `started_by` bigint DEFAULT NULL COMMENT '操作人账号ID',
  `started_at` datetime DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime DEFAULT NULL COMMENT '结束时间',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT '失败原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`job_id`),
  KEY `idx_rag_index_job_status` (`status`),
  KEY `idx_rag_index_job_target` (`target_entity_type`,`target_entity_id`),
  KEY `idx_rag_index_job_started_by` (`started_by`),
  KEY `idx_rag_index_job_started_at` (`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG索引任务表';

  CREATE TABLE `rag_retrieval_test_log` (
  `test_id` bigint NOT NULL AUTO_INCREMENT COMMENT '测试ID',
  `query_text` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '管理员输入的问题',
  `scope_type` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'GLOBAL' COMMENT '范围类型：SCHOOL/RESOURCE/REGION/GLOBAL',
  `scope_id` bigint DEFAULT NULL COMMENT '范围ID',
  `top_k` int NOT NULL DEFAULT '5' COMMENT '召回数量',
  `retrieval_status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'success' COMMENT '检索状态：success/empty/failed',
  `result_json` json DEFAULT NULL COMMENT '召回片段、分数、来源、引用编号',
  `created_by` bigint DEFAULT NULL COMMENT '测试人账号ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '测试时间',
  PRIMARY KEY (`test_id`),
  KEY `idx_retrieval_test_scope` (`scope_type`,`scope_id`),
  KEY `idx_retrieval_test_status` (`retrieval_status`),
  KEY `idx_retrieval_test_created_by` (`created_by`),
  KEY `idx_retrieval_test_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG检索测试日志表';

  CREATE TABLE `rag_web_source` (
  `source_id` bigint NOT NULL AUTO_INCREMENT,
  `display_name` varchar(120) NOT NULL,
  `domain` varchar(255) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `sort_order` int NOT NULL DEFAULT '100',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`source_id`),
  UNIQUE KEY `uk_rag_web_source_domain` (`domain`),
  KEY `idx_rag_web_source_enabled_sort` (`enabled`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG 权威 Web 检索域名白名单';

  CREATE TABLE `red_site` (
  `site_id` bigint NOT NULL AUTO_INCREMENT,
  `site_code` varchar(50) NOT NULL,
  `site_name` varchar(200) NOT NULL,
  `region_id` bigint DEFAULT NULL,
  `address` varchar(300) DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `latitude` decimal(10,7) DEFAULT NULL,
  `established_date` date DEFAULT NULL,
  `established_year` smallint DEFAULT NULL,
  `site_level` enum('national','provincial','municipal','county','other') NOT NULL DEFAULT 'other',
  `protection_level` varchar(100) DEFAULT NULL,
  `historical_background` text,
  `intro` text,
  `opening_time_desc` varchar(255) DEFAULT NULL,
  `suggested_visit_minutes` int DEFAULT NULL,
  `official_url` varchar(500) DEFAULT NULL,
  `review_status` enum('draft','pending','approved','rejected') NOT NULL DEFAULT 'draft',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`site_id`),
  UNIQUE KEY `uk_site_code` (`site_code`),
  KEY `idx_site_name` (`site_name`),
  KEY `idx_site_region` (`region_id`),
  KEY `idx_site_status` (`review_status`,`is_active`),
  KEY `idx_site_geo` (`longitude`,`latitude`),
  CONSTRAINT `fk_site_region` FOREIGN KEY (`region_id`) REFERENCES `administrative_region` (`region_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `red_story` (
  `story_id` bigint NOT NULL AUTO_INCREMENT,
  `story_code` varchar(50) NOT NULL,
  `story_title` varchar(200) NOT NULL,
  `related_region_id` bigint DEFAULT NULL,
  `age_group` enum('primary','middle','high','college','general') NOT NULL DEFAULT 'general',
  `summary` text,
  `story_content` longtext NOT NULL,
  `source_id` bigint DEFAULT NULL,
  `review_status` enum('draft','pending','approved','rejected') NOT NULL DEFAULT 'draft',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`story_id`),
  UNIQUE KEY `uk_story_code` (`story_code`),
  KEY `fk_story_source` (`source_id`),
  KEY `idx_story_title` (`story_title`),
  KEY `idx_story_region` (`related_region_id`),
  CONSTRAINT `fk_story_region` FOREIGN KEY (`related_region_id`) REFERENCES `administrative_region` (`region_id`),
  CONSTRAINT `fk_story_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `resource_media` (
  `media_id` bigint NOT NULL AUTO_INCREMENT,
  `entity_type` enum('site','hero','event','memorial','story','school','resource','activity_plan') NOT NULL,
  `entity_id` bigint NOT NULL,
  `media_type` enum('image','video','audio','document','link') NOT NULL DEFAULT 'image',
  `media_title` varchar(200) DEFAULT NULL,
  `media_url` varchar(500) NOT NULL,
  `cover_url` varchar(500) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `source_id` bigint DEFAULT NULL,
  `copyright_note` varchar(255) DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `is_primary` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`media_id`),
  KEY `fk_media_source` (`source_id`),
  KEY `idx_media_entity` (`entity_type`,`entity_id`),
  KEY `idx_media_type` (`media_type`),
  CONSTRAINT `fk_media_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `school` (
  `school_id` bigint NOT NULL AUTO_INCREMENT,
  `school_code` varchar(50) NOT NULL,
  `school_name` varchar(200) NOT NULL,
  `school_alias` varchar(200) DEFAULT NULL,
  `region_id` bigint DEFAULT NULL,
  `province_region_id` bigint DEFAULT NULL,
  `city_region_id` bigint DEFAULT NULL,
  `county_region_id` bigint DEFAULT NULL,
  `township_region_id` bigint DEFAULT NULL,
  `village_region_id` bigint DEFAULT NULL,
  `school_level` enum('kindergarten','primary','junior','senior','nine_year','twelve_year','vocational','special','other') NOT NULL DEFAULT 'primary',
  `school_type` varchar(100) DEFAULT NULL,
  `school_nature` enum('public','private','other') NOT NULL DEFAULT 'public',
  `is_rural_school` tinyint(1) NOT NULL DEFAULT '1',
  `is_teaching_point` tinyint(1) NOT NULL DEFAULT '0',
  `address` varchar(300) DEFAULT NULL,
  `postcode` varchar(20) DEFAULT NULL,
  `contact_phone` varchar(50) DEFAULT NULL,
  `principal_name` varchar(100) DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `latitude` decimal(10,7) DEFAULT NULL,
  `geo_source_type` enum('amap_poi','manual','school_official','government_doc','satellite_fix','other') NOT NULL DEFAULT 'government_doc',
  `poi_name` varchar(200) DEFAULT NULL,
  `poi_address` varchar(300) DEFAULT NULL,
  `poi_type` varchar(200) DEFAULT NULL,
  `geo_confidence` enum('high','medium','low','unknown') NOT NULL DEFAULT 'unknown',
  `geo_verified` tinyint(1) NOT NULL DEFAULT '0',
  `intro` text,
  `source_id` bigint DEFAULT NULL,
  `review_status` enum('draft','pending','approved','rejected') NOT NULL DEFAULT 'draft',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `logo_url` varchar(255) DEFAULT NULL COMMENT '学校Logo地址',
  `website_url` varchar(255) DEFAULT NULL COMMENT '学校官网地址',
  `contact_email` varchar(100) DEFAULT NULL COMMENT '联系邮箱',
  `admin_contact_name` varchar(100) DEFAULT NULL COMMENT '管理员联系人姓名',
  `default_discovery_radius_km` decimal(8,2) DEFAULT NULL COMMENT '默认周边资源发现半径',
  `resource_calc_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用周边资源自动计算',
  `last_resource_calc_at` datetime DEFAULT NULL COMMENT '最后一次资源计算时间',
  PRIMARY KEY (`school_id`),
  UNIQUE KEY `uk_school_code` (`school_code`),
  KEY `fk_school_village_region` (`village_region_id`),
  KEY `fk_school_source` (`source_id`),
  KEY `idx_school_name` (`school_name`),
  KEY `idx_school_region` (`region_id`),
  KEY `idx_school_county` (`county_region_id`),
  KEY `idx_school_township` (`township_region_id`),
  KEY `idx_school_status` (`review_status`,`is_active`),
  KEY `idx_school_geo` (`longitude`,`latitude`),
  KEY `idx_school_province_region_id` (`province_region_id`),
  KEY `idx_school_city_region_id` (`city_region_id`),
  CONSTRAINT `fk_school_county_region` FOREIGN KEY (`county_region_id`) REFERENCES `administrative_region` (`region_id`),
  CONSTRAINT `fk_school_region` FOREIGN KEY (`region_id`) REFERENCES `administrative_region` (`region_id`),
  CONSTRAINT `fk_school_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`),
  CONSTRAINT `fk_school_township_region` FOREIGN KEY (`township_region_id`) REFERENCES `administrative_region` (`region_id`),
  CONSTRAINT `fk_school_village_region` FOREIGN KEY (`village_region_id`) REFERENCES `administrative_region` (`region_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `school_geo_record` (
  `geo_record_id` bigint NOT NULL AUTO_INCREMENT,
  `school_id` bigint NOT NULL,
  `longitude` decimal(10,7) NOT NULL,
  `latitude` decimal(10,7) NOT NULL,
  `source_type` enum('amap_poi','manual','school_official','government_doc','satellite_fix','other') NOT NULL DEFAULT 'amap_poi',
  `poi_name` varchar(200) DEFAULT NULL,
  `poi_address` varchar(300) DEFAULT NULL,
  `poi_type` varchar(200) DEFAULT NULL,
  `confidence_level` enum('high','medium','low','unknown') NOT NULL DEFAULT 'unknown',
  `is_manual_reviewed` tinyint(1) NOT NULL DEFAULT '0',
  `review_result` enum('pending','confirmed','corrected','rejected') NOT NULL DEFAULT 'pending',
  `reviewer_name` varchar(100) DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `is_current` tinyint(1) NOT NULL DEFAULT '1',
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`geo_record_id`),
  KEY `idx_school_geo_record_school` (`school_id`),
  KEY `idx_school_geo_record_current` (`school_id`,`is_current`),
  CONSTRAINT `fk_school_geo_record_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `school_resource_calc_run` (
  `run_id` bigint NOT NULL AUTO_INCREMENT COMMENT '计算批次ID',
  `school_id` bigint NOT NULL COMMENT '学校ID',
  `radius_km` decimal(8,2) DEFAULT NULL COMMENT '计算半径',
  `calc_method` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'distance' COMMENT '计算方式：distance/amap/ai/manual',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'running' COMMENT '状态：running/success/failed',
  `candidate_count` int NOT NULL DEFAULT '0' COMMENT '候选资源数',
  `linked_count` int NOT NULL DEFAULT '0' COMMENT '形成关联数',
  `started_at` datetime DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime DEFAULT NULL COMMENT '结束时间',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT '失败原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`run_id`),
  KEY `idx_resource_calc_school_id` (`school_id`),
  KEY `idx_resource_calc_status` (`status`),
  KEY `idx_resource_calc_started_at` (`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学校周边资源计算批次表';

  CREATE TABLE `school_resource_rel` (
  `rel_id` bigint NOT NULL AUTO_INCREMENT,
  `school_id` bigint NOT NULL,
  `resource_id` bigint NOT NULL,
  `relation_type` enum('nearby','cooperation','practice','curriculum_support','volunteer_base','research_route','other') NOT NULL DEFAULT 'nearby',
  `distance_meters` int DEFAULT NULL,
  `recommended_travel_mode` enum('walk','bike','bus','drive','mixed','unknown') NOT NULL DEFAULT 'unknown',
  `estimated_duration_minutes` int DEFAULT NULL,
  `reachability_level` enum('near','medium','far','very_far','unknown') NOT NULL DEFAULT 'unknown',
  `priority_level` tinyint NOT NULL DEFAULT '3',
  `education_theme_summary` varchar(255) DEFAULT NULL,
  `source_id` bigint DEFAULT NULL,
  `review_status` enum('draft','pending','approved','rejected') NOT NULL DEFAULT 'draft',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `calc_run_id` bigint DEFAULT NULL COMMENT '计算批次ID',
  `calc_method` varchar(50) DEFAULT NULL COMMENT '计算方式：distance/amap/ai/manual',
  `match_score` decimal(10,4) DEFAULT NULL COMMENT '匹配分数',
  `route_distance_meters` int DEFAULT NULL COMMENT '路线距离，单位米',
  `route_duration_minutes` int DEFAULT NULL COMMENT '路线耗时，单位分钟',
  `calculated_at` datetime DEFAULT NULL COMMENT '计算时间',
  `manual_locked` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否人工锁定，防止自动刷新覆盖',
  `last_verified_at` datetime DEFAULT NULL COMMENT '最后核验时间',
  PRIMARY KEY (`rel_id`),
  UNIQUE KEY `uk_school_resource_rel` (`school_id`,`resource_id`,`relation_type`),
  KEY `fk_school_resource_rel_source` (`source_id`),
  KEY `idx_school_resource_rel_school` (`school_id`),
  KEY `idx_school_resource_rel_resource` (`resource_id`),
  KEY `idx_school_resource_rel_distance` (`distance_meters`),
  KEY `idx_school_resource_rel_calc_run_id` (`calc_run_id`),
  KEY `idx_school_resource_rel_manual_locked` (`manual_locked`),
  CONSTRAINT `fk_school_resource_rel_resource` FOREIGN KEY (`resource_id`) REFERENCES `local_edu_resource` (`resource_id`),
  CONSTRAINT `fk_school_resource_rel_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`),
  CONSTRAINT `fk_school_resource_rel_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `school_user_account` (
  `account_id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(100) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `role_code` varchar(50) NOT NULL DEFAULT 'school_admin',
  `school_id` bigint DEFAULT NULL,
  `display_name` varchar(120) DEFAULT NULL,
  `contact_name` varchar(100) DEFAULT NULL,
  `contact_phone` varchar(50) DEFAULT NULL,
  `real_name` varchar(100) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `account_type` varchar(30) DEFAULT NULL,
  `force_password_change` tinyint(1) NOT NULL DEFAULT '0',
  `password_updated_at` datetime DEFAULT NULL,
  `status` enum('pending_activation','active','disabled') NOT NULL DEFAULT 'active',
  `last_login_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `avatar_url` varchar(255) DEFAULT NULL COMMENT '头像地址',
  `employee_no` varchar(50) DEFAULT NULL COMMENT '员工号/教师工号',
  `student_no` varchar(50) DEFAULT NULL COMMENT '学生学号',
  `last_login_ip` varchar(45) DEFAULT NULL COMMENT '最后登录IP',
  PRIMARY KEY (`account_id`),
  UNIQUE KEY `uk_school_user_account_username` (`username`),
  KEY `idx_school_user_account_school_id` (`school_id`),
  KEY `idx_school_user_account_status` (`status`),
  KEY `idx_school_user_account_role` (`role_code`),
  CONSTRAINT `fk_school_user_account_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `site_event_rel` (
  `rel_id` bigint NOT NULL AUTO_INCREMENT,
  `site_id` bigint NOT NULL,
  `event_id` bigint NOT NULL,
  `relation_type` enum('occurred_at','related_to','memorialized_at') NOT NULL DEFAULT 'related_to',
  `importance_level` tinyint NOT NULL DEFAULT '3',
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`rel_id`),
  UNIQUE KEY `uk_site_event` (`site_id`,`event_id`,`relation_type`),
  KEY `idx_site_event_event` (`event_id`),
  KEY `idx_site_event_site` (`site_id`),
  CONSTRAINT `fk_site_event_event` FOREIGN KEY (`event_id`) REFERENCES `historical_event` (`event_id`),
  CONSTRAINT `fk_site_event_site` FOREIGN KEY (`site_id`) REFERENCES `red_site` (`site_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `site_hero_rel` (
  `rel_id` bigint NOT NULL AUTO_INCREMENT,
  `site_id` bigint NOT NULL,
  `hero_id` bigint NOT NULL,
  `relation_type` enum('born_in','fought_in','memorialized','visited','related_to') NOT NULL DEFAULT 'related_to',
  `importance_level` tinyint NOT NULL DEFAULT '3',
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`rel_id`),
  UNIQUE KEY `uk_site_hero` (`site_id`,`hero_id`,`relation_type`),
  KEY `idx_site_hero_hero` (`hero_id`),
  KEY `idx_site_hero_site` (`site_id`),
  CONSTRAINT `fk_site_hero_hero` FOREIGN KEY (`hero_id`) REFERENCES `hero_person` (`hero_id`),
  CONSTRAINT `fk_site_hero_site` FOREIGN KEY (`site_id`) REFERENCES `red_site` (`site_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `story_entity_rel` (
  `rel_id` bigint NOT NULL AUTO_INCREMENT,
  `story_id` bigint NOT NULL,
  `entity_type` enum('site','hero','event','memorial','school','resource') NOT NULL,
  `entity_id` bigint NOT NULL,
  `relation_type` enum('about','mentions','teaches') NOT NULL DEFAULT 'about',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`rel_id`),
  UNIQUE KEY `uk_story_entity` (`story_id`,`entity_type`,`entity_id`,`relation_type`),
  KEY `idx_story_entity_lookup` (`entity_type`,`entity_id`),
  CONSTRAINT `fk_story_entity_story` FOREIGN KEY (`story_id`) REFERENCES `red_story` (`story_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `student_profile` (
  `student_id` bigint NOT NULL AUTO_INCREMENT COMMENT '学生ID',
  `account_id` bigint NOT NULL COMMENT '关联 school_user_account',
  `school_id` bigint NOT NULL COMMENT '所属学校',
  `student_no` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '学号',
  `student_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '学生姓名',
  `grade_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '年级',
  `enrollment_year` int DEFAULT NULL COMMENT '入学年份',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态：active/graduated/transferred',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `profile_id` bigint DEFAULT NULL COMMENT '统一档案ID',
  PRIMARY KEY (`student_id`),
  UNIQUE KEY `uk_student_account_id` (`account_id`),
  UNIQUE KEY `uk_student_school_no` (`school_id`,`student_no`),
  KEY `idx_student_school_id` (`school_id`),
  KEY `idx_student_grade_name` (`grade_name`),
  KEY `idx_student_status` (`status`),
  KEY `idx_student_profile_id` (`profile_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学生档案表';

  CREATE TABLE `student_resource_browse_history` (
  `browse_id` bigint NOT NULL AUTO_INCREMENT,
  `student_id` bigint NOT NULL,
  `resource_id` bigint NOT NULL,
  `viewed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `view_count` int NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`browse_id`),
  UNIQUE KEY `uk_student_resource` (`student_id`,`resource_id`),
  KEY `idx_student_browse_time` (`student_id`,`viewed_at`),
  KEY `fk_student_browse_resource` (`resource_id`),
  CONSTRAINT `fk_student_browse_resource` FOREIGN KEY (`resource_id`) REFERENCES `local_edu_resource` (`resource_id`),
  CONSTRAINT `fk_student_browse_student` FOREIGN KEY (`student_id`) REFERENCES `student_profile` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `student_task_attachment` (
  `attachment_id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `original_filename` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `storage_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_type` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `file_size` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`attachment_id`),
  UNIQUE KEY `uk_submission_storage_key` (`storage_key`),
  KEY `idx_submission_attachment` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE `student_task_progress` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending',
  `completed_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_student_task_progress` (`task_id`,`student_id`),
  KEY `idx_student_task_progress_student` (`student_id`),
  KEY `idx_student_task_progress_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE `student_task_review` (
  `review_id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `teacher_id` bigint NOT NULL,
  `review_action` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `comment` text COLLATE utf8mb4_unicode_ci,
  `grade` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reviewed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`review_id`),
  KEY `idx_task_review_submission` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE `student_task_submission` (
  `submission_id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `version_no` int NOT NULL,
  `content` text COLLATE utf8mb4_unicode_ci,
  `selected_resource_ids` text COLLATE utf8mb4_unicode_ci,
  `submitted_at` datetime DEFAULT NULL,
  `is_late` tinyint(1) NOT NULL DEFAULT '0',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'draft',
  `is_current` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`submission_id`),
  UNIQUE KEY `uk_submission_version` (`task_id`,`student_id`,`version_no`),
  KEY `idx_submission_task_student` (`task_id`,`student_id`),
  KEY `idx_submission_current` (`task_id`,`student_id`,`is_current`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE `sys_account_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  `account_id` bigint NOT NULL COMMENT '账号ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `data_scope` varchar(50) NOT NULL DEFAULT 'school' COMMENT '数据范围：all/school/class/self',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_role` (`account_id`,`role_id`),
  KEY `idx_account_role_account_id` (`account_id`),
  KEY `idx_account_role_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号角色关系表';

  CREATE TABLE `sys_permission` (
  `permission_id` bigint NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  `permission_code` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '权限编码，如 admin.school.read',
  `permission_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '权限名称',
  `permission_type` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'api' COMMENT '权限类型：menu/api/button/data',
  `resource_path` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '页面路径或接口路径',
  `parent_id` bigint DEFAULT NULL COMMENT '上级权限ID',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`permission_id`),
  UNIQUE KEY `uk_sys_permission_code` (`permission_code`),
  KEY `idx_sys_permission_parent_id` (`parent_id`),
  KEY `idx_sys_permission_type` (`permission_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限定义表';

  CREATE TABLE `sys_role` (
  `role_id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色编码，如 platform_admin、teacher、student',
  `role_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色名称',
  `role_scope` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'school' COMMENT '角色范围：platform/school/class',
  `is_system` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否系统内置角色',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`role_id`),
  UNIQUE KEY `uk_sys_role_code` (`role_code`),
  KEY `idx_sys_role_scope` (`role_scope`),
  KEY `idx_sys_role_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色定义表';

  CREATE TABLE `sys_role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `permission_id` bigint NOT NULL COMMENT '权限ID',
  `data_scope` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'school' COMMENT '数据范围：all/school/class/self',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_id`,`permission_id`),
  KEY `idx_role_permission_role_id` (`role_id`),
  KEY `idx_role_permission_permission_id` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关系表';

  CREATE TABLE `tag_info` (
  `tag_id` bigint NOT NULL AUTO_INCREMENT,
  `tag_name` varchar(100) NOT NULL,
  `tag_type` enum('theme','period','region','education','route','other') NOT NULL DEFAULT 'other',
  `description` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`tag_id`),
  UNIQUE KEY `uk_tag_name_type` (`tag_name`,`tag_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `task_resource_rel` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `resource_id` bigint NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_resource` (`task_id`,`resource_id`),
  KEY `idx_task_resource_resource` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE `teacher_profile` (
  `teacher_id` bigint NOT NULL AUTO_INCREMENT COMMENT '教师ID',
  `account_id` bigint NOT NULL COMMENT '关联 school_user_account',
  `school_id` bigint NOT NULL COMMENT '所属学校',
  `teacher_no` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '教师工号',
  `teacher_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '教师姓名',
  `title` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '职称/岗位',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive/left',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `profile_id` bigint DEFAULT NULL COMMENT '统一档案ID',
  PRIMARY KEY (`teacher_id`),
  UNIQUE KEY `uk_teacher_account_id` (`account_id`),
  UNIQUE KEY `uk_teacher_school_no` (`school_id`,`teacher_no`),
  KEY `idx_teacher_school_id` (`school_id`),
  KEY `idx_teacher_status` (`status`),
  KEY `idx_teacher_profile_id` (`profile_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='教师档案表';

  CREATE TABLE `teacher_registration_invite` (
  `invite_id` bigint NOT NULL AUTO_INCREMENT,
  `school_id` bigint NOT NULL,
  `code_hash` char(64) NOT NULL,
  `status` enum('active','revoked') NOT NULL DEFAULT 'active',
  `expires_at` datetime NOT NULL,
  `max_uses` int NOT NULL DEFAULT '50',
  `used_count` int NOT NULL DEFAULT '0',
  `created_by_account_id` bigint NOT NULL,
  `revoked_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`invite_id`),
  UNIQUE KEY `uk_teacher_registration_invite_hash` (`code_hash`),
  KEY `idx_teacher_registration_invite_school_status` (`school_id`,`status`,`expires_at`),
  KEY `fk_teacher_registration_invite_creator` (`created_by_account_id`),
  CONSTRAINT `fk_teacher_registration_invite_creator` FOREIGN KEY (`created_by_account_id`) REFERENCES `school_user_account` (`account_id`),
  CONSTRAINT `fk_teacher_registration_invite_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`),
  CONSTRAINT `chk_teacher_registration_invite_uses` CHECK (((`max_uses` > 0) and (`used_count` >= 0) and (`used_count` <= `max_uses`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `teacher_resource_favorite` (
  `favorite_id` bigint NOT NULL AUTO_INCREMENT,
  `teacher_id` bigint NOT NULL,
  `resource_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`favorite_id`),
  UNIQUE KEY `uk_teacher_resource_favorite` (`teacher_id`,`resource_id`),
  KEY `idx_teacher_resource_favorite_resource` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE `teaching_activity_plan` (
  `plan_id` bigint NOT NULL AUTO_INCREMENT,
  `plan_code` varchar(50) NOT NULL,
  `school_id` bigint NOT NULL,
  `resource_id` bigint DEFAULT NULL,
  `theme` varchar(200) NOT NULL,
  `activity_type` enum('classroom','field_trip','volunteer_service','research_study','labor_practice','club_activity','school_based_course','other') NOT NULL DEFAULT 'classroom',
  `suitable_grade` varchar(100) DEFAULT NULL,
  `objective_text` text,
  `activity_content` longtext NOT NULL,
  `preparation_text` text,
  `safety_text` text,
  `expected_outcome` text,
  `duration_minutes` int DEFAULT NULL,
  `source_id` bigint DEFAULT NULL,
  `owner_account_id` bigint DEFAULT NULL,
  `plan_payload` longtext,
  `generation_source` varchar(30) DEFAULT NULL,
  `ai_run_id` bigint DEFAULT NULL,
  `published_status` varchar(20) NOT NULL DEFAULT 'draft',
  `review_status` enum('draft','pending','approved','adopted','rejected') NOT NULL DEFAULT 'draft',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `class_id` bigint DEFAULT NULL COMMENT '发布班级ID',
  `published_at` datetime DEFAULT NULL COMMENT '发布时间',
  PRIMARY KEY (`plan_id`),
  UNIQUE KEY `uk_teaching_activity_plan_code` (`plan_code`),
  KEY `fk_teaching_activity_plan_source` (`source_id`),
  KEY `idx_teaching_activity_plan_school` (`school_id`),
  KEY `idx_teaching_activity_plan_resource` (`resource_id`),
  KEY `idx_teaching_activity_plan_theme` (`theme`),
  KEY `idx_teaching_activity_plan_status` (`review_status`,`is_active`),
  KEY `idx_teaching_activity_plan_owner` (`owner_account_id`),
  KEY `idx_teaching_activity_plan_class` (`class_id`),
  KEY `idx_teaching_activity_plan_publish` (`published_status`),
  CONSTRAINT `fk_teaching_activity_plan_resource` FOREIGN KEY (`resource_id`) REFERENCES `local_edu_resource` (`resource_id`),
  CONSTRAINT `fk_teaching_activity_plan_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`),
  CONSTRAINT `fk_teaching_activity_plan_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `teaching_activity_plan_resource` (
  `plan_id` bigint NOT NULL,
  `resource_id` bigint NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `is_primary` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`plan_id`,`resource_id`),
  KEY `idx_plan_resource_resource` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `user_profile` (
  `profile_id` bigint NOT NULL AUTO_INCREMENT COMMENT '统一档案ID',
  `account_id` bigint NOT NULL COMMENT '关联账号ID',
  `profile_type` varchar(30) NOT NULL COMMENT '档案类型：admin/teacher/student/other',
  `real_name` varchar(100) NOT NULL COMMENT '真实姓名',
  `gender` varchar(20) DEFAULT NULL COMMENT '性别',
  `phone` varchar(50) DEFAULT NULL COMMENT '联系电话',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `school_id` bigint DEFAULT NULL COMMENT '所属学校ID',
  `status` varchar(20) NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive/left/graduated/transferred',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`profile_id`),
  UNIQUE KEY `uk_user_profile_account_id` (`account_id`),
  KEY `idx_user_profile_type` (`profile_type`),
  KEY `idx_user_profile_school_id` (`school_id`),
  KEY `idx_user_profile_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一用户档案表';

  CREATE TABLE `ai_teaching_plan_generation` (
  `generation_id` bigint NOT NULL AUTO_INCREMENT,
  `school_id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `actor_role` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `thread_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `grade` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `theme` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `activity_type` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `duration_minutes` int DEFAULT NULL,
  `practice_required` tinyint(1) DEFAULT NULL,
  `generation_status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `retrieval_status` varchar(24) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `llm_provider` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `llm_model` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prompt_version` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prompt_run_id` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prompt_experiment` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prompt_variant` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `request_json` json NOT NULL,
  `response_json` json NOT NULL,
  `saved_plan_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`generation_id`),
  KEY `idx_ai_plan_generation_school_time` (`school_id`,`created_at`),
  KEY `idx_ai_plan_generation_account_time` (`account_id`,`created_at`),
  KEY `idx_ai_plan_generation_role_status_time` (`actor_role`,`generation_status`,`created_at`),
  KEY `idx_ai_plan_generation_saved_plan` (`saved_plan_id`),
  CONSTRAINT `fk_ai_plan_generation_saved_plan` FOREIGN KEY (`saved_plan_id`) REFERENCES `teaching_activity_plan` (`plan_id`),
  CONSTRAINT `fk_ai_plan_generation_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE `auth_refresh_token` (
  `token_id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL,
  `token_hash` char(64) NOT NULL,
  `token_family_id` varchar(64) NOT NULL,
  `issued_at` datetime NOT NULL,
  `expires_at` datetime NOT NULL,
  `rotated_at` datetime DEFAULT NULL,
  `revoked_at` datetime DEFAULT NULL,
  `revoke_reason` varchar(100) DEFAULT NULL,
  `user_agent` varchar(512) DEFAULT NULL,
  `client_ip` varchar(45) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`token_id`),
  UNIQUE KEY `uk_auth_refresh_token_hash` (`token_hash`),
  KEY `idx_auth_refresh_token_account_status` (`account_id`,`revoked_at`,`expires_at`),
  KEY `idx_auth_refresh_token_family` (`token_family_id`),
  CONSTRAINT `fk_auth_refresh_token_account` FOREIGN KEY (`account_id`) REFERENCES `school_user_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `content_chunk` (
  `chunk_id` bigint NOT NULL AUTO_INCREMENT,
  `entity_type` enum('site','hero','event','memorial','story','school','resource','activity_plan') NOT NULL,
  `entity_id` bigint NOT NULL,
  `chunk_title` varchar(200) DEFAULT NULL,
  `chunk_text` longtext NOT NULL,
  `chunk_index` int NOT NULL DEFAULT '1',
  `source_id` bigint DEFAULT NULL,
  `token_count` int DEFAULT NULL,
  `embedding_status` enum('pending','done','failed') NOT NULL DEFAULT 'pending',
  `retrieval_text` longtext,
  `embedding_hash` char(64) DEFAULT NULL,
  `embedding_model` varchar(100) DEFAULT NULL,
  `embedding_dimensions` int DEFAULT NULL,
  `embedding_index_version` varchar(32) DEFAULT NULL,
  `embedded_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `content_hash` varchar(64) DEFAULT NULL COMMENT '内容哈希',
  `qdrant_collection` varchar(100) DEFAULT NULL COMMENT 'Qdrant集合名',
  `vector_point_id` varchar(100) DEFAULT NULL COMMENT '向量库点位ID',
  `embedding_error` text COMMENT '向量化失败原因',
  PRIMARY KEY (`chunk_id`),
  UNIQUE KEY `uk_content_chunk` (`entity_type`,`entity_id`,`chunk_index`),
  KEY `fk_chunk_source` (`source_id`),
  KEY `idx_chunk_entity` (`entity_type`,`entity_id`),
  KEY `idx_content_chunk_content_hash` (`content_hash`),
  KEY `idx_content_chunk_vector_point_id` (`vector_point_id`),
  FULLTEXT KEY `ft_chunk_text` (`chunk_title`,`chunk_text`,`retrieval_text`) /*!50100 WITH PARSER `ngram` */ ,
  CONSTRAINT `fk_chunk_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `entity_tag_rel` (
  `rel_id` bigint NOT NULL AUTO_INCREMENT,
  `entity_type` enum('site','hero','event','memorial','story','school','resource','activity_plan') NOT NULL,
  `entity_id` bigint NOT NULL,
  `tag_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`rel_id`),
  UNIQUE KEY `uk_entity_tag` (`entity_type`,`entity_id`,`tag_id`),
  KEY `fk_entity_tag_rel_tag` (`tag_id`),
  KEY `idx_entity_tag_lookup` (`entity_type`,`entity_id`),
  CONSTRAINT `fk_entity_tag_rel_tag` FOREIGN KEY (`tag_id`) REFERENCES `tag_info` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `event_hero_rel` (
  `rel_id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `hero_id` bigint NOT NULL,
  `relation_type` enum('participant','leader','witness','martyr','related_to') NOT NULL DEFAULT 'participant',
  `contribution_text` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`rel_id`),
  UNIQUE KEY `uk_event_hero` (`event_id`,`hero_id`,`relation_type`),
  KEY `idx_event_hero_hero` (`hero_id`),
  KEY `idx_event_hero_event` (`event_id`),
  CONSTRAINT `fk_event_hero_event` FOREIGN KEY (`event_id`) REFERENCES `historical_event` (`event_id`),
  CONSTRAINT `fk_event_hero_hero` FOREIGN KEY (`hero_id`) REFERENCES `hero_person` (`hero_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `knowledge_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` bigint NOT NULL,
  `chunk_order` int NOT NULL,
  `title_path` varchar(1000) DEFAULT NULL,
  `content` mediumtext NOT NULL,
  `token_count` int NOT NULL,
  `subject` varchar(255) DEFAULT NULL,
  `subject_type` varchar(100) DEFAULT NULL,
  `tags` json DEFAULT NULL,
  `qdrant_point_id` varchar(64) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_chunk_document_order` (`document_id`,`chunk_order`),
  KEY `idx_knowledge_chunk_document` (`document_id`),
  CONSTRAINT `fk_knowledge_chunk_document` FOREIGN KEY (`document_id`) REFERENCES `knowledge_document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `memorial_event_rel` (
  `rel_id` bigint NOT NULL AUTO_INCREMENT,
  `memorial_id` bigint NOT NULL,
  `event_id` bigint NOT NULL,
  `relation_type` enum('commemorates','exhibits','related_to') NOT NULL DEFAULT 'commemorates',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`rel_id`),
  UNIQUE KEY `uk_memorial_event` (`memorial_id`,`event_id`,`relation_type`),
  KEY `idx_memorial_event_event` (`event_id`),
  CONSTRAINT `fk_memorial_event_event` FOREIGN KEY (`event_id`) REFERENCES `historical_event` (`event_id`),
  CONSTRAINT `fk_memorial_event_memorial` FOREIGN KEY (`memorial_id`) REFERENCES `memorial_hall` (`memorial_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `memorial_site_rel` (
  `rel_id` bigint NOT NULL AUTO_INCREMENT,
  `memorial_id` bigint NOT NULL,
  `site_id` bigint NOT NULL,
  `relation_type` enum('located_at','displays','related_to') NOT NULL DEFAULT 'related_to',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`rel_id`),
  UNIQUE KEY `uk_memorial_site` (`memorial_id`,`site_id`,`relation_type`),
  KEY `idx_memorial_site_site` (`site_id`),
  CONSTRAINT `fk_memorial_site_memorial` FOREIGN KEY (`memorial_id`) REFERENCES `memorial_hall` (`memorial_id`),
  CONSTRAINT `fk_memorial_site_site` FOREIGN KEY (`site_id`) REFERENCES `red_site` (`site_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `resource_discovery_candidate` (
  `candidate_id` bigint NOT NULL AUTO_INCREMENT,
  `school_id` bigint NOT NULL,
  `provider` varchar(30) NOT NULL DEFAULT 'amap',
  `provider_place_id` varchar(100) NOT NULL,
  `place_name` varchar(200) NOT NULL,
  `address` varchar(300) DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `latitude` decimal(10,7) DEFAULT NULL,
  `provider_type_code` varchar(50) DEFAULT NULL,
  `provider_type_name` varchar(255) DEFAULT NULL,
  `contact_phone` varchar(100) DEFAULT NULL,
  `opening_hours` varchar(255) DEFAULT NULL,
  `distance_meters` int DEFAULT NULL,
  `raw_json` json DEFAULT NULL,
  `analysis_status` enum('unanalyzed','completed','failed') NOT NULL DEFAULT 'unanalyzed',
  `ideological_relevant` tinyint(1) DEFAULT NULL,
  `ai_category` varchar(50) DEFAULT NULL,
  `ai_subcategory` varchar(100) DEFAULT NULL,
  `ai_confidence` decimal(4,3) DEFAULT NULL,
  `ai_rationale` text,
  `education_themes_json` json DEFAULT NULL,
  `target_grades` varchar(255) DEFAULT NULL,
  `activity_suggestion` text,
  `verification_notes` text,
  `decision_status` enum('pending','approved','rejected') NOT NULL DEFAULT 'pending',
  `matched_resource_id` bigint DEFAULT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `first_seen_at` datetime NOT NULL,
  `last_seen_at` datetime NOT NULL,
  `last_analyzed_at` datetime DEFAULT NULL,
  `reviewed_by` varchar(100) DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `review_remark` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`candidate_id`),
  UNIQUE KEY `uk_discovery_candidate_place` (`school_id`,`provider`,`provider_place_id`),
  KEY `fk_discovery_candidate_resource` (`matched_resource_id`),
  KEY `idx_discovery_candidate_review` (`decision_status`,`analysis_status`),
  KEY `idx_discovery_candidate_school` (`school_id`,`last_seen_at`),
  KEY `idx_discovery_candidate_geo` (`longitude`,`latitude`),
  CONSTRAINT `fk_discovery_candidate_resource` FOREIGN KEY (`matched_resource_id`) REFERENCES `local_edu_resource` (`resource_id`),
  CONSTRAINT `fk_discovery_candidate_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `resource_discovery_run` (
  `run_id` bigint NOT NULL AUTO_INCREMENT,
  `school_id` bigint NOT NULL,
  `radius_meters` int NOT NULL,
  `provider` varchar(30) NOT NULL DEFAULT 'amap',
  `status` enum('pending','running','completed','failed') NOT NULL DEFAULT 'pending',
  `forced` tinyint(1) NOT NULL DEFAULT '0',
  `provider_count` int NOT NULL DEFAULT '0',
  `candidate_count` int NOT NULL DEFAULT '0',
  `analysis_count` int NOT NULL DEFAULT '0',
  `error_message` varchar(500) DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `cache_expires_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`run_id`),
  KEY `idx_discovery_run_cache` (`school_id`,`radius_meters`,`status`,`cache_expires_at`),
  KEY `idx_discovery_run_status` (`status`,`started_at`),
  CONSTRAINT `fk_discovery_run_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `resource_discovery_run_item` (
  `run_id` bigint NOT NULL,
  `candidate_id` bigint NOT NULL,
  `result_rank` int NOT NULL,
  `distance_meters` int DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`run_id`,`candidate_id`),
  KEY `fk_discovery_item_candidate` (`candidate_id`),
  KEY `idx_discovery_item_rank` (`run_id`,`result_rank`),
  CONSTRAINT `fk_discovery_item_candidate` FOREIGN KEY (`candidate_id`) REFERENCES `resource_discovery_candidate` (`candidate_id`),
  CONSTRAINT `fk_discovery_item_run` FOREIGN KEY (`run_id`) REFERENCES `resource_discovery_run` (`run_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `teaching_plan_feedback` (
  `feedback_id` bigint NOT NULL AUTO_INCREMENT,
  `generation_id` bigint NOT NULL,
  `teacher_account_id` bigint NOT NULL,
  `adopted` tinyint(1) NOT NULL,
  `rating` tinyint NOT NULL,
  `teacher_note` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `submitted_at` datetime NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `reason_codes_json` json DEFAULT NULL,
  PRIMARY KEY (`feedback_id`),
  UNIQUE KEY `uk_teaching_plan_feedback_generation` (`generation_id`),
  KEY `idx_teaching_plan_feedback_teacher_time` (`teacher_account_id`,`submitted_at`),
  KEY `idx_teaching_plan_feedback_adopted_rating` (`adopted`,`rating`),
  CONSTRAINT `fk_teaching_plan_feedback_generation` FOREIGN KEY (`generation_id`) REFERENCES `ai_teaching_plan_generation` (`generation_id`),
  CONSTRAINT `chk_teaching_plan_feedback_rating` CHECK ((`rating` between 1 and 5))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 系统角色权限：仅插入缺失项，不覆盖已有角色、权限或密码。
INSERT INTO sys_role (role_code, role_name, role_scope, is_system, status)
SELECT 'platform_admin', '平台管理员', 'platform', 1, 'active' WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code='platform_admin');
INSERT INTO sys_role (role_code, role_name, role_scope, is_system, status)
SELECT 'school_admin', '学校管理员', 'school', 1, 'active' WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code='school_admin');
INSERT INTO sys_role (role_code, role_name, role_scope, is_system, status)
SELECT 'teacher', '教师', 'school', 1, 'active' WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code='teacher');
INSERT INTO sys_role (role_code, role_name, role_scope, is_system, status)
SELECT 'student', '学生', 'school', 1, 'active' WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code='student');
INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, sort_order)
SELECT 'admin.account.manage', '账号管理', 'menu', '/admin#user-accounts', 10 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code='admin.account.manage');
INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, sort_order)
SELECT 'admin.profile.manage', '档案管理', 'menu', '/admin#user-profiles', 20 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code='admin.profile.manage');
INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, sort_order)
SELECT 'admin.role.manage', '角色权限管理', 'menu', '/admin#roles', 30 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code='admin.role.manage');
INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, sort_order)
SELECT 'admin.account.read', '查看账号', 'api', '/api/admin/user-accounts', 101 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code='admin.account.read');
INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, sort_order)
SELECT 'admin.account.write', '维护账号', 'api', '/api/admin/user-accounts', 102 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code='admin.account.write');
INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, sort_order)
SELECT 'admin.profile.read', '查看档案', 'api', '/api/admin/user-profiles', 201 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code='admin.profile.read');
INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, sort_order)
SELECT 'admin.profile.write', '维护档案', 'api', '/api/admin/user-profiles', 202 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code='admin.profile.write');
INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, sort_order)
SELECT 'admin.role.read', '查看角色权限', 'api', '/api/admin/roles', 301 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code='admin.role.read');
INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, sort_order)
SELECT 'admin.role.write', '维护角色权限', 'api', '/api/admin/roles', 302 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code='admin.role.write');

INSERT INTO sys_account_role(account_id,role_id,data_scope)
SELECT a.account_id,r.role_id,IF(r.role_code='platform_admin','all','school')
FROM school_user_account a JOIN sys_role r
  ON r.role_code COLLATE utf8mb4_0900_ai_ci=a.role_code COLLATE utf8mb4_0900_ai_ci
WHERE NOT EXISTS (SELECT 1 FROM sys_account_role ar WHERE ar.account_id=a.account_id AND ar.role_id=r.role_id);
INSERT INTO sys_role_permission(role_id,permission_id,data_scope)
SELECT r.role_id,p.permission_id,'all' FROM sys_role r CROSS JOIN sys_permission p
WHERE r.role_code='platform_admin' AND p.permission_code LIKE 'admin.%'
AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=r.role_id AND rp.permission_id=p.permission_id);

  SET SESSION foreign_key_checks=old_fk;
  SET SESSION unique_checks=old_unique;
  SET SESSION sql_mode=old_mode;
  DO RELEASE_LOCK(CONCAT('gc_schema:',DATABASE()));
  SELECT DATABASE() AS target_database, 'Schema ready' AS result;
END$$
DELIMITER ;

-- 所有业务操作都在此 CALL 内；即使客户端继续执行，也不会绕过失败检查。
CALL gc_sql_apply();
DROP PROCEDURE IF EXISTS gc_sql_apply;
