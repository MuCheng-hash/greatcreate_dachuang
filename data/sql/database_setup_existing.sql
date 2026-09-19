-- MySQL 8.0；在 IDEA 选择目标数据库后，作为完整脚本执行（包括 DELIMITER）。
-- 使用独立控制台，确保没有未提交事务；不要与应用启动/其他迁移并发执行。
-- 本文件自包含，不创建/切换数据库，不删除业务表或重置账号密码。
-- DDL 隐式提交，失败不能整体回滚；已有库务必先备份，再修正报错后重跑。
-- 未选择数据库时 MySQL 会在创建辅助过程前报 No database selected。
DELIMITER $$

-- 辅助过程仅供本文件使用；失败后重新执行整个文件即可重新创建。
DROP PROCEDURE IF EXISTS gc_sql_exec$$
CREATE PROCEDURE gc_sql_exec(IN ddl_text LONGTEXT)
SQL SECURITY INVOKER
BEGIN
  DECLARE prepared_ok BOOLEAN DEFAULT FALSE;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    IF prepared_ok THEN DEALLOCATE PREPARE gc_sql_stmt; END IF;
    SET @gc_sql_ddl = NULL;
    RESIGNAL;
  END;
  SET @gc_sql_ddl = ddl_text;
  PREPARE gc_sql_stmt FROM @gc_sql_ddl;
  SET prepared_ok = TRUE;
  EXECUTE gc_sql_stmt;
  DEALLOCATE PREPARE gc_sql_stmt;
  SET @gc_sql_ddl = NULL;
END$$


-- 先检查现有字段，拒绝隐式收窄、默认值变化和无依据的数据转换。
DROP PROCEDURE IF EXISTS gc_sql_validate_column$$
CREATE PROCEDURE gc_sql_validate_column(IN tbl VARCHAR(64), IN col VARCHAR(64),
    IN wanted_type TEXT, IN wanted_null VARCHAR(3), IN wanted_default TEXT,
    IN wanted_extra VARCHAR(100), IN enum_extension BOOLEAN)
SQL SECURITY INVOKER
BEGIN
  DECLARE actual_type TEXT;
  DECLARE actual_null VARCHAR(3);
  DECLARE actual_default TEXT;
  DECLARE actual_extra TEXT;
  DECLARE message_text VARCHAR(128);
  DECLARE type_ok BOOLEAN DEFAULT FALSE;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name=tbl AND column_name=col) THEN
    SELECT column_type,is_nullable,column_default,extra
      INTO actual_type,actual_null,actual_default,actual_extra
      FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tbl AND column_name=col;
    SET type_ok=REGEXP_REPLACE(LOWER(actual_type),'^(tinyint|smallint|mediumint|int|bigint)\\([0-9]+\\)', '$1')
      = REGEXP_REPLACE(LOWER(wanted_type),'^(tinyint|smallint|mediumint|int|bigint)\\([0-9]+\\)', '$1');
    -- 仅已知枚举扩展允许保留额外值，实际补值在下方专项迁移中完成。
    IF enum_extension AND LEFT(actual_type,5)='enum(' THEN SET type_ok=TRUE; END IF;
    IF NOT type_ok OR actual_null<>wanted_null
      OR NOT(REPLACE(LOWER(actual_default),'current_timestamp()','current_timestamp') <=> REPLACE(LOWER(wanted_default),'current_timestamp()','current_timestamp'))
      OR (LOCATE('auto_increment',actual_extra)>0)<>(LOCATE('auto_increment',wanted_extra)>0)
      OR (LOCATE('on update',actual_extra)>0)<>(LOCATE('on update',wanted_extra)>0)
      OR LOCATE('GENERATED',UPPER(REPLACE(actual_extra,'DEFAULT_GENERATED','')))>0 THEN
      SET message_text=LEFT(CONCAT('Incompatible column: ',tbl,'.',col,'; type/null/default/extra; manual repair required'),128);
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=message_text;
    END IF;
  END IF;
END$$

DROP PROCEDURE IF EXISTS gc_sql_column$$
CREATE PROCEDURE gc_sql_column(IN tbl VARCHAR(64), IN col VARCHAR(64), IN definition TEXT)
SQL SECURITY INVOKER
BEGIN
  DECLARE message_text VARCHAR(128);
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name=tbl AND column_name=col) THEN
    -- 缺失主键不能用重新分配 ID 的方式补齐。
    IF LOCATE('AUTO_INCREMENT', UPPER(definition)) > 0 THEN
      SET message_text=CONCAT('Missing identity column: ',tbl,'.',col,'; manual repair required');
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=message_text;
    END IF;
    IF LOCATE('NOT NULL',UPPER(definition))>0 AND LOCATE('DEFAULT',UPPER(definition))=0 THEN
      CALL gc_sql_exec(CONCAT('SELECT EXISTS(SELECT 1 FROM `',tbl,'` LIMIT 1) INTO @gc_sql_has_rows'));
      IF @gc_sql_has_rows THEN
        SET @gc_sql_has_rows=NULL;
        SET message_text=LEFT(CONCAT('Missing required column on populated table: ',tbl,'.',col),128);
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=message_text;
      END IF;
      SET @gc_sql_has_rows=NULL;
    END IF;
    CALL gc_sql_exec(CONCAT('ALTER TABLE `',tbl,'` ADD COLUMN `',col,'` ',definition));
  END IF;
END$$

DROP PROCEDURE IF EXISTS gc_sql_index$$
CREATE PROCEDURE gc_sql_index(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN cols TEXT,
    IN is_non_unique INT, IN kind VARCHAR(16), IN definition TEXT)
SQL SECURITY INVOKER
BEGIN
  DECLARE found_cols TEXT;
  DECLARE found_unique INT;
  DECLARE found_kind VARCHAR(16);
  DECLARE message_text VARCHAR(128);
  SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index),MIN(non_unique),MIN(index_type)
    INTO found_cols,found_unique,found_kind FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name=tbl AND index_name=idx;
  IF found_cols IS NULL THEN
    -- MySQL 在创建 UNIQUE 时检查重复值；不关闭唯一性检查或删除冲突行。
    CALL gc_sql_exec(CONCAT('ALTER TABLE `',tbl,'` ADD ',definition));
  ELSEIF kind='FULLTEXT' AND found_kind='FULLTEXT' THEN
    -- STATISTICS 不提供解析器；保守原子重建以确保 ngram，重复升级会产生索引维护开销。
    CALL gc_sql_exec(CONCAT('ALTER TABLE `',tbl,'` DROP INDEX `',idx,'`, ADD ',definition));
  ELSEIF found_cols <> cols OR found_unique <> is_non_unique OR found_kind <> kind
      OR EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name=tbl AND index_name=idx AND
          ((sub_part IS NOT NULL AND LOCATE(CONCAT('`',column_name,'`(',sub_part,')'),definition)=0)
            OR is_visible<>'YES' OR collation='D')) THEN
    IF kind='FULLTEXT' AND found_kind='FULLTEXT' THEN
      -- 列集合改变才重建；旧索引删除和新索引创建在同一条原子 DDL 中。
      CALL gc_sql_exec(CONCAT('ALTER TABLE `',tbl,'` DROP INDEX `',idx,'`, ADD ',definition));
    ELSE
      SET message_text=CONCAT('Incompatible index: ',tbl,'.',idx,'; inspect before retry');
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=message_text;
    END IF;
  END IF;
END$$

DROP PROCEDURE IF EXISTS gc_sql_fk$$
CREATE PROCEDURE gc_sql_fk(IN tbl VARCHAR(64),IN fk VARCHAR(64),IN col VARCHAR(64),
    IN ref_tbl VARCHAR(64),IN ref_col VARCHAR(64),IN definition TEXT)
SQL SECURITY INVOKER
BEGIN
  DECLARE message_text VARCHAR(128);
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints
      WHERE constraint_schema=DATABASE() AND table_name=tbl AND constraint_name=fk) THEN
    IF NOT EXISTS (SELECT 1 FROM information_schema.key_column_usage
        WHERE constraint_schema=DATABASE() AND table_name=tbl AND constraint_name=fk
          AND column_name=col AND referenced_table_name=ref_tbl AND referenced_column_name=ref_col)
      OR (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE()
          AND table_name=tbl AND constraint_name=fk)<>1
      OR NOT EXISTS (SELECT 1 FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()
          AND table_name=tbl AND constraint_name=fk
          AND REPLACE(delete_rule,'NO ACTION','RESTRICT')=IF(LOCATE('ON DELETE CASCADE',definition)>0,'CASCADE',IF(LOCATE('ON DELETE SET NULL',definition)>0,'SET NULL','RESTRICT'))
          AND REPLACE(update_rule,'NO ACTION','RESTRICT')=IF(LOCATE('ON UPDATE CASCADE',definition)>0,'CASCADE','RESTRICT')) THEN
      SET message_text=CONCAT('Incompatible foreign key: ',tbl,'.',fk);
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=message_text;
    END IF;
  ELSE
    -- 原生 ALTER 验证存量引用和类型兼容性；孤立数据必须人工处理。
    CALL gc_sql_exec(CONCAT('ALTER TABLE `',tbl,'` ADD ',definition));
  END IF;
END$$
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
  IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
      AND table_name IN ('administrative_region','data_source','school','school_user_account','local_edu_resource','content_chunk','teaching_activity_plan')) <> 7 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Base tables missing; use database_setup.sql for an empty database';
  END IF;
  SET SESSION foreign_key_checks=1;
  SET SESSION unique_checks=1;
  SET SESSION sql_mode=CONCAT_WS(',',NULLIF(old_mode,''),'STRICT_ALL_TABLES');


  -- 在任何业务 DDL 前检查已存在字段，保留不兼容数据供人工处理。
  CALL gc_sql_validate_column('story_entity_rel','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);

  CALL gc_sql_validate_column('site_hero_rel','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);

  CALL gc_sql_validate_column('site_event_rel','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);

  CALL gc_sql_validate_column('memorial_site_rel','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);

  CALL gc_sql_validate_column('memorial_hero_rel','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);

  CALL gc_sql_validate_column('memorial_event_rel','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);

  CALL gc_sql_validate_column('event_hero_rel','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);

  CALL gc_sql_validate_column('entity_tag_rel','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);

  CALL gc_sql_validate_column('audit_log','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);

  CALL gc_sql_validate_column('administrative_region','region_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('administrative_region','parent_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('administrative_region','region_name','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('administrative_region','region_level','enum(''province'',''city'',''county'',''township'',''village'')','NO',NULL,'',0);
  CALL gc_sql_validate_column('administrative_region','adcode','varchar(20)','YES',NULL,'',0);
  CALL gc_sql_validate_column('administrative_region','center_longitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('administrative_region','center_latitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('administrative_region','boundary_geojson','json','YES',NULL,'',0);
  CALL gc_sql_validate_column('administrative_region','intro','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('administrative_region','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('administrative_region','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('agent_action_idempotency','action_id','varchar(96)','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_idempotency','turn_id','varchar(64)','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_idempotency','operation','varchar(160)','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_idempotency','request_hash','char(64)','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_idempotency','request_json','json','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_idempotency','status','varchar(24)','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_idempotency','response_json','json','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_idempotency','resource_reference','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_idempotency','completed_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_idempotency','payload_redacted_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_idempotency','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('agent_action_idempotency','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('agent_action_outbox','event_id','varchar(64)','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_outbox','action_id','varchar(96)','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_outbox','event_type','varchar(160)','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_outbox','payload_json','json','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_outbox','status','varchar(24)','NO','PENDING','',0);
  CALL gc_sql_validate_column('agent_action_outbox','attempt_count','int','NO','0','',0);
  CALL gc_sql_validate_column('agent_action_outbox','next_attempt_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('agent_action_outbox','lease_owner','varchar(128)','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_outbox','lease_expires_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_outbox','published_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_outbox','error_summary','varchar(1000)','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_outbox','payload_redacted_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_action_outbox','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('agent_action_outbox','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('agent_debug_event','event_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('agent_debug_event','debug_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_event','event_name','varchar(80)','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_event','event_stage','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_event','status','varchar(20)','NO','started','',0);
  CALL gc_sql_validate_column('agent_debug_event','duration_ms','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_event','payload_json','json','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_event','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('agent_debug_session','debug_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('agent_debug_session','question','text','NO',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_session','scope_type','varchar(30)','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_session','scope_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_session','model_id','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_session','provider','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_session','answer','mediumtext','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_session','status','varchar(20)','NO','success','',0);
  CALL gc_sql_validate_column('agent_debug_session','duration_ms','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_session','created_by','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('agent_debug_session','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','generation_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','account_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','actor_role','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','thread_id','varchar(128)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','grade','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','theme','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','activity_type','varchar(40)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','duration_minutes','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','practice_required','tinyint(1)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','generation_status','varchar(24)','NO',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','retrieval_status','varchar(24)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','llm_provider','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','llm_model','varchar(160)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','prompt_version','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','prompt_run_id','varchar(160)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','prompt_experiment','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','prompt_variant','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','request_json','json','NO',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','response_json','json','NO',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','saved_plan_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('ai_teaching_plan_generation','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('audit_log','log_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('audit_log','entity_type','enum(''region'',''site'',''hero'',''event'',''memorial'',''story'',''tag'',''school'',''resource'',''activity_plan'')','NO',NULL,'',1);
  CALL gc_sql_validate_column('audit_log','entity_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('audit_log','operation_type','enum(''insert'',''update'',''delete'',''review'')','NO',NULL,'',0);
  CALL gc_sql_validate_column('audit_log','operator_name','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('audit_log','change_summary','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('audit_log','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('auth_refresh_token','token_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('auth_refresh_token','account_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('auth_refresh_token','token_hash','char(64)','NO',NULL,'',0);
  CALL gc_sql_validate_column('auth_refresh_token','token_family_id','varchar(64)','NO',NULL,'',0);
  CALL gc_sql_validate_column('auth_refresh_token','issued_at','datetime','NO',NULL,'',0);
  CALL gc_sql_validate_column('auth_refresh_token','expires_at','datetime','NO',NULL,'',0);
  CALL gc_sql_validate_column('auth_refresh_token','rotated_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('auth_refresh_token','revoked_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('auth_refresh_token','revoke_reason','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('auth_refresh_token','user_agent','varchar(512)','YES',NULL,'',0);
  CALL gc_sql_validate_column('auth_refresh_token','client_ip','varchar(45)','YES',NULL,'',0);
  CALL gc_sql_validate_column('auth_refresh_token','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('auth_refresh_token','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('catalog_import_batch','batch_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('catalog_import_batch','file_name','varchar(255)','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_batch','created_by','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_batch','status','varchar(32)','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_batch','total_rows','int','NO','0','',0);
  CALL gc_sql_validate_column('catalog_import_batch','valid_rows','int','NO','0','',0);
  CALL gc_sql_validate_column('catalog_import_batch','invalid_rows','int','NO','0','',0);
  CALL gc_sql_validate_column('catalog_import_batch','duplicate_rows','int','NO','0','',0);
  CALL gc_sql_validate_column('catalog_import_batch','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('catalog_import_batch','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('catalog_import_row','row_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('catalog_import_row','batch_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_row','sheet_name','varchar(64)','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_row','row_no','int','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_row','entity_type','varchar(32)','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_row','payload_json','longtext','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_row','validation_status','varchar(32)','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_row','validation_message','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_row','imported_entity_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('catalog_import_row','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('catalog_import_row','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('catalog_projection_task','task_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('catalog_projection_task','entity_type','varchar(64)','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_projection_task','entity_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_projection_task','task_type','varchar(32)','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_projection_task','status','varchar(32)','NO',NULL,'',0);
  CALL gc_sql_validate_column('catalog_projection_task','attempt_count','int','NO','0','',0);
  CALL gc_sql_validate_column('catalog_projection_task','last_error','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('catalog_projection_task','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('catalog_projection_task','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('class_info','class_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('class_info','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('class_info','class_name','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('class_info','grade_name','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('class_info','class_type','varchar(30)','NO','administrative','',0);
  CALL gc_sql_validate_column('class_info','invite_code','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('class_info','status','varchar(20)','NO','active','',0);
  CALL gc_sql_validate_column('class_info','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('class_info','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('class_learning_task','task_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('class_learning_task','class_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('class_learning_task','publisher_teacher_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('class_learning_task','title','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('class_learning_task','description','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('class_learning_task','published_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('class_learning_task','due_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('class_learning_task','status','varchar(20)','NO','published','',0);
  CALL gc_sql_validate_column('class_learning_task','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('class_learning_task','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('class_learning_task','task_type','varchar(40)','NO','red_culture_learning','',0);
  CALL gc_sql_validate_column('class_learning_task','submission_rule','varchar(40)','NO','text_required','',0);
  CALL gc_sql_validate_column('class_learning_task','allow_late_submission','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('class_learning_task','start_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('class_learning_task','material_filename','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('class_learning_task','material_storage_key','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('class_learning_task','material_content_type','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('class_member','id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('class_member','class_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('class_member','student_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('class_member','join_source','varchar(30)','NO','manual','',0);
  CALL gc_sql_validate_column('class_member','joined_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('class_member','status','varchar(20)','NO','active','',0);
  CALL gc_sql_validate_column('class_member','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('class_member','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('class_member','is_primary','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('class_teacher','id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('class_teacher','class_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('class_teacher','teacher_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('class_teacher','teacher_role','varchar(30)','NO','subject_teacher','',0);
  CALL gc_sql_validate_column('class_teacher','status','varchar(20)','NO','active','',0);
  CALL gc_sql_validate_column('class_teacher','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('class_teacher','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('content_chunk','chunk_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('content_chunk','entity_type','enum(''site'',''hero'',''event'',''memorial'',''story'',''school'',''resource'',''activity_plan'')','NO',NULL,'',1);
  CALL gc_sql_validate_column('content_chunk','entity_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','chunk_title','varchar(200)','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','chunk_text','longtext','NO',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','chunk_index','int','NO','1','',0);
  CALL gc_sql_validate_column('content_chunk','source_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','token_count','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','embedding_status','enum(''pending'',''done'',''failed'')','NO','pending','',0);
  CALL gc_sql_validate_column('content_chunk','retrieval_text','longtext','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','embedding_hash','char(64)','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','embedding_model','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','embedding_dimensions','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','embedding_index_version','varchar(32)','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','embedded_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('content_chunk','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('content_chunk','content_hash','varchar(64)','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','qdrant_collection','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','vector_point_id','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('content_chunk','embedding_error','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('data_source','source_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('data_source','source_name','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('data_source','source_type','enum(''government'',''encyclopedia'',''news'',''museum'',''paper'',''other'')','NO',NULL,'',0);
  CALL gc_sql_validate_column('data_source','organization_name','varchar(200)','YES',NULL,'',0);
  CALL gc_sql_validate_column('data_source','base_url','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('data_source','reliability_level','tinyint','NO','3','',0);
  CALL gc_sql_validate_column('data_source','license_note','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('data_source','crawl_allowed','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('data_source','last_crawled_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('data_source','remark','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('data_source','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('data_source','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('entity_source_rel','rel_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('entity_source_rel','entity_type','enum(''site'',''hero'',''event'',''memorial'',''story'',''school'',''resource'',''activity_plan'')','NO',NULL,'',1);
  CALL gc_sql_validate_column('entity_source_rel','entity_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('entity_source_rel','source_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('entity_source_rel','source_url','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('entity_source_rel','captured_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('entity_source_rel','source_excerpt','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('entity_source_rel','credibility_score','tinyint','NO','3','',0);
  CALL gc_sql_validate_column('entity_source_rel','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('entity_source_rel','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('entity_tag_rel','rel_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('entity_tag_rel','entity_type','enum(''site'',''hero'',''event'',''memorial'',''story'',''school'',''resource'',''activity_plan'')','NO',NULL,'',1);
  CALL gc_sql_validate_column('entity_tag_rel','entity_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('entity_tag_rel','tag_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('entity_tag_rel','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('event_hero_rel','rel_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('event_hero_rel','event_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('event_hero_rel','hero_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('event_hero_rel','relation_type','enum(''participant'',''leader'',''witness'',''martyr'',''related_to'')','NO','participant','',0);
  CALL gc_sql_validate_column('event_hero_rel','contribution_text','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('event_hero_rel','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('hero_person','hero_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('hero_person','hero_code','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','hero_name','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','gender','enum(''male'',''female'',''unknown'')','NO','unknown','',0);
  CALL gc_sql_validate_column('hero_person','birth_year','smallint','YES',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','death_year','smallint','YES',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','birth_date_text','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','death_date_text','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','native_place_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','native_place_text','varchar(200)','YES',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','profile_summary','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','main_deeds','longtext','YES',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','official_url','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('hero_person','review_status','enum(''draft'',''pending'',''approved'',''rejected'')','NO','draft','',0);
  CALL gc_sql_validate_column('hero_person','is_active','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('hero_person','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('hero_person','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('historical_event','event_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('historical_event','event_code','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','event_name','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','primary_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','event_time_text','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','start_date','date','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','end_date','date','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','start_year','smallint','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','end_year','smallint','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','longitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','latitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','historical_significance','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','event_process','longtext','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','result_impact','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','official_url','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('historical_event','review_status','enum(''draft'',''pending'',''approved'',''rejected'')','NO','draft','',0);
  CALL gc_sql_validate_column('historical_event','is_active','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('historical_event','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('historical_event','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('knowledge_chunk','id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('knowledge_chunk','document_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_chunk','chunk_order','int','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_chunk','title_path','varchar(1000)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_chunk','content','mediumtext','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_chunk','token_count','int','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_chunk','subject','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_chunk','subject_type','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_chunk','tags','json','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_chunk','qdrant_point_id','varchar(64)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_chunk','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('knowledge_chunk','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('knowledge_document','id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('knowledge_document','school_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','title','varchar(255)','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','original_filename','varchar(255)','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','content_type','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','file_size','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','sha256','char(64)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','object_key','varchar(512)','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','markdown_object_key','varchar(512)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','status','varchar(24)','NO','PENDING','',0);
  CALL gc_sql_validate_column('knowledge_document','published_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','indexed_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','created_by','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('knowledge_document','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('knowledge_document_image','id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('knowledge_document_image','document_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document_image','sha256','char(64)','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document_image','object_key','varchar(512)','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document_image','alt_text','varchar(1000)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document_image','description','mediumtext','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document_image','status','varchar(24)','NO','PENDING','',0);
  CALL gc_sql_validate_column('knowledge_document_image','model','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document_image','error_summary','varchar(1000)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_document_image','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('knowledge_document_image','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','document_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','status','varchar(24)','NO','PENDING','',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','current_node','varchar(40)','NO','VALIDATE','',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','retry_count','int','NO','0','',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','error_summary','varchar(1000)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','metadata_json','json','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','restart_from','varchar(40)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','started_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','finished_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','generation','bigint','NO','1','',0);
  CALL gc_sql_validate_column('knowledge_ingest_job','execution_attempts','int','NO','0','',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','event_id','char(36)','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','job_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','document_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','generation','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','status','varchar(24)','NO','PENDING','',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','attempt_count','int','NO','0','',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','next_attempt_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','lease_owner','char(36)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','lease_expires_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','error_summary','varchar(1000)','YES',NULL,'',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('knowledge_ingest_outbox','published_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','resource_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('local_edu_resource','resource_code','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','resource_name','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','resource_category','enum(''red_culture'',''intangible_culture'',''traditional_culture'',''local_history'',''public_culture'',''labor_education'',''public_welfare'',''ecological_civilization'',''patriotism_base'',''social_practice'',''other'')','NO','other','',0);
  CALL gc_sql_validate_column('local_edu_resource','resource_subcategory','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','county_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','township_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','address','varchar(300)','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','longitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','latitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','organization_name','varchar(200)','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','contact_phone','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','opening_time_desc','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','reservation_required','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('local_edu_resource','recommended_visit_minutes','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','intro','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','education_value','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','activity_suggestion','longtext','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','target_grade','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','safety_note','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','source_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','review_status','enum(''draft'',''pending'',''approved'',''rejected'')','NO','draft','',0);
  CALL gc_sql_validate_column('local_edu_resource','is_active','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('local_edu_resource','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('local_edu_resource','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('local_edu_resource','external_provider','varchar(30)','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','external_place_id','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('local_edu_resource','source_checked_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_event_rel','rel_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('memorial_event_rel','memorial_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('memorial_event_rel','event_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('memorial_event_rel','relation_type','enum(''commemorates'',''exhibits'',''related_to'')','NO','commemorates','',0);
  CALL gc_sql_validate_column('memorial_event_rel','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('memorial_hall','memorial_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('memorial_hall','memorial_code','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','memorial_name','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','address','varchar(300)','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','longitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','latitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','exhibition_content','longtext','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','intro','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','opening_time_desc','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','ticket_info','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','contact_phone','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','official_url','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hall','review_status','enum(''draft'',''pending'',''approved'',''rejected'')','NO','draft','',0);
  CALL gc_sql_validate_column('memorial_hall','is_active','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('memorial_hall','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('memorial_hall','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('memorial_hero_rel','rel_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('memorial_hero_rel','memorial_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hero_rel','hero_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('memorial_hero_rel','relation_type','enum(''commemorates'',''exhibits'',''related_to'')','NO','commemorates','',0);
  CALL gc_sql_validate_column('memorial_hero_rel','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('memorial_site_rel','rel_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('memorial_site_rel','memorial_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('memorial_site_rel','site_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('memorial_site_rel','relation_type','enum(''located_at'',''displays'',''related_to'')','NO','related_to','',0);
  CALL gc_sql_validate_column('memorial_site_rel','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('rag_index_job','job_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('rag_index_job','job_type','varchar(30)','NO',NULL,'',0);
  CALL gc_sql_validate_column('rag_index_job','target_entity_type','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('rag_index_job','target_entity_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('rag_index_job','status','varchar(20)','NO','pending','',0);
  CALL gc_sql_validate_column('rag_index_job','total_chunks','int','NO','0','',0);
  CALL gc_sql_validate_column('rag_index_job','indexed_chunks','int','NO','0','',0);
  CALL gc_sql_validate_column('rag_index_job','failed_chunks','int','NO','0','',0);
  CALL gc_sql_validate_column('rag_index_job','started_by','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('rag_index_job','started_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('rag_index_job','finished_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('rag_index_job','error_message','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('rag_index_job','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('rag_index_job','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('rag_retrieval_test_log','test_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('rag_retrieval_test_log','query_text','text','NO',NULL,'',0);
  CALL gc_sql_validate_column('rag_retrieval_test_log','scope_type','varchar(30)','NO','GLOBAL','',0);
  CALL gc_sql_validate_column('rag_retrieval_test_log','scope_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('rag_retrieval_test_log','top_k','int','NO','5','',0);
  CALL gc_sql_validate_column('rag_retrieval_test_log','retrieval_status','varchar(20)','NO','success','',0);
  CALL gc_sql_validate_column('rag_retrieval_test_log','result_json','json','YES',NULL,'',0);
  CALL gc_sql_validate_column('rag_retrieval_test_log','created_by','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('rag_retrieval_test_log','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('rag_web_source','source_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('rag_web_source','display_name','varchar(120)','NO',NULL,'',0);
  CALL gc_sql_validate_column('rag_web_source','domain','varchar(255)','NO',NULL,'',0);
  CALL gc_sql_validate_column('rag_web_source','enabled','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('rag_web_source','sort_order','int','NO','100','',0);
  CALL gc_sql_validate_column('rag_web_source','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('rag_web_source','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('red_site','site_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('red_site','site_code','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('red_site','site_name','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('red_site','region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','address','varchar(300)','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','longitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','latitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','established_date','date','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','established_year','smallint','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','site_level','enum(''national'',''provincial'',''municipal'',''county'',''other'')','NO','other','',0);
  CALL gc_sql_validate_column('red_site','protection_level','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','historical_background','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','intro','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','opening_time_desc','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','suggested_visit_minutes','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','official_url','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_site','review_status','enum(''draft'',''pending'',''approved'',''rejected'')','NO','draft','',0);
  CALL gc_sql_validate_column('red_site','is_active','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('red_site','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('red_site','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('red_story','story_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('red_story','story_code','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('red_story','story_title','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('red_story','related_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_story','age_group','enum(''primary'',''middle'',''high'',''college'',''general'')','NO','general','',0);
  CALL gc_sql_validate_column('red_story','summary','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_story','story_content','longtext','NO',NULL,'',0);
  CALL gc_sql_validate_column('red_story','source_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('red_story','review_status','enum(''draft'',''pending'',''approved'',''rejected'')','NO','draft','',0);
  CALL gc_sql_validate_column('red_story','is_active','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('red_story','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('red_story','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','candidate_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','provider','varchar(30)','NO','amap','',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','provider_place_id','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','place_name','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','address','varchar(300)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','longitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','latitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','provider_type_code','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','provider_type_name','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','contact_phone','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','opening_hours','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','distance_meters','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','raw_json','json','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','analysis_status','enum(''unanalyzed'',''completed'',''failed'')','NO','unanalyzed','',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','ideological_relevant','tinyint(1)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','ai_category','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','ai_subcategory','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','ai_confidence','decimal(4,3)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','ai_rationale','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','education_themes_json','json','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','target_grades','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','activity_suggestion','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','verification_notes','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','decision_status','enum(''pending'',''approved'',''rejected'')','NO','pending','',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','matched_resource_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','last_error','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','first_seen_at','datetime','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','last_seen_at','datetime','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','last_analyzed_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','reviewed_by','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','reviewed_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','review_remark','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('resource_discovery_candidate','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('resource_discovery_run','run_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('resource_discovery_run','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_run','radius_meters','int','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_run','provider','varchar(30)','NO','amap','',0);
  CALL gc_sql_validate_column('resource_discovery_run','status','enum(''pending'',''running'',''completed'',''failed'')','NO','pending','',0);
  CALL gc_sql_validate_column('resource_discovery_run','forced','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('resource_discovery_run','provider_count','int','NO','0','',0);
  CALL gc_sql_validate_column('resource_discovery_run','candidate_count','int','NO','0','',0);
  CALL gc_sql_validate_column('resource_discovery_run','analysis_count','int','NO','0','',0);
  CALL gc_sql_validate_column('resource_discovery_run','error_message','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_run','started_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_run','completed_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_run','cache_expires_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_run','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('resource_discovery_run','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('resource_discovery_run_item','run_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_run_item','candidate_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_run_item','result_rank','int','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_run_item','distance_meters','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_discovery_run_item','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('resource_media','media_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('resource_media','entity_type','enum(''site'',''hero'',''event'',''memorial'',''story'',''school'',''resource'',''activity_plan'')','NO',NULL,'',1);
  CALL gc_sql_validate_column('resource_media','entity_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_media','media_type','enum(''image'',''video'',''audio'',''document'',''link'')','NO','image','',0);
  CALL gc_sql_validate_column('resource_media','media_title','varchar(200)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_media','media_url','varchar(500)','NO',NULL,'',0);
  CALL gc_sql_validate_column('resource_media','cover_url','varchar(500)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_media','description','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_media','source_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_media','copyright_note','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('resource_media','sort_order','int','NO','0','',0);
  CALL gc_sql_validate_column('resource_media','is_primary','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('resource_media','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('resource_media','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('school','school_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('school','school_code','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('school','school_name','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('school','school_alias','varchar(200)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','province_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','city_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','county_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','township_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','village_region_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','school_level','enum(''kindergarten'',''primary'',''junior'',''senior'',''nine_year'',''twelve_year'',''vocational'',''special'',''other'')','NO','primary','',0);
  CALL gc_sql_validate_column('school','school_type','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','school_nature','enum(''public'',''private'',''other'')','NO','public','',0);
  CALL gc_sql_validate_column('school','is_rural_school','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('school','is_teaching_point','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('school','address','varchar(300)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','postcode','varchar(20)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','contact_phone','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','principal_name','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','longitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','latitude','decimal(10,7)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','geo_source_type','enum(''amap_poi'',''manual'',''school_official'',''government_doc'',''satellite_fix'',''other'')','NO','government_doc','',0);
  CALL gc_sql_validate_column('school','poi_name','varchar(200)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','poi_address','varchar(300)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','poi_type','varchar(200)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','geo_confidence','enum(''high'',''medium'',''low'',''unknown'')','NO','unknown','',0);
  CALL gc_sql_validate_column('school','geo_verified','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('school','intro','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','source_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','review_status','enum(''draft'',''pending'',''approved'',''rejected'')','NO','draft','',0);
  CALL gc_sql_validate_column('school','is_active','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('school','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('school','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('school','logo_url','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','website_url','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','contact_email','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','admin_contact_name','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','default_discovery_radius_km','decimal(8,2)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school','resource_calc_enabled','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('school','last_resource_calc_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_geo_record','geo_record_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('school_geo_record','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('school_geo_record','longitude','decimal(10,7)','NO',NULL,'',0);
  CALL gc_sql_validate_column('school_geo_record','latitude','decimal(10,7)','NO',NULL,'',0);
  CALL gc_sql_validate_column('school_geo_record','source_type','enum(''amap_poi'',''manual'',''school_official'',''government_doc'',''satellite_fix'',''other'')','NO','amap_poi','',0);
  CALL gc_sql_validate_column('school_geo_record','poi_name','varchar(200)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_geo_record','poi_address','varchar(300)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_geo_record','poi_type','varchar(200)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_geo_record','confidence_level','enum(''high'',''medium'',''low'',''unknown'')','NO','unknown','',0);
  CALL gc_sql_validate_column('school_geo_record','is_manual_reviewed','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('school_geo_record','review_result','enum(''pending'',''confirmed'',''corrected'',''rejected'')','NO','pending','',0);
  CALL gc_sql_validate_column('school_geo_record','reviewer_name','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_geo_record','reviewed_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_geo_record','is_current','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('school_geo_record','remark','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_geo_record','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('school_resource_calc_run','run_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('school_resource_calc_run','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_calc_run','radius_km','decimal(8,2)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_calc_run','calc_method','varchar(50)','NO','distance','',0);
  CALL gc_sql_validate_column('school_resource_calc_run','status','varchar(20)','NO','running','',0);
  CALL gc_sql_validate_column('school_resource_calc_run','candidate_count','int','NO','0','',0);
  CALL gc_sql_validate_column('school_resource_calc_run','linked_count','int','NO','0','',0);
  CALL gc_sql_validate_column('school_resource_calc_run','started_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_calc_run','finished_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_calc_run','error_message','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_calc_run','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('school_resource_calc_run','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('school_resource_rel','rel_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('school_resource_rel','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','resource_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','relation_type','enum(''nearby'',''cooperation'',''practice'',''curriculum_support'',''volunteer_base'',''research_route'',''other'')','NO','nearby','',0);
  CALL gc_sql_validate_column('school_resource_rel','distance_meters','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','recommended_travel_mode','enum(''walk'',''bike'',''bus'',''drive'',''mixed'',''unknown'')','NO','unknown','',0);
  CALL gc_sql_validate_column('school_resource_rel','estimated_duration_minutes','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','reachability_level','enum(''near'',''medium'',''far'',''very_far'',''unknown'')','NO','unknown','',0);
  CALL gc_sql_validate_column('school_resource_rel','priority_level','tinyint','NO','3','',0);
  CALL gc_sql_validate_column('school_resource_rel','education_theme_summary','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','source_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','review_status','enum(''draft'',''pending'',''approved'',''rejected'')','NO','draft','',0);
  CALL gc_sql_validate_column('school_resource_rel','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('school_resource_rel','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('school_resource_rel','calc_run_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','calc_method','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','match_score','decimal(10,4)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','route_distance_meters','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','route_duration_minutes','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','calculated_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_resource_rel','manual_locked','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('school_resource_rel','last_verified_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','account_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('school_user_account','username','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','password_hash','varchar(255)','NO',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','role_code','varchar(50)','NO','school_admin','',0);
  CALL gc_sql_validate_column('school_user_account','school_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','display_name','varchar(120)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','contact_name','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','contact_phone','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','real_name','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','email','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','account_type','varchar(30)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','force_password_change','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('school_user_account','password_updated_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','status','enum(''pending_activation'',''active'',''disabled'')','NO','active','',0);
  CALL gc_sql_validate_column('school_user_account','last_login_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('school_user_account','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('school_user_account','avatar_url','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','employee_no','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','student_no','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('school_user_account','last_login_ip','varchar(45)','YES',NULL,'',0);
  CALL gc_sql_validate_column('site_event_rel','rel_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('site_event_rel','site_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('site_event_rel','event_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('site_event_rel','relation_type','enum(''occurred_at'',''related_to'',''memorialized_at'')','NO','related_to','',0);
  CALL gc_sql_validate_column('site_event_rel','importance_level','tinyint','NO','3','',0);
  CALL gc_sql_validate_column('site_event_rel','remark','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('site_event_rel','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('site_hero_rel','rel_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('site_hero_rel','site_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('site_hero_rel','hero_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('site_hero_rel','relation_type','enum(''born_in'',''fought_in'',''memorialized'',''visited'',''related_to'')','NO','related_to','',0);
  CALL gc_sql_validate_column('site_hero_rel','importance_level','tinyint','NO','3','',0);
  CALL gc_sql_validate_column('site_hero_rel','remark','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('site_hero_rel','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('story_entity_rel','rel_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('story_entity_rel','story_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('story_entity_rel','entity_type','enum(''site'',''hero'',''event'',''memorial'',''school'',''resource'')','NO',NULL,'',1);
  CALL gc_sql_validate_column('story_entity_rel','entity_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('story_entity_rel','relation_type','enum(''about'',''mentions'',''teaches'')','NO','about','',0);
  CALL gc_sql_validate_column('story_entity_rel','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('student_profile','student_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('student_profile','account_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_profile','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_profile','student_no','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('student_profile','student_name','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_profile','grade_name','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('student_profile','enrollment_year','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('student_profile','status','varchar(20)','NO','active','',0);
  CALL gc_sql_validate_column('student_profile','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('student_profile','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('student_profile','profile_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('student_resource_browse_history','browse_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('student_resource_browse_history','student_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_resource_browse_history','resource_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_resource_browse_history','viewed_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('student_resource_browse_history','view_count','int','NO','1','',0);
  CALL gc_sql_validate_column('student_resource_browse_history','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('student_resource_browse_history','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('student_task_attachment','attachment_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('student_task_attachment','submission_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_attachment','original_filename','varchar(255)','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_attachment','storage_key','varchar(255)','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_attachment','content_type','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_attachment','file_size','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_attachment','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('student_task_attachment','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('student_task_progress','id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('student_task_progress','task_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_progress','student_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_progress','status','varchar(20)','NO','pending','',0);
  CALL gc_sql_validate_column('student_task_progress','completed_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('student_task_progress','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('student_task_progress','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('student_task_review','review_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('student_task_review','submission_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_review','teacher_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_review','review_action','varchar(20)','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_review','comment','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('student_task_review','grade','varchar(30)','YES',NULL,'',0);
  CALL gc_sql_validate_column('student_task_review','reviewed_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('student_task_review','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('student_task_review','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('student_task_submission','submission_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('student_task_submission','task_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_submission','student_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_submission','version_no','int','NO',NULL,'',0);
  CALL gc_sql_validate_column('student_task_submission','content','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('student_task_submission','selected_resource_ids','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('student_task_submission','submitted_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('student_task_submission','is_late','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('student_task_submission','status','varchar(20)','NO','draft','',0);
  CALL gc_sql_validate_column('student_task_submission','is_current','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('student_task_submission','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('student_task_submission','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('sys_account_role','id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('sys_account_role','account_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('sys_account_role','role_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('sys_account_role','data_scope','varchar(50)','NO','school','',0);
  CALL gc_sql_validate_column('sys_account_role','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('sys_permission','permission_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('sys_permission','permission_code','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('sys_permission','permission_name','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('sys_permission','permission_type','varchar(30)','NO','api','',0);
  CALL gc_sql_validate_column('sys_permission','resource_path','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('sys_permission','parent_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('sys_permission','sort_order','int','NO','0','',0);
  CALL gc_sql_validate_column('sys_permission','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('sys_permission','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('sys_role','role_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('sys_role','role_code','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('sys_role','role_name','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('sys_role','role_scope','varchar(50)','NO','school','',0);
  CALL gc_sql_validate_column('sys_role','is_system','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('sys_role','status','varchar(20)','NO','active','',0);
  CALL gc_sql_validate_column('sys_role','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('sys_role','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('sys_role_permission','id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('sys_role_permission','role_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('sys_role_permission','permission_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('sys_role_permission','data_scope','varchar(50)','NO','school','',0);
  CALL gc_sql_validate_column('sys_role_permission','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('tag_info','tag_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('tag_info','tag_name','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('tag_info','tag_type','enum(''theme'',''period'',''region'',''education'',''route'',''other'')','NO','other','',0);
  CALL gc_sql_validate_column('tag_info','description','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('tag_info','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('tag_info','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('task_resource_rel','id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('task_resource_rel','task_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('task_resource_rel','resource_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('task_resource_rel','sort_order','int','NO','0','',0);
  CALL gc_sql_validate_column('teacher_profile','teacher_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('teacher_profile','account_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teacher_profile','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teacher_profile','teacher_no','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('teacher_profile','teacher_name','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('teacher_profile','title','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('teacher_profile','status','varchar(20)','NO','active','',0);
  CALL gc_sql_validate_column('teacher_profile','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('teacher_profile','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('teacher_profile','profile_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('teacher_registration_invite','invite_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('teacher_registration_invite','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teacher_registration_invite','code_hash','char(64)','NO',NULL,'',0);
  CALL gc_sql_validate_column('teacher_registration_invite','status','enum(''active'',''revoked'')','NO','active','',0);
  CALL gc_sql_validate_column('teacher_registration_invite','expires_at','datetime','NO',NULL,'',0);
  CALL gc_sql_validate_column('teacher_registration_invite','max_uses','int','NO','50','',0);
  CALL gc_sql_validate_column('teacher_registration_invite','used_count','int','NO','0','',0);
  CALL gc_sql_validate_column('teacher_registration_invite','created_by_account_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teacher_registration_invite','revoked_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('teacher_registration_invite','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('teacher_registration_invite','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('teacher_resource_favorite','favorite_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('teacher_resource_favorite','teacher_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teacher_resource_favorite','resource_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teacher_resource_favorite','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('teacher_resource_favorite','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('teaching_activity_plan','plan_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('teaching_activity_plan','plan_code','varchar(50)','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','school_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','resource_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','theme','varchar(200)','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','activity_type','enum(''classroom'',''field_trip'',''volunteer_service'',''research_study'',''labor_practice'',''club_activity'',''school_based_course'',''other'')','NO','classroom','',0);
  CALL gc_sql_validate_column('teaching_activity_plan','suitable_grade','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','objective_text','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','activity_content','longtext','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','preparation_text','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','safety_text','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','expected_outcome','text','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','duration_minutes','int','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','source_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','owner_account_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','plan_payload','longtext','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','generation_source','varchar(30)','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','ai_run_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','published_status','varchar(20)','NO','draft','',0);
  CALL gc_sql_validate_column('teaching_activity_plan','review_status','enum(''draft'',''pending'',''approved'',''adopted'',''rejected'')','NO','draft','',1);
  CALL gc_sql_validate_column('teaching_activity_plan','is_active','tinyint(1)','NO','1','',0);
  CALL gc_sql_validate_column('teaching_activity_plan','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('teaching_activity_plan','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('teaching_activity_plan','class_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan','published_at','datetime','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan_resource','plan_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan_resource','resource_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_activity_plan_resource','sort_order','int','NO','0','',0);
  CALL gc_sql_validate_column('teaching_activity_plan_resource','is_primary','tinyint(1)','NO','0','',0);
  CALL gc_sql_validate_column('teaching_activity_plan_resource','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('teaching_plan_feedback','feedback_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('teaching_plan_feedback','generation_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_plan_feedback','teacher_account_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_plan_feedback','adopted','tinyint(1)','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_plan_feedback','rating','tinyint','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_plan_feedback','teacher_note','varchar(2000)','YES',NULL,'',0);
  CALL gc_sql_validate_column('teaching_plan_feedback','submitted_at','datetime','NO',NULL,'',0);
  CALL gc_sql_validate_column('teaching_plan_feedback','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('teaching_plan_feedback','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);
  CALL gc_sql_validate_column('teaching_plan_feedback','reason_codes_json','json','YES',NULL,'',0);
  CALL gc_sql_validate_column('user_profile','profile_id','bigint','NO',NULL,'auto_increment',0);
  CALL gc_sql_validate_column('user_profile','account_id','bigint','NO',NULL,'',0);
  CALL gc_sql_validate_column('user_profile','profile_type','varchar(30)','NO',NULL,'',0);
  CALL gc_sql_validate_column('user_profile','real_name','varchar(100)','NO',NULL,'',0);
  CALL gc_sql_validate_column('user_profile','gender','varchar(20)','YES',NULL,'',0);
  CALL gc_sql_validate_column('user_profile','phone','varchar(50)','YES',NULL,'',0);
  CALL gc_sql_validate_column('user_profile','email','varchar(100)','YES',NULL,'',0);
  CALL gc_sql_validate_column('user_profile','school_id','bigint','YES',NULL,'',0);
  CALL gc_sql_validate_column('user_profile','status','varchar(20)','NO','active','',0);
  CALL gc_sql_validate_column('user_profile','remark','varchar(255)','YES',NULL,'',0);
  CALL gc_sql_validate_column('user_profile','created_at','datetime','NO','CURRENT_TIMESTAMP','',0);
  CALL gc_sql_validate_column('user_profile','updated_at','datetime','NO','CURRENT_TIMESTAMP','on update',0);

  -- 补齐 administrative_region（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='administrative_region') THEN
    CALL gc_sql_column('administrative_region','region_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('administrative_region','parent_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('administrative_region','region_name','varchar(100) NOT NULL');
    CALL gc_sql_column('administrative_region','region_level','enum(''province'',''city'',''county'',''township'',''village'') NOT NULL');
    CALL gc_sql_column('administrative_region','adcode','varchar(20) DEFAULT NULL');
    CALL gc_sql_column('administrative_region','center_longitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('administrative_region','center_latitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('administrative_region','boundary_geojson','json DEFAULT NULL');
    CALL gc_sql_column('administrative_region','intro','text');
    CALL gc_sql_column('administrative_region','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('administrative_region','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 agent_action_idempotency（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_action_idempotency') THEN
    CALL gc_sql_column('agent_action_idempotency','action_id','varchar(96) NOT NULL');
    CALL gc_sql_column('agent_action_idempotency','turn_id','varchar(64) NOT NULL');
    CALL gc_sql_column('agent_action_idempotency','operation','varchar(160) NOT NULL');
    CALL gc_sql_column('agent_action_idempotency','request_hash','char(64) NOT NULL');
    CALL gc_sql_column('agent_action_idempotency','request_json','json DEFAULT NULL');
    CALL gc_sql_column('agent_action_idempotency','status','varchar(24) NOT NULL');
    CALL gc_sql_column('agent_action_idempotency','response_json','json DEFAULT NULL');
    CALL gc_sql_column('agent_action_idempotency','resource_reference','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('agent_action_idempotency','completed_at','datetime DEFAULT NULL');
    CALL gc_sql_column('agent_action_idempotency','payload_redacted_at','datetime DEFAULT NULL');
    CALL gc_sql_column('agent_action_idempotency','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('agent_action_idempotency','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 agent_action_outbox（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_action_outbox') THEN
    CALL gc_sql_column('agent_action_outbox','event_id','varchar(64) NOT NULL');
    CALL gc_sql_column('agent_action_outbox','action_id','varchar(96) NOT NULL');
    CALL gc_sql_column('agent_action_outbox','event_type','varchar(160) NOT NULL');
    CALL gc_sql_column('agent_action_outbox','payload_json','json NOT NULL');
    CALL gc_sql_column('agent_action_outbox','status','varchar(24) NOT NULL DEFAULT ''PENDING''');
    CALL gc_sql_column('agent_action_outbox','attempt_count','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('agent_action_outbox','next_attempt_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('agent_action_outbox','lease_owner','varchar(128) DEFAULT NULL');
    CALL gc_sql_column('agent_action_outbox','lease_expires_at','datetime DEFAULT NULL');
    CALL gc_sql_column('agent_action_outbox','published_at','datetime DEFAULT NULL');
    CALL gc_sql_column('agent_action_outbox','error_summary','varchar(1000) DEFAULT NULL');
    CALL gc_sql_column('agent_action_outbox','payload_redacted_at','datetime DEFAULT NULL');
    CALL gc_sql_column('agent_action_outbox','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('agent_action_outbox','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 agent_debug_event（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_debug_event') THEN
    CALL gc_sql_column('agent_debug_event','event_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''事件ID''');
    CALL gc_sql_column('agent_debug_event','debug_id','bigint NOT NULL COMMENT ''调试会话ID''');
    CALL gc_sql_column('agent_debug_event','event_name','varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''事件名称，如 retrieval.started、tool.completed''');
    CALL gc_sql_column('agent_debug_event','event_stage','varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''事件阶段：retrieval/model/tool/final''');
    CALL gc_sql_column('agent_debug_event','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''started'' COMMENT ''状态：started/success/failed''');
    CALL gc_sql_column('agent_debug_event','duration_ms','int DEFAULT NULL COMMENT ''节点耗时''');
    CALL gc_sql_column('agent_debug_event','payload_json','json DEFAULT NULL COMMENT ''事件详情、命中资源、工具参数等''');
    CALL gc_sql_column('agent_debug_event','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''事件时间''');
  END IF;

  -- 补齐 agent_debug_session（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_debug_session') THEN
    CALL gc_sql_column('agent_debug_session','debug_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''调试会话ID''');
    CALL gc_sql_column('agent_debug_session','question','text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''测试问题''');
    CALL gc_sql_column('agent_debug_session','scope_type','varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''调试范围类型''');
    CALL gc_sql_column('agent_debug_session','scope_id','bigint DEFAULT NULL COMMENT ''调试范围ID''');
    CALL gc_sql_column('agent_debug_session','model_id','varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''模型ID''');
    CALL gc_sql_column('agent_debug_session','provider','varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''模型供应商''');
    CALL gc_sql_column('agent_debug_session','answer','mediumtext COLLATE utf8mb4_unicode_ci COMMENT ''最终回答''');
    CALL gc_sql_column('agent_debug_session','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''success'' COMMENT ''状态：success/failed/stopped''');
    CALL gc_sql_column('agent_debug_session','duration_ms','int DEFAULT NULL COMMENT ''总耗时''');
    CALL gc_sql_column('agent_debug_session','created_by','bigint DEFAULT NULL COMMENT ''管理员账号ID''');
    CALL gc_sql_column('agent_debug_session','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
  END IF;

  -- 补齐 audit_log（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='audit_log') THEN
    CALL gc_sql_column('audit_log','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('audit_log','log_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('audit_log','entity_type','enum(''region'',''site'',''hero'',''event'',''memorial'',''story'',''tag'',''school'',''resource'',''activity_plan'') NOT NULL');
    CALL gc_sql_column('audit_log','entity_id','bigint NOT NULL');
    CALL gc_sql_column('audit_log','operation_type','enum(''insert'',''update'',''delete'',''review'') NOT NULL');
    CALL gc_sql_column('audit_log','operator_name','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('audit_log','change_summary','text');
    CALL gc_sql_column('audit_log','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 catalog_import_batch（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='catalog_import_batch') THEN
    CALL gc_sql_column('catalog_import_batch','batch_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('catalog_import_batch','file_name','varchar(255) NOT NULL');
    CALL gc_sql_column('catalog_import_batch','created_by','bigint DEFAULT NULL');
    CALL gc_sql_column('catalog_import_batch','status','varchar(32) NOT NULL');
    CALL gc_sql_column('catalog_import_batch','total_rows','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('catalog_import_batch','valid_rows','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('catalog_import_batch','invalid_rows','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('catalog_import_batch','duplicate_rows','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('catalog_import_batch','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('catalog_import_batch','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 catalog_import_row（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='catalog_import_row') THEN
    CALL gc_sql_column('catalog_import_row','row_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('catalog_import_row','batch_id','bigint NOT NULL');
    CALL gc_sql_column('catalog_import_row','sheet_name','varchar(64) NOT NULL');
    CALL gc_sql_column('catalog_import_row','row_no','int NOT NULL');
    CALL gc_sql_column('catalog_import_row','entity_type','varchar(32) NOT NULL');
    CALL gc_sql_column('catalog_import_row','payload_json','longtext NOT NULL');
    CALL gc_sql_column('catalog_import_row','validation_status','varchar(32) NOT NULL');
    CALL gc_sql_column('catalog_import_row','validation_message','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('catalog_import_row','imported_entity_id','bigint DEFAULT NULL');
    CALL gc_sql_column('catalog_import_row','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('catalog_import_row','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 catalog_projection_task（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='catalog_projection_task') THEN
    CALL gc_sql_column('catalog_projection_task','task_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('catalog_projection_task','entity_type','varchar(64) NOT NULL');
    CALL gc_sql_column('catalog_projection_task','entity_id','bigint NOT NULL');
    CALL gc_sql_column('catalog_projection_task','task_type','varchar(32) NOT NULL');
    CALL gc_sql_column('catalog_projection_task','status','varchar(32) NOT NULL');
    CALL gc_sql_column('catalog_projection_task','attempt_count','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('catalog_projection_task','last_error','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('catalog_projection_task','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('catalog_projection_task','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 class_info（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='class_info') THEN
    CALL gc_sql_column('class_info','class_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''班级ID''');
    CALL gc_sql_column('class_info','school_id','bigint NOT NULL COMMENT ''所属学校''');
    CALL gc_sql_column('class_info','class_name','varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''班级名称''');
    CALL gc_sql_column('class_info','grade_name','varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''年级''');
    CALL gc_sql_column('class_info','class_type','varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''administrative'' COMMENT ''班级类型：administrative/teaching''');
    CALL gc_sql_column('class_info','invite_code','varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''学生加入班级的邀请码''');
    CALL gc_sql_column('class_info','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''active'' COMMENT ''状态：active/archived''');
    CALL gc_sql_column('class_info','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL gc_sql_column('class_info','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''');
  END IF;

  -- 补齐 class_learning_task（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='class_learning_task') THEN
    CALL gc_sql_column('class_learning_task','task_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('class_learning_task','class_id','bigint NOT NULL');
    CALL gc_sql_column('class_learning_task','publisher_teacher_id','bigint DEFAULT NULL');
    CALL gc_sql_column('class_learning_task','title','varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL');
    CALL gc_sql_column('class_learning_task','description','text COLLATE utf8mb4_unicode_ci');
    CALL gc_sql_column('class_learning_task','published_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('class_learning_task','due_at','datetime DEFAULT NULL');
    CALL gc_sql_column('class_learning_task','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''published''');
    CALL gc_sql_column('class_learning_task','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('class_learning_task','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('class_learning_task','task_type','varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''red_culture_learning''');
    CALL gc_sql_column('class_learning_task','submission_rule','varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''text_required''');
    CALL gc_sql_column('class_learning_task','allow_late_submission','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('class_learning_task','start_at','datetime DEFAULT NULL');
    CALL gc_sql_column('class_learning_task','material_filename','varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('class_learning_task','material_storage_key','varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('class_learning_task','material_content_type','varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
  END IF;

  -- 补齐 class_member（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='class_member') THEN
    CALL gc_sql_column('class_member','id','bigint NOT NULL AUTO_INCREMENT COMMENT ''关系ID''');
    CALL gc_sql_column('class_member','class_id','bigint NOT NULL COMMENT ''班级ID''');
    CALL gc_sql_column('class_member','student_id','bigint NOT NULL COMMENT ''学生ID''');
    CALL gc_sql_column('class_member','join_source','varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''manual'' COMMENT ''加入来源：manual/import/invite''');
    CALL gc_sql_column('class_member','joined_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''加入时间''');
    CALL gc_sql_column('class_member','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''active'' COMMENT ''状态：active/removed''');
    CALL gc_sql_column('class_member','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL gc_sql_column('class_member','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''');
    CALL gc_sql_column('class_member','is_primary','tinyint(1) NOT NULL DEFAULT ''1'' COMMENT ''是否主班级''');
  END IF;

  -- 补齐 class_teacher（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='class_teacher') THEN
    CALL gc_sql_column('class_teacher','id','bigint NOT NULL AUTO_INCREMENT COMMENT ''关系ID''');
    CALL gc_sql_column('class_teacher','class_id','bigint NOT NULL COMMENT ''班级ID''');
    CALL gc_sql_column('class_teacher','teacher_id','bigint NOT NULL COMMENT ''教师ID''');
    CALL gc_sql_column('class_teacher','teacher_role','varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''subject_teacher'' COMMENT ''教师角色：head_teacher/subject_teacher''');
    CALL gc_sql_column('class_teacher','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''active'' COMMENT ''状态：active/inactive''');
    CALL gc_sql_column('class_teacher','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL gc_sql_column('class_teacher','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''');
  END IF;

  -- 补齐 data_source（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='data_source') THEN
    CALL gc_sql_column('data_source','source_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('data_source','source_name','varchar(200) NOT NULL');
    CALL gc_sql_column('data_source','source_type','enum(''government'',''encyclopedia'',''news'',''museum'',''paper'',''other'') NOT NULL');
    CALL gc_sql_column('data_source','organization_name','varchar(200) DEFAULT NULL');
    CALL gc_sql_column('data_source','base_url','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('data_source','reliability_level','tinyint NOT NULL DEFAULT ''3''');
    CALL gc_sql_column('data_source','license_note','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('data_source','crawl_allowed','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('data_source','last_crawled_at','datetime DEFAULT NULL');
    CALL gc_sql_column('data_source','remark','text');
    CALL gc_sql_column('data_source','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('data_source','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 entity_source_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='entity_source_rel') THEN
    CALL gc_sql_column('entity_source_rel','rel_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('entity_source_rel','entity_type','enum(''site'',''hero'',''event'',''memorial'',''story'',''school'',''resource'',''activity_plan'') NOT NULL');
    CALL gc_sql_column('entity_source_rel','entity_id','bigint NOT NULL');
    CALL gc_sql_column('entity_source_rel','source_id','bigint NOT NULL');
    CALL gc_sql_column('entity_source_rel','source_url','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('entity_source_rel','captured_at','datetime DEFAULT NULL');
    CALL gc_sql_column('entity_source_rel','source_excerpt','text');
    CALL gc_sql_column('entity_source_rel','credibility_score','tinyint NOT NULL DEFAULT ''3''');
    CALL gc_sql_column('entity_source_rel','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('entity_source_rel','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 hero_person（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='hero_person') THEN
    CALL gc_sql_column('hero_person','hero_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('hero_person','hero_code','varchar(50) NOT NULL');
    CALL gc_sql_column('hero_person','hero_name','varchar(100) NOT NULL');
    CALL gc_sql_column('hero_person','gender','enum(''male'',''female'',''unknown'') NOT NULL DEFAULT ''unknown''');
    CALL gc_sql_column('hero_person','birth_year','smallint DEFAULT NULL');
    CALL gc_sql_column('hero_person','death_year','smallint DEFAULT NULL');
    CALL gc_sql_column('hero_person','birth_date_text','varchar(50) DEFAULT NULL');
    CALL gc_sql_column('hero_person','death_date_text','varchar(50) DEFAULT NULL');
    CALL gc_sql_column('hero_person','native_place_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('hero_person','native_place_text','varchar(200) DEFAULT NULL');
    CALL gc_sql_column('hero_person','profile_summary','text');
    CALL gc_sql_column('hero_person','main_deeds','longtext');
    CALL gc_sql_column('hero_person','official_url','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('hero_person','review_status','enum(''draft'',''pending'',''approved'',''rejected'') NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('hero_person','is_active','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('hero_person','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('hero_person','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 historical_event（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='historical_event') THEN
    CALL gc_sql_column('historical_event','event_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('historical_event','event_code','varchar(50) NOT NULL');
    CALL gc_sql_column('historical_event','event_name','varchar(200) NOT NULL');
    CALL gc_sql_column('historical_event','primary_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('historical_event','event_time_text','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('historical_event','start_date','date DEFAULT NULL');
    CALL gc_sql_column('historical_event','end_date','date DEFAULT NULL');
    CALL gc_sql_column('historical_event','start_year','smallint DEFAULT NULL');
    CALL gc_sql_column('historical_event','end_year','smallint DEFAULT NULL');
    CALL gc_sql_column('historical_event','longitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('historical_event','latitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('historical_event','historical_significance','text');
    CALL gc_sql_column('historical_event','event_process','longtext');
    CALL gc_sql_column('historical_event','result_impact','text');
    CALL gc_sql_column('historical_event','official_url','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('historical_event','review_status','enum(''draft'',''pending'',''approved'',''rejected'') NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('historical_event','is_active','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('historical_event','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('historical_event','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 knowledge_document（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_document') THEN
    CALL gc_sql_column('knowledge_document','id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('knowledge_document','school_id','bigint DEFAULT NULL');
    CALL gc_sql_column('knowledge_document','title','varchar(255) NOT NULL');
    CALL gc_sql_column('knowledge_document','original_filename','varchar(255) NOT NULL');
    CALL gc_sql_column('knowledge_document','content_type','varchar(100) NOT NULL');
    CALL gc_sql_column('knowledge_document','file_size','bigint NOT NULL');
    CALL gc_sql_column('knowledge_document','sha256','char(64) DEFAULT NULL');
    CALL gc_sql_column('knowledge_document','object_key','varchar(512) NOT NULL');
    CALL gc_sql_column('knowledge_document','markdown_object_key','varchar(512) DEFAULT NULL');
    CALL gc_sql_column('knowledge_document','status','varchar(24) NOT NULL DEFAULT ''PENDING''');
    CALL gc_sql_column('knowledge_document','published_at','datetime DEFAULT NULL');
    CALL gc_sql_column('knowledge_document','indexed_at','datetime DEFAULT NULL');
    CALL gc_sql_column('knowledge_document','created_by','bigint NOT NULL');
    CALL gc_sql_column('knowledge_document','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('knowledge_document','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 knowledge_document_image（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_document_image') THEN
    CALL gc_sql_column('knowledge_document_image','id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('knowledge_document_image','document_id','bigint NOT NULL');
    CALL gc_sql_column('knowledge_document_image','sha256','char(64) NOT NULL');
    CALL gc_sql_column('knowledge_document_image','object_key','varchar(512) NOT NULL');
    CALL gc_sql_column('knowledge_document_image','alt_text','varchar(1000) DEFAULT NULL');
    CALL gc_sql_column('knowledge_document_image','description','mediumtext');
    CALL gc_sql_column('knowledge_document_image','status','varchar(24) NOT NULL DEFAULT ''PENDING''');
    CALL gc_sql_column('knowledge_document_image','model','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('knowledge_document_image','error_summary','varchar(1000) DEFAULT NULL');
    CALL gc_sql_column('knowledge_document_image','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('knowledge_document_image','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 knowledge_ingest_job（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_ingest_job') THEN
    CALL gc_sql_column('knowledge_ingest_job','id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('knowledge_ingest_job','document_id','bigint NOT NULL');
    CALL gc_sql_column('knowledge_ingest_job','status','varchar(24) NOT NULL DEFAULT ''PENDING''');
    CALL gc_sql_column('knowledge_ingest_job','current_node','varchar(40) NOT NULL DEFAULT ''VALIDATE''');
    CALL gc_sql_column('knowledge_ingest_job','retry_count','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('knowledge_ingest_job','error_summary','varchar(1000) DEFAULT NULL');
    CALL gc_sql_column('knowledge_ingest_job','metadata_json','json DEFAULT NULL');
    CALL gc_sql_column('knowledge_ingest_job','restart_from','varchar(40) DEFAULT NULL');
    CALL gc_sql_column('knowledge_ingest_job','started_at','datetime DEFAULT NULL');
    CALL gc_sql_column('knowledge_ingest_job','finished_at','datetime DEFAULT NULL');
    CALL gc_sql_column('knowledge_ingest_job','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('knowledge_ingest_job','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('knowledge_ingest_job','generation','bigint NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('knowledge_ingest_job','execution_attempts','int NOT NULL DEFAULT ''0''');
  END IF;

  -- 补齐 knowledge_ingest_outbox（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_ingest_outbox') THEN
    CALL gc_sql_column('knowledge_ingest_outbox','event_id','char(36) NOT NULL');
    CALL gc_sql_column('knowledge_ingest_outbox','job_id','bigint NOT NULL');
    CALL gc_sql_column('knowledge_ingest_outbox','document_id','bigint NOT NULL');
    CALL gc_sql_column('knowledge_ingest_outbox','generation','bigint NOT NULL');
    CALL gc_sql_column('knowledge_ingest_outbox','status','varchar(24) NOT NULL DEFAULT ''PENDING''');
    CALL gc_sql_column('knowledge_ingest_outbox','attempt_count','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('knowledge_ingest_outbox','next_attempt_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('knowledge_ingest_outbox','lease_owner','char(36) DEFAULT NULL');
    CALL gc_sql_column('knowledge_ingest_outbox','lease_expires_at','datetime DEFAULT NULL');
    CALL gc_sql_column('knowledge_ingest_outbox','error_summary','varchar(1000) DEFAULT NULL');
    CALL gc_sql_column('knowledge_ingest_outbox','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('knowledge_ingest_outbox','published_at','datetime DEFAULT NULL');
  END IF;

  -- 补齐 local_edu_resource（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='local_edu_resource') THEN
    CALL gc_sql_column('local_edu_resource','resource_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('local_edu_resource','resource_code','varchar(50) NOT NULL');
    CALL gc_sql_column('local_edu_resource','resource_name','varchar(200) NOT NULL');
    CALL gc_sql_column('local_edu_resource','resource_category','enum(''red_culture'',''intangible_culture'',''traditional_culture'',''local_history'',''public_culture'',''labor_education'',''public_welfare'',''ecological_civilization'',''patriotism_base'',''social_practice'',''other'') NOT NULL DEFAULT ''other''');
    CALL gc_sql_column('local_edu_resource','resource_subcategory','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','county_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','township_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','address','varchar(300) DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','longitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','latitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','organization_name','varchar(200) DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','contact_phone','varchar(50) DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','opening_time_desc','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','reservation_required','tinyint(1) NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('local_edu_resource','recommended_visit_minutes','int DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','intro','text');
    CALL gc_sql_column('local_edu_resource','education_value','text');
    CALL gc_sql_column('local_edu_resource','activity_suggestion','longtext');
    CALL gc_sql_column('local_edu_resource','target_grade','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','safety_note','text');
    CALL gc_sql_column('local_edu_resource','source_id','bigint DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','review_status','enum(''draft'',''pending'',''approved'',''rejected'') NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('local_edu_resource','is_active','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('local_edu_resource','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('local_edu_resource','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('local_edu_resource','external_provider','varchar(30) DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','external_place_id','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('local_edu_resource','source_checked_at','datetime DEFAULT NULL');
  END IF;

  -- 补齐 memorial_hall（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='memorial_hall') THEN
    CALL gc_sql_column('memorial_hall','memorial_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('memorial_hall','memorial_code','varchar(50) NOT NULL');
    CALL gc_sql_column('memorial_hall','memorial_name','varchar(200) NOT NULL');
    CALL gc_sql_column('memorial_hall','region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('memorial_hall','address','varchar(300) DEFAULT NULL');
    CALL gc_sql_column('memorial_hall','longitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('memorial_hall','latitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('memorial_hall','exhibition_content','longtext');
    CALL gc_sql_column('memorial_hall','intro','text');
    CALL gc_sql_column('memorial_hall','opening_time_desc','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('memorial_hall','ticket_info','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('memorial_hall','contact_phone','varchar(50) DEFAULT NULL');
    CALL gc_sql_column('memorial_hall','official_url','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('memorial_hall','review_status','enum(''draft'',''pending'',''approved'',''rejected'') NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('memorial_hall','is_active','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('memorial_hall','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('memorial_hall','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 memorial_hero_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='memorial_hero_rel') THEN
    CALL gc_sql_column('memorial_hero_rel','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('memorial_hero_rel','rel_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('memorial_hero_rel','memorial_id','bigint NOT NULL');
    CALL gc_sql_column('memorial_hero_rel','hero_id','bigint NOT NULL');
    CALL gc_sql_column('memorial_hero_rel','relation_type','enum(''commemorates'',''exhibits'',''related_to'') NOT NULL DEFAULT ''commemorates''');
    CALL gc_sql_column('memorial_hero_rel','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 rag_index_job（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='rag_index_job') THEN
    CALL gc_sql_column('rag_index_job','job_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''索引任务ID''');
    CALL gc_sql_column('rag_index_job','job_type','varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''任务类型：full/resource/chunk''');
    CALL gc_sql_column('rag_index_job','target_entity_type','varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''目标实体类型''');
    CALL gc_sql_column('rag_index_job','target_entity_id','bigint DEFAULT NULL COMMENT ''目标实体ID''');
    CALL gc_sql_column('rag_index_job','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''pending'' COMMENT ''状态：pending/running/success/failed''');
    CALL gc_sql_column('rag_index_job','total_chunks','int NOT NULL DEFAULT ''0'' COMMENT ''总分块数''');
    CALL gc_sql_column('rag_index_job','indexed_chunks','int NOT NULL DEFAULT ''0'' COMMENT ''成功分块数''');
    CALL gc_sql_column('rag_index_job','failed_chunks','int NOT NULL DEFAULT ''0'' COMMENT ''失败分块数''');
    CALL gc_sql_column('rag_index_job','started_by','bigint DEFAULT NULL COMMENT ''操作人账号ID''');
    CALL gc_sql_column('rag_index_job','started_at','datetime DEFAULT NULL COMMENT ''开始时间''');
    CALL gc_sql_column('rag_index_job','finished_at','datetime DEFAULT NULL COMMENT ''结束时间''');
    CALL gc_sql_column('rag_index_job','error_message','text COLLATE utf8mb4_unicode_ci COMMENT ''失败原因''');
    CALL gc_sql_column('rag_index_job','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL gc_sql_column('rag_index_job','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''');
  END IF;

  -- 补齐 rag_retrieval_test_log（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='rag_retrieval_test_log') THEN
    CALL gc_sql_column('rag_retrieval_test_log','test_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''测试ID''');
    CALL gc_sql_column('rag_retrieval_test_log','query_text','text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''管理员输入的问题''');
    CALL gc_sql_column('rag_retrieval_test_log','scope_type','varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''GLOBAL'' COMMENT ''范围类型：SCHOOL/RESOURCE/REGION/GLOBAL''');
    CALL gc_sql_column('rag_retrieval_test_log','scope_id','bigint DEFAULT NULL COMMENT ''范围ID''');
    CALL gc_sql_column('rag_retrieval_test_log','top_k','int NOT NULL DEFAULT ''5'' COMMENT ''召回数量''');
    CALL gc_sql_column('rag_retrieval_test_log','retrieval_status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''success'' COMMENT ''检索状态：success/empty/failed''');
    CALL gc_sql_column('rag_retrieval_test_log','result_json','json DEFAULT NULL COMMENT ''召回片段、分数、来源、引用编号''');
    CALL gc_sql_column('rag_retrieval_test_log','created_by','bigint DEFAULT NULL COMMENT ''测试人账号ID''');
    CALL gc_sql_column('rag_retrieval_test_log','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''测试时间''');
  END IF;

  -- 补齐 rag_web_source（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='rag_web_source') THEN
    CALL gc_sql_column('rag_web_source','source_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('rag_web_source','display_name','varchar(120) NOT NULL');
    CALL gc_sql_column('rag_web_source','domain','varchar(255) NOT NULL');
    CALL gc_sql_column('rag_web_source','enabled','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('rag_web_source','sort_order','int NOT NULL DEFAULT ''100''');
    CALL gc_sql_column('rag_web_source','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('rag_web_source','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 red_site（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='red_site') THEN
    CALL gc_sql_column('red_site','site_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('red_site','site_code','varchar(50) NOT NULL');
    CALL gc_sql_column('red_site','site_name','varchar(200) NOT NULL');
    CALL gc_sql_column('red_site','region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('red_site','address','varchar(300) DEFAULT NULL');
    CALL gc_sql_column('red_site','longitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('red_site','latitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('red_site','established_date','date DEFAULT NULL');
    CALL gc_sql_column('red_site','established_year','smallint DEFAULT NULL');
    CALL gc_sql_column('red_site','site_level','enum(''national'',''provincial'',''municipal'',''county'',''other'') NOT NULL DEFAULT ''other''');
    CALL gc_sql_column('red_site','protection_level','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('red_site','historical_background','text');
    CALL gc_sql_column('red_site','intro','text');
    CALL gc_sql_column('red_site','opening_time_desc','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('red_site','suggested_visit_minutes','int DEFAULT NULL');
    CALL gc_sql_column('red_site','official_url','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('red_site','review_status','enum(''draft'',''pending'',''approved'',''rejected'') NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('red_site','is_active','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('red_site','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('red_site','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 red_story（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='red_story') THEN
    CALL gc_sql_column('red_story','story_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('red_story','story_code','varchar(50) NOT NULL');
    CALL gc_sql_column('red_story','story_title','varchar(200) NOT NULL');
    CALL gc_sql_column('red_story','related_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('red_story','age_group','enum(''primary'',''middle'',''high'',''college'',''general'') NOT NULL DEFAULT ''general''');
    CALL gc_sql_column('red_story','summary','text');
    CALL gc_sql_column('red_story','story_content','longtext NOT NULL');
    CALL gc_sql_column('red_story','source_id','bigint DEFAULT NULL');
    CALL gc_sql_column('red_story','review_status','enum(''draft'',''pending'',''approved'',''rejected'') NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('red_story','is_active','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('red_story','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('red_story','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 resource_media（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='resource_media') THEN
    CALL gc_sql_column('resource_media','media_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('resource_media','entity_type','enum(''site'',''hero'',''event'',''memorial'',''story'',''school'',''resource'',''activity_plan'') NOT NULL');
    CALL gc_sql_column('resource_media','entity_id','bigint NOT NULL');
    CALL gc_sql_column('resource_media','media_type','enum(''image'',''video'',''audio'',''document'',''link'') NOT NULL DEFAULT ''image''');
    CALL gc_sql_column('resource_media','media_title','varchar(200) DEFAULT NULL');
    CALL gc_sql_column('resource_media','media_url','varchar(500) NOT NULL');
    CALL gc_sql_column('resource_media','cover_url','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('resource_media','description','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('resource_media','source_id','bigint DEFAULT NULL');
    CALL gc_sql_column('resource_media','copyright_note','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('resource_media','sort_order','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('resource_media','is_primary','tinyint(1) NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('resource_media','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('resource_media','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 school（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='school') THEN
    CALL gc_sql_column('school','school_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('school','school_code','varchar(50) NOT NULL');
    CALL gc_sql_column('school','school_name','varchar(200) NOT NULL');
    CALL gc_sql_column('school','school_alias','varchar(200) DEFAULT NULL');
    CALL gc_sql_column('school','region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('school','province_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('school','city_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('school','county_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('school','township_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('school','village_region_id','bigint DEFAULT NULL');
    CALL gc_sql_column('school','school_level','enum(''kindergarten'',''primary'',''junior'',''senior'',''nine_year'',''twelve_year'',''vocational'',''special'',''other'') NOT NULL DEFAULT ''primary''');
    CALL gc_sql_column('school','school_type','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('school','school_nature','enum(''public'',''private'',''other'') NOT NULL DEFAULT ''public''');
    CALL gc_sql_column('school','is_rural_school','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('school','is_teaching_point','tinyint(1) NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('school','address','varchar(300) DEFAULT NULL');
    CALL gc_sql_column('school','postcode','varchar(20) DEFAULT NULL');
    CALL gc_sql_column('school','contact_phone','varchar(50) DEFAULT NULL');
    CALL gc_sql_column('school','principal_name','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('school','longitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('school','latitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('school','geo_source_type','enum(''amap_poi'',''manual'',''school_official'',''government_doc'',''satellite_fix'',''other'') NOT NULL DEFAULT ''government_doc''');
    CALL gc_sql_column('school','poi_name','varchar(200) DEFAULT NULL');
    CALL gc_sql_column('school','poi_address','varchar(300) DEFAULT NULL');
    CALL gc_sql_column('school','poi_type','varchar(200) DEFAULT NULL');
    CALL gc_sql_column('school','geo_confidence','enum(''high'',''medium'',''low'',''unknown'') NOT NULL DEFAULT ''unknown''');
    CALL gc_sql_column('school','geo_verified','tinyint(1) NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('school','intro','text');
    CALL gc_sql_column('school','source_id','bigint DEFAULT NULL');
    CALL gc_sql_column('school','review_status','enum(''draft'',''pending'',''approved'',''rejected'') NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('school','is_active','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('school','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('school','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('school','logo_url','varchar(255) DEFAULT NULL COMMENT ''学校Logo地址''');
    CALL gc_sql_column('school','website_url','varchar(255) DEFAULT NULL COMMENT ''学校官网地址''');
    CALL gc_sql_column('school','contact_email','varchar(100) DEFAULT NULL COMMENT ''联系邮箱''');
    CALL gc_sql_column('school','admin_contact_name','varchar(100) DEFAULT NULL COMMENT ''管理员联系人姓名''');
    CALL gc_sql_column('school','default_discovery_radius_km','decimal(8,2) DEFAULT NULL COMMENT ''默认周边资源发现半径''');
    CALL gc_sql_column('school','resource_calc_enabled','tinyint(1) NOT NULL DEFAULT ''1'' COMMENT ''是否启用周边资源自动计算''');
    CALL gc_sql_column('school','last_resource_calc_at','datetime DEFAULT NULL COMMENT ''最后一次资源计算时间''');
  END IF;

  -- 补齐 school_geo_record（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='school_geo_record') THEN
    CALL gc_sql_column('school_geo_record','geo_record_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('school_geo_record','school_id','bigint NOT NULL');
    CALL gc_sql_column('school_geo_record','longitude','decimal(10,7) NOT NULL');
    CALL gc_sql_column('school_geo_record','latitude','decimal(10,7) NOT NULL');
    CALL gc_sql_column('school_geo_record','source_type','enum(''amap_poi'',''manual'',''school_official'',''government_doc'',''satellite_fix'',''other'') NOT NULL DEFAULT ''amap_poi''');
    CALL gc_sql_column('school_geo_record','poi_name','varchar(200) DEFAULT NULL');
    CALL gc_sql_column('school_geo_record','poi_address','varchar(300) DEFAULT NULL');
    CALL gc_sql_column('school_geo_record','poi_type','varchar(200) DEFAULT NULL');
    CALL gc_sql_column('school_geo_record','confidence_level','enum(''high'',''medium'',''low'',''unknown'') NOT NULL DEFAULT ''unknown''');
    CALL gc_sql_column('school_geo_record','is_manual_reviewed','tinyint(1) NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('school_geo_record','review_result','enum(''pending'',''confirmed'',''corrected'',''rejected'') NOT NULL DEFAULT ''pending''');
    CALL gc_sql_column('school_geo_record','reviewer_name','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('school_geo_record','reviewed_at','datetime DEFAULT NULL');
    CALL gc_sql_column('school_geo_record','is_current','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('school_geo_record','remark','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('school_geo_record','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 school_resource_calc_run（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='school_resource_calc_run') THEN
    CALL gc_sql_column('school_resource_calc_run','run_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''计算批次ID''');
    CALL gc_sql_column('school_resource_calc_run','school_id','bigint NOT NULL COMMENT ''学校ID''');
    CALL gc_sql_column('school_resource_calc_run','radius_km','decimal(8,2) DEFAULT NULL COMMENT ''计算半径''');
    CALL gc_sql_column('school_resource_calc_run','calc_method','varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''distance'' COMMENT ''计算方式：distance/amap/ai/manual''');
    CALL gc_sql_column('school_resource_calc_run','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''running'' COMMENT ''状态：running/success/failed''');
    CALL gc_sql_column('school_resource_calc_run','candidate_count','int NOT NULL DEFAULT ''0'' COMMENT ''候选资源数''');
    CALL gc_sql_column('school_resource_calc_run','linked_count','int NOT NULL DEFAULT ''0'' COMMENT ''形成关联数''');
    CALL gc_sql_column('school_resource_calc_run','started_at','datetime DEFAULT NULL COMMENT ''开始时间''');
    CALL gc_sql_column('school_resource_calc_run','finished_at','datetime DEFAULT NULL COMMENT ''结束时间''');
    CALL gc_sql_column('school_resource_calc_run','error_message','text COLLATE utf8mb4_unicode_ci COMMENT ''失败原因''');
    CALL gc_sql_column('school_resource_calc_run','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL gc_sql_column('school_resource_calc_run','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''');
  END IF;

  -- 补齐 school_resource_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='school_resource_rel') THEN
    CALL gc_sql_column('school_resource_rel','rel_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('school_resource_rel','school_id','bigint NOT NULL');
    CALL gc_sql_column('school_resource_rel','resource_id','bigint NOT NULL');
    CALL gc_sql_column('school_resource_rel','relation_type','enum(''nearby'',''cooperation'',''practice'',''curriculum_support'',''volunteer_base'',''research_route'',''other'') NOT NULL DEFAULT ''nearby''');
    CALL gc_sql_column('school_resource_rel','distance_meters','int DEFAULT NULL');
    CALL gc_sql_column('school_resource_rel','recommended_travel_mode','enum(''walk'',''bike'',''bus'',''drive'',''mixed'',''unknown'') NOT NULL DEFAULT ''unknown''');
    CALL gc_sql_column('school_resource_rel','estimated_duration_minutes','int DEFAULT NULL');
    CALL gc_sql_column('school_resource_rel','reachability_level','enum(''near'',''medium'',''far'',''very_far'',''unknown'') NOT NULL DEFAULT ''unknown''');
    CALL gc_sql_column('school_resource_rel','priority_level','tinyint NOT NULL DEFAULT ''3''');
    CALL gc_sql_column('school_resource_rel','education_theme_summary','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('school_resource_rel','source_id','bigint DEFAULT NULL');
    CALL gc_sql_column('school_resource_rel','review_status','enum(''draft'',''pending'',''approved'',''rejected'') NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('school_resource_rel','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('school_resource_rel','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('school_resource_rel','calc_run_id','bigint DEFAULT NULL COMMENT ''计算批次ID''');
    CALL gc_sql_column('school_resource_rel','calc_method','varchar(50) DEFAULT NULL COMMENT ''计算方式：distance/amap/ai/manual''');
    CALL gc_sql_column('school_resource_rel','match_score','decimal(10,4) DEFAULT NULL COMMENT ''匹配分数''');
    CALL gc_sql_column('school_resource_rel','route_distance_meters','int DEFAULT NULL COMMENT ''路线距离，单位米''');
    CALL gc_sql_column('school_resource_rel','route_duration_minutes','int DEFAULT NULL COMMENT ''路线耗时，单位分钟''');
    CALL gc_sql_column('school_resource_rel','calculated_at','datetime DEFAULT NULL COMMENT ''计算时间''');
    CALL gc_sql_column('school_resource_rel','manual_locked','tinyint(1) NOT NULL DEFAULT ''0'' COMMENT ''是否人工锁定，防止自动刷新覆盖''');
    CALL gc_sql_column('school_resource_rel','last_verified_at','datetime DEFAULT NULL COMMENT ''最后核验时间''');
  END IF;

  -- 补齐 school_user_account（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='school_user_account') THEN
    CALL gc_sql_column('school_user_account','account_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('school_user_account','username','varchar(100) NOT NULL');
    CALL gc_sql_column('school_user_account','password_hash','varchar(255) NOT NULL');
    CALL gc_sql_column('school_user_account','role_code','varchar(50) NOT NULL DEFAULT ''school_admin''');
    CALL gc_sql_column('school_user_account','school_id','bigint DEFAULT NULL');
    CALL gc_sql_column('school_user_account','display_name','varchar(120) DEFAULT NULL');
    CALL gc_sql_column('school_user_account','contact_name','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('school_user_account','contact_phone','varchar(50) DEFAULT NULL');
    CALL gc_sql_column('school_user_account','real_name','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('school_user_account','email','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('school_user_account','account_type','varchar(30) DEFAULT NULL');
    CALL gc_sql_column('school_user_account','force_password_change','tinyint(1) NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('school_user_account','password_updated_at','datetime DEFAULT NULL');
    CALL gc_sql_column('school_user_account','status','enum(''pending_activation'',''active'',''disabled'') NOT NULL DEFAULT ''active''');
    CALL gc_sql_column('school_user_account','last_login_at','datetime DEFAULT NULL');
    CALL gc_sql_column('school_user_account','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('school_user_account','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('school_user_account','avatar_url','varchar(255) DEFAULT NULL COMMENT ''头像地址''');
    CALL gc_sql_column('school_user_account','employee_no','varchar(50) DEFAULT NULL COMMENT ''员工号/教师工号''');
    CALL gc_sql_column('school_user_account','student_no','varchar(50) DEFAULT NULL COMMENT ''学生学号''');
    CALL gc_sql_column('school_user_account','last_login_ip','varchar(45) DEFAULT NULL COMMENT ''最后登录IP''');
  END IF;

  -- 补齐 site_event_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='site_event_rel') THEN
    CALL gc_sql_column('site_event_rel','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('site_event_rel','rel_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('site_event_rel','site_id','bigint NOT NULL');
    CALL gc_sql_column('site_event_rel','event_id','bigint NOT NULL');
    CALL gc_sql_column('site_event_rel','relation_type','enum(''occurred_at'',''related_to'',''memorialized_at'') NOT NULL DEFAULT ''related_to''');
    CALL gc_sql_column('site_event_rel','importance_level','tinyint NOT NULL DEFAULT ''3''');
    CALL gc_sql_column('site_event_rel','remark','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('site_event_rel','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 site_hero_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='site_hero_rel') THEN
    CALL gc_sql_column('site_hero_rel','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('site_hero_rel','rel_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('site_hero_rel','site_id','bigint NOT NULL');
    CALL gc_sql_column('site_hero_rel','hero_id','bigint NOT NULL');
    CALL gc_sql_column('site_hero_rel','relation_type','enum(''born_in'',''fought_in'',''memorialized'',''visited'',''related_to'') NOT NULL DEFAULT ''related_to''');
    CALL gc_sql_column('site_hero_rel','importance_level','tinyint NOT NULL DEFAULT ''3''');
    CALL gc_sql_column('site_hero_rel','remark','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('site_hero_rel','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 story_entity_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='story_entity_rel') THEN
    CALL gc_sql_column('story_entity_rel','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('story_entity_rel','rel_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('story_entity_rel','story_id','bigint NOT NULL');
    CALL gc_sql_column('story_entity_rel','entity_type','enum(''site'',''hero'',''event'',''memorial'',''school'',''resource'') NOT NULL');
    CALL gc_sql_column('story_entity_rel','entity_id','bigint NOT NULL');
    CALL gc_sql_column('story_entity_rel','relation_type','enum(''about'',''mentions'',''teaches'') NOT NULL DEFAULT ''about''');
    CALL gc_sql_column('story_entity_rel','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 student_profile（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='student_profile') THEN
    CALL gc_sql_column('student_profile','student_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''学生ID''');
    CALL gc_sql_column('student_profile','account_id','bigint NOT NULL COMMENT ''关联 school_user_account''');
    CALL gc_sql_column('student_profile','school_id','bigint NOT NULL COMMENT ''所属学校''');
    CALL gc_sql_column('student_profile','student_no','varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''学号''');
    CALL gc_sql_column('student_profile','student_name','varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''学生姓名''');
    CALL gc_sql_column('student_profile','grade_name','varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''年级''');
    CALL gc_sql_column('student_profile','enrollment_year','int DEFAULT NULL COMMENT ''入学年份''');
    CALL gc_sql_column('student_profile','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''active'' COMMENT ''状态：active/graduated/transferred''');
    CALL gc_sql_column('student_profile','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL gc_sql_column('student_profile','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''');
    CALL gc_sql_column('student_profile','profile_id','bigint DEFAULT NULL COMMENT ''统一档案ID''');
  END IF;

  -- 补齐 student_resource_browse_history（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='student_resource_browse_history') THEN
    CALL gc_sql_column('student_resource_browse_history','browse_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('student_resource_browse_history','student_id','bigint NOT NULL');
    CALL gc_sql_column('student_resource_browse_history','resource_id','bigint NOT NULL');
    CALL gc_sql_column('student_resource_browse_history','viewed_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('student_resource_browse_history','view_count','int NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('student_resource_browse_history','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('student_resource_browse_history','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 student_task_attachment（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='student_task_attachment') THEN
    CALL gc_sql_column('student_task_attachment','attachment_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('student_task_attachment','submission_id','bigint NOT NULL');
    CALL gc_sql_column('student_task_attachment','original_filename','varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL');
    CALL gc_sql_column('student_task_attachment','storage_key','varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL');
    CALL gc_sql_column('student_task_attachment','content_type','varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL');
    CALL gc_sql_column('student_task_attachment','file_size','bigint NOT NULL');
    CALL gc_sql_column('student_task_attachment','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('student_task_attachment','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 student_task_progress（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='student_task_progress') THEN
    CALL gc_sql_column('student_task_progress','id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('student_task_progress','task_id','bigint NOT NULL');
    CALL gc_sql_column('student_task_progress','student_id','bigint NOT NULL');
    CALL gc_sql_column('student_task_progress','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''pending''');
    CALL gc_sql_column('student_task_progress','completed_at','datetime DEFAULT NULL');
    CALL gc_sql_column('student_task_progress','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('student_task_progress','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 student_task_review（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='student_task_review') THEN
    CALL gc_sql_column('student_task_review','review_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('student_task_review','submission_id','bigint NOT NULL');
    CALL gc_sql_column('student_task_review','teacher_id','bigint NOT NULL');
    CALL gc_sql_column('student_task_review','review_action','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL');
    CALL gc_sql_column('student_task_review','comment','text COLLATE utf8mb4_unicode_ci');
    CALL gc_sql_column('student_task_review','grade','varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('student_task_review','reviewed_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('student_task_review','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('student_task_review','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 student_task_submission（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='student_task_submission') THEN
    CALL gc_sql_column('student_task_submission','submission_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('student_task_submission','task_id','bigint NOT NULL');
    CALL gc_sql_column('student_task_submission','student_id','bigint NOT NULL');
    CALL gc_sql_column('student_task_submission','version_no','int NOT NULL');
    CALL gc_sql_column('student_task_submission','content','text COLLATE utf8mb4_unicode_ci');
    CALL gc_sql_column('student_task_submission','selected_resource_ids','text COLLATE utf8mb4_unicode_ci');
    CALL gc_sql_column('student_task_submission','submitted_at','datetime DEFAULT NULL');
    CALL gc_sql_column('student_task_submission','is_late','tinyint(1) NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('student_task_submission','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('student_task_submission','is_current','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('student_task_submission','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('student_task_submission','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 sys_account_role（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_account_role') THEN
    CALL gc_sql_column('sys_account_role','id','bigint NOT NULL AUTO_INCREMENT COMMENT ''关系ID''');
    CALL gc_sql_column('sys_account_role','account_id','bigint NOT NULL COMMENT ''账号ID''');
    CALL gc_sql_column('sys_account_role','role_id','bigint NOT NULL COMMENT ''角色ID''');
    CALL gc_sql_column('sys_account_role','data_scope','varchar(50) NOT NULL DEFAULT ''school'' COMMENT ''数据范围：all/school/class/self''');
    CALL gc_sql_column('sys_account_role','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
  END IF;

  -- 补齐 sys_permission（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_permission') THEN
    CALL gc_sql_column('sys_permission','permission_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''权限ID''');
    CALL gc_sql_column('sys_permission','permission_code','varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''权限编码，如 admin.school.read''');
    CALL gc_sql_column('sys_permission','permission_name','varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''权限名称''');
    CALL gc_sql_column('sys_permission','permission_type','varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''api'' COMMENT ''权限类型：menu/api/button/data''');
    CALL gc_sql_column('sys_permission','resource_path','varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''页面路径或接口路径''');
    CALL gc_sql_column('sys_permission','parent_id','bigint DEFAULT NULL COMMENT ''上级权限ID''');
    CALL gc_sql_column('sys_permission','sort_order','int NOT NULL DEFAULT ''0'' COMMENT ''排序''');
    CALL gc_sql_column('sys_permission','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL gc_sql_column('sys_permission','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''');
  END IF;

  -- 补齐 sys_role（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_role') THEN
    CALL gc_sql_column('sys_role','role_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''角色ID''');
    CALL gc_sql_column('sys_role','role_code','varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''角色编码，如 platform_admin、teacher、student''');
    CALL gc_sql_column('sys_role','role_name','varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''角色名称''');
    CALL gc_sql_column('sys_role','role_scope','varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''school'' COMMENT ''角色范围：platform/school/class''');
    CALL gc_sql_column('sys_role','is_system','tinyint(1) NOT NULL DEFAULT ''0'' COMMENT ''是否系统内置角色''');
    CALL gc_sql_column('sys_role','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''active'' COMMENT ''状态：active/inactive''');
    CALL gc_sql_column('sys_role','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL gc_sql_column('sys_role','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''');
  END IF;

  -- 补齐 sys_role_permission（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_role_permission') THEN
    CALL gc_sql_column('sys_role_permission','id','bigint NOT NULL AUTO_INCREMENT COMMENT ''关系ID''');
    CALL gc_sql_column('sys_role_permission','role_id','bigint NOT NULL COMMENT ''角色ID''');
    CALL gc_sql_column('sys_role_permission','permission_id','bigint NOT NULL COMMENT ''权限ID''');
    CALL gc_sql_column('sys_role_permission','data_scope','varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''school'' COMMENT ''数据范围：all/school/class/self''');
    CALL gc_sql_column('sys_role_permission','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
  END IF;

  -- 补齐 tag_info（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='tag_info') THEN
    CALL gc_sql_column('tag_info','tag_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('tag_info','tag_name','varchar(100) NOT NULL');
    CALL gc_sql_column('tag_info','tag_type','enum(''theme'',''period'',''region'',''education'',''route'',''other'') NOT NULL DEFAULT ''other''');
    CALL gc_sql_column('tag_info','description','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('tag_info','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('tag_info','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 task_resource_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='task_resource_rel') THEN
    CALL gc_sql_column('task_resource_rel','id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('task_resource_rel','task_id','bigint NOT NULL');
    CALL gc_sql_column('task_resource_rel','resource_id','bigint NOT NULL');
    CALL gc_sql_column('task_resource_rel','sort_order','int NOT NULL DEFAULT ''0''');
  END IF;

  -- 补齐 teacher_profile（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='teacher_profile') THEN
    CALL gc_sql_column('teacher_profile','teacher_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''教师ID''');
    CALL gc_sql_column('teacher_profile','account_id','bigint NOT NULL COMMENT ''关联 school_user_account''');
    CALL gc_sql_column('teacher_profile','school_id','bigint NOT NULL COMMENT ''所属学校''');
    CALL gc_sql_column('teacher_profile','teacher_no','varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''教师工号''');
    CALL gc_sql_column('teacher_profile','teacher_name','varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''教师姓名''');
    CALL gc_sql_column('teacher_profile','title','varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''职称/岗位''');
    CALL gc_sql_column('teacher_profile','status','varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''active'' COMMENT ''状态：active/inactive/left''');
    CALL gc_sql_column('teacher_profile','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL gc_sql_column('teacher_profile','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''');
    CALL gc_sql_column('teacher_profile','profile_id','bigint DEFAULT NULL COMMENT ''统一档案ID''');
  END IF;

  -- 补齐 teacher_registration_invite（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='teacher_registration_invite') THEN
    CALL gc_sql_column('teacher_registration_invite','invite_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('teacher_registration_invite','school_id','bigint NOT NULL');
    CALL gc_sql_column('teacher_registration_invite','code_hash','char(64) NOT NULL');
    CALL gc_sql_column('teacher_registration_invite','status','enum(''active'',''revoked'') NOT NULL DEFAULT ''active''');
    CALL gc_sql_column('teacher_registration_invite','expires_at','datetime NOT NULL');
    CALL gc_sql_column('teacher_registration_invite','max_uses','int NOT NULL DEFAULT ''50''');
    CALL gc_sql_column('teacher_registration_invite','used_count','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('teacher_registration_invite','created_by_account_id','bigint NOT NULL');
    CALL gc_sql_column('teacher_registration_invite','revoked_at','datetime DEFAULT NULL');
    CALL gc_sql_column('teacher_registration_invite','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('teacher_registration_invite','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 teacher_resource_favorite（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='teacher_resource_favorite') THEN
    CALL gc_sql_column('teacher_resource_favorite','favorite_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('teacher_resource_favorite','teacher_id','bigint NOT NULL');
    CALL gc_sql_column('teacher_resource_favorite','resource_id','bigint NOT NULL');
    CALL gc_sql_column('teacher_resource_favorite','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('teacher_resource_favorite','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 teaching_activity_plan（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan') THEN
    CALL gc_sql_column('teaching_activity_plan','plan_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('teaching_activity_plan','plan_code','varchar(50) NOT NULL');
    CALL gc_sql_column('teaching_activity_plan','school_id','bigint NOT NULL');
    CALL gc_sql_column('teaching_activity_plan','resource_id','bigint DEFAULT NULL');
    CALL gc_sql_column('teaching_activity_plan','theme','varchar(200) NOT NULL');
    CALL gc_sql_column('teaching_activity_plan','activity_type','enum(''classroom'',''field_trip'',''volunteer_service'',''research_study'',''labor_practice'',''club_activity'',''school_based_course'',''other'') NOT NULL DEFAULT ''classroom''');
    CALL gc_sql_column('teaching_activity_plan','suitable_grade','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('teaching_activity_plan','objective_text','text');
    CALL gc_sql_column('teaching_activity_plan','activity_content','longtext NOT NULL');
    CALL gc_sql_column('teaching_activity_plan','preparation_text','text');
    CALL gc_sql_column('teaching_activity_plan','safety_text','text');
    CALL gc_sql_column('teaching_activity_plan','expected_outcome','text');
    CALL gc_sql_column('teaching_activity_plan','duration_minutes','int DEFAULT NULL');
    CALL gc_sql_column('teaching_activity_plan','source_id','bigint DEFAULT NULL');
    CALL gc_sql_column('teaching_activity_plan','owner_account_id','bigint DEFAULT NULL');
    CALL gc_sql_column('teaching_activity_plan','plan_payload','longtext');
    CALL gc_sql_column('teaching_activity_plan','generation_source','varchar(30) DEFAULT NULL');
    CALL gc_sql_column('teaching_activity_plan','ai_run_id','bigint DEFAULT NULL');
    CALL gc_sql_column('teaching_activity_plan','published_status','varchar(20) NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('teaching_activity_plan','review_status','enum(''draft'',''pending'',''approved'',''adopted'',''rejected'') NOT NULL DEFAULT ''draft''');
    CALL gc_sql_column('teaching_activity_plan','is_active','tinyint(1) NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('teaching_activity_plan','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('teaching_activity_plan','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('teaching_activity_plan','class_id','bigint DEFAULT NULL COMMENT ''发布班级ID''');
    CALL gc_sql_column('teaching_activity_plan','published_at','datetime DEFAULT NULL COMMENT ''发布时间''');
  END IF;

  -- 补齐 teaching_activity_plan_resource（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan_resource') THEN
    CALL gc_sql_column('teaching_activity_plan_resource','plan_id','bigint NOT NULL');
    CALL gc_sql_column('teaching_activity_plan_resource','resource_id','bigint NOT NULL');
    CALL gc_sql_column('teaching_activity_plan_resource','sort_order','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('teaching_activity_plan_resource','is_primary','tinyint(1) NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('teaching_activity_plan_resource','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 user_profile（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='user_profile') THEN
    CALL gc_sql_column('user_profile','profile_id','bigint NOT NULL AUTO_INCREMENT COMMENT ''统一档案ID''');
    CALL gc_sql_column('user_profile','account_id','bigint NOT NULL COMMENT ''关联账号ID''');
    CALL gc_sql_column('user_profile','profile_type','varchar(30) NOT NULL COMMENT ''档案类型：admin/teacher/student/other''');
    CALL gc_sql_column('user_profile','real_name','varchar(100) NOT NULL COMMENT ''真实姓名''');
    CALL gc_sql_column('user_profile','gender','varchar(20) DEFAULT NULL COMMENT ''性别''');
    CALL gc_sql_column('user_profile','phone','varchar(50) DEFAULT NULL COMMENT ''联系电话''');
    CALL gc_sql_column('user_profile','email','varchar(100) DEFAULT NULL COMMENT ''邮箱''');
    CALL gc_sql_column('user_profile','school_id','bigint DEFAULT NULL COMMENT ''所属学校ID''');
    CALL gc_sql_column('user_profile','status','varchar(20) NOT NULL DEFAULT ''active'' COMMENT ''状态：active/inactive/left/graduated/transferred''');
    CALL gc_sql_column('user_profile','remark','varchar(255) DEFAULT NULL COMMENT ''备注''');
    CALL gc_sql_column('user_profile','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL gc_sql_column('user_profile','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''');
  END IF;

  -- 补齐 ai_teaching_plan_generation（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ai_teaching_plan_generation') THEN
    CALL gc_sql_column('ai_teaching_plan_generation','generation_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('ai_teaching_plan_generation','school_id','bigint NOT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','account_id','bigint NOT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','actor_role','varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','thread_id','varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','grade','varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','theme','varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','activity_type','varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','duration_minutes','int DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','practice_required','tinyint(1) DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','generation_status','varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','retrieval_status','varchar(24) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','llm_provider','varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','llm_model','varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','prompt_version','varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','prompt_run_id','varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','prompt_experiment','varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','prompt_variant','varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','request_json','json NOT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','response_json','json NOT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','saved_plan_id','bigint DEFAULT NULL');
    CALL gc_sql_column('ai_teaching_plan_generation','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('ai_teaching_plan_generation','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 auth_refresh_token（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='auth_refresh_token') THEN
    CALL gc_sql_column('auth_refresh_token','token_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('auth_refresh_token','account_id','bigint NOT NULL');
    CALL gc_sql_column('auth_refresh_token','token_hash','char(64) NOT NULL');
    CALL gc_sql_column('auth_refresh_token','token_family_id','varchar(64) NOT NULL');
    CALL gc_sql_column('auth_refresh_token','issued_at','datetime NOT NULL');
    CALL gc_sql_column('auth_refresh_token','expires_at','datetime NOT NULL');
    CALL gc_sql_column('auth_refresh_token','rotated_at','datetime DEFAULT NULL');
    CALL gc_sql_column('auth_refresh_token','revoked_at','datetime DEFAULT NULL');
    CALL gc_sql_column('auth_refresh_token','revoke_reason','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('auth_refresh_token','user_agent','varchar(512) DEFAULT NULL');
    CALL gc_sql_column('auth_refresh_token','client_ip','varchar(45) DEFAULT NULL');
    CALL gc_sql_column('auth_refresh_token','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('auth_refresh_token','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 content_chunk（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='content_chunk') THEN
    CALL gc_sql_column('content_chunk','chunk_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('content_chunk','entity_type','enum(''site'',''hero'',''event'',''memorial'',''story'',''school'',''resource'',''activity_plan'') NOT NULL');
    CALL gc_sql_column('content_chunk','entity_id','bigint NOT NULL');
    CALL gc_sql_column('content_chunk','chunk_title','varchar(200) DEFAULT NULL');
    CALL gc_sql_column('content_chunk','chunk_text','longtext NOT NULL');
    CALL gc_sql_column('content_chunk','chunk_index','int NOT NULL DEFAULT ''1''');
    CALL gc_sql_column('content_chunk','source_id','bigint DEFAULT NULL');
    CALL gc_sql_column('content_chunk','token_count','int DEFAULT NULL');
    CALL gc_sql_column('content_chunk','embedding_status','enum(''pending'',''done'',''failed'') NOT NULL DEFAULT ''pending''');
    CALL gc_sql_column('content_chunk','retrieval_text','longtext');
    CALL gc_sql_column('content_chunk','embedding_hash','char(64) DEFAULT NULL');
    CALL gc_sql_column('content_chunk','embedding_model','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('content_chunk','embedding_dimensions','int DEFAULT NULL');
    CALL gc_sql_column('content_chunk','embedding_index_version','varchar(32) DEFAULT NULL');
    CALL gc_sql_column('content_chunk','embedded_at','datetime DEFAULT NULL');
    CALL gc_sql_column('content_chunk','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('content_chunk','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('content_chunk','content_hash','varchar(64) DEFAULT NULL COMMENT ''内容哈希''');
    CALL gc_sql_column('content_chunk','qdrant_collection','varchar(100) DEFAULT NULL COMMENT ''Qdrant集合名''');
    CALL gc_sql_column('content_chunk','vector_point_id','varchar(100) DEFAULT NULL COMMENT ''向量库点位ID''');
    CALL gc_sql_column('content_chunk','embedding_error','text COMMENT ''向量化失败原因''');
  END IF;

  -- 补齐 entity_tag_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='entity_tag_rel') THEN
    CALL gc_sql_column('entity_tag_rel','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('entity_tag_rel','rel_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('entity_tag_rel','entity_type','enum(''site'',''hero'',''event'',''memorial'',''story'',''school'',''resource'',''activity_plan'') NOT NULL');
    CALL gc_sql_column('entity_tag_rel','entity_id','bigint NOT NULL');
    CALL gc_sql_column('entity_tag_rel','tag_id','bigint NOT NULL');
    CALL gc_sql_column('entity_tag_rel','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 event_hero_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='event_hero_rel') THEN
    CALL gc_sql_column('event_hero_rel','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('event_hero_rel','rel_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('event_hero_rel','event_id','bigint NOT NULL');
    CALL gc_sql_column('event_hero_rel','hero_id','bigint NOT NULL');
    CALL gc_sql_column('event_hero_rel','relation_type','enum(''participant'',''leader'',''witness'',''martyr'',''related_to'') NOT NULL DEFAULT ''participant''');
    CALL gc_sql_column('event_hero_rel','contribution_text','text');
    CALL gc_sql_column('event_hero_rel','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 knowledge_chunk（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_chunk') THEN
    CALL gc_sql_column('knowledge_chunk','id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('knowledge_chunk','document_id','bigint NOT NULL');
    CALL gc_sql_column('knowledge_chunk','chunk_order','int NOT NULL');
    CALL gc_sql_column('knowledge_chunk','title_path','varchar(1000) DEFAULT NULL');
    CALL gc_sql_column('knowledge_chunk','content','mediumtext NOT NULL');
    CALL gc_sql_column('knowledge_chunk','token_count','int NOT NULL');
    CALL gc_sql_column('knowledge_chunk','subject','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('knowledge_chunk','subject_type','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('knowledge_chunk','tags','json DEFAULT NULL');
    CALL gc_sql_column('knowledge_chunk','qdrant_point_id','varchar(64) DEFAULT NULL');
    CALL gc_sql_column('knowledge_chunk','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('knowledge_chunk','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 memorial_event_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='memorial_event_rel') THEN
    CALL gc_sql_column('memorial_event_rel','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('memorial_event_rel','rel_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('memorial_event_rel','memorial_id','bigint NOT NULL');
    CALL gc_sql_column('memorial_event_rel','event_id','bigint NOT NULL');
    CALL gc_sql_column('memorial_event_rel','relation_type','enum(''commemorates'',''exhibits'',''related_to'') NOT NULL DEFAULT ''commemorates''');
    CALL gc_sql_column('memorial_event_rel','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 memorial_site_rel（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='memorial_site_rel') THEN
    CALL gc_sql_column('memorial_site_rel','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('memorial_site_rel','rel_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('memorial_site_rel','memorial_id','bigint NOT NULL');
    CALL gc_sql_column('memorial_site_rel','site_id','bigint NOT NULL');
    CALL gc_sql_column('memorial_site_rel','relation_type','enum(''located_at'',''displays'',''related_to'') NOT NULL DEFAULT ''related_to''');
    CALL gc_sql_column('memorial_site_rel','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 resource_discovery_candidate（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='resource_discovery_candidate') THEN
    CALL gc_sql_column('resource_discovery_candidate','candidate_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('resource_discovery_candidate','school_id','bigint NOT NULL');
    CALL gc_sql_column('resource_discovery_candidate','provider','varchar(30) NOT NULL DEFAULT ''amap''');
    CALL gc_sql_column('resource_discovery_candidate','provider_place_id','varchar(100) NOT NULL');
    CALL gc_sql_column('resource_discovery_candidate','place_name','varchar(200) NOT NULL');
    CALL gc_sql_column('resource_discovery_candidate','address','varchar(300) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','longitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','latitude','decimal(10,7) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','provider_type_code','varchar(50) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','provider_type_name','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','contact_phone','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','opening_hours','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','distance_meters','int DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','raw_json','json DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','analysis_status','enum(''unanalyzed'',''completed'',''failed'') NOT NULL DEFAULT ''unanalyzed''');
    CALL gc_sql_column('resource_discovery_candidate','ideological_relevant','tinyint(1) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','ai_category','varchar(50) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','ai_subcategory','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','ai_confidence','decimal(4,3) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','ai_rationale','text');
    CALL gc_sql_column('resource_discovery_candidate','education_themes_json','json DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','target_grades','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','activity_suggestion','text');
    CALL gc_sql_column('resource_discovery_candidate','verification_notes','text');
    CALL gc_sql_column('resource_discovery_candidate','decision_status','enum(''pending'',''approved'',''rejected'') NOT NULL DEFAULT ''pending''');
    CALL gc_sql_column('resource_discovery_candidate','matched_resource_id','bigint DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','last_error','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','first_seen_at','datetime NOT NULL');
    CALL gc_sql_column('resource_discovery_candidate','last_seen_at','datetime NOT NULL');
    CALL gc_sql_column('resource_discovery_candidate','last_analyzed_at','datetime DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','reviewed_by','varchar(100) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','reviewed_at','datetime DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','review_remark','varchar(255) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_candidate','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('resource_discovery_candidate','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 resource_discovery_run（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='resource_discovery_run') THEN
    CALL gc_sql_column('resource_discovery_run','run_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('resource_discovery_run','school_id','bigint NOT NULL');
    CALL gc_sql_column('resource_discovery_run','radius_meters','int NOT NULL');
    CALL gc_sql_column('resource_discovery_run','provider','varchar(30) NOT NULL DEFAULT ''amap''');
    CALL gc_sql_column('resource_discovery_run','status','enum(''pending'',''running'',''completed'',''failed'') NOT NULL DEFAULT ''pending''');
    CALL gc_sql_column('resource_discovery_run','forced','tinyint(1) NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('resource_discovery_run','provider_count','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('resource_discovery_run','candidate_count','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('resource_discovery_run','analysis_count','int NOT NULL DEFAULT ''0''');
    CALL gc_sql_column('resource_discovery_run','error_message','varchar(500) DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_run','started_at','datetime DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_run','completed_at','datetime DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_run','cache_expires_at','datetime DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_run','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('resource_discovery_run','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 resource_discovery_run_item（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='resource_discovery_run_item') THEN
    CALL gc_sql_column('resource_discovery_run_item','run_id','bigint NOT NULL');
    CALL gc_sql_column('resource_discovery_run_item','candidate_id','bigint NOT NULL');
    CALL gc_sql_column('resource_discovery_run_item','result_rank','int NOT NULL');
    CALL gc_sql_column('resource_discovery_run_item','distance_meters','int DEFAULT NULL');
    CALL gc_sql_column('resource_discovery_run_item','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  END IF;

  -- 补齐 teaching_plan_feedback（保留额外历史字段及已有值）。
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='teaching_plan_feedback') THEN
    CALL gc_sql_column('teaching_plan_feedback','feedback_id','bigint NOT NULL AUTO_INCREMENT');
    CALL gc_sql_column('teaching_plan_feedback','generation_id','bigint NOT NULL');
    CALL gc_sql_column('teaching_plan_feedback','teacher_account_id','bigint NOT NULL');
    CALL gc_sql_column('teaching_plan_feedback','adopted','tinyint(1) NOT NULL');
    CALL gc_sql_column('teaching_plan_feedback','rating','tinyint NOT NULL');
    CALL gc_sql_column('teaching_plan_feedback','teacher_note','varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL');
    CALL gc_sql_column('teaching_plan_feedback','submitted_at','datetime NOT NULL');
    CALL gc_sql_column('teaching_plan_feedback','created_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
    CALL gc_sql_column('teaching_plan_feedback','updated_at','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
    CALL gc_sql_column('teaching_plan_feedback','reason_codes_json','json DEFAULT NULL');
  END IF;

  CREATE TABLE IF NOT EXISTS `administrative_region` (
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

  CREATE TABLE IF NOT EXISTS `agent_action_idempotency` (
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

  CREATE TABLE IF NOT EXISTS `agent_action_outbox` (
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

  CREATE TABLE IF NOT EXISTS `agent_debug_event` (
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

  CREATE TABLE IF NOT EXISTS `agent_debug_session` (
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

  CREATE TABLE IF NOT EXISTS `audit_log` (
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

  CREATE TABLE IF NOT EXISTS `catalog_import_batch` (
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

  CREATE TABLE IF NOT EXISTS `catalog_import_row` (
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

  CREATE TABLE IF NOT EXISTS `catalog_projection_task` (
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

  CREATE TABLE IF NOT EXISTS `class_info` (
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

  CREATE TABLE IF NOT EXISTS `class_learning_task` (
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

  CREATE TABLE IF NOT EXISTS `class_member` (
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

  CREATE TABLE IF NOT EXISTS `class_teacher` (
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

  CREATE TABLE IF NOT EXISTS `data_source` (
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

  CREATE TABLE IF NOT EXISTS `entity_source_rel` (
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

  CREATE TABLE IF NOT EXISTS `hero_person` (
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

  CREATE TABLE IF NOT EXISTS `historical_event` (
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

  CREATE TABLE IF NOT EXISTS `knowledge_document` (
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

  CREATE TABLE IF NOT EXISTS `knowledge_document_image` (
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

  CREATE TABLE IF NOT EXISTS `knowledge_ingest_job` (
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

  CREATE TABLE IF NOT EXISTS `knowledge_ingest_outbox` (
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

  CREATE TABLE IF NOT EXISTS `local_edu_resource` (
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

  CREATE TABLE IF NOT EXISTS `memorial_hall` (
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

  CREATE TABLE IF NOT EXISTS `memorial_hero_rel` (
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

  CREATE TABLE IF NOT EXISTS `rag_index_job` (
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

  CREATE TABLE IF NOT EXISTS `rag_retrieval_test_log` (
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

  CREATE TABLE IF NOT EXISTS `rag_web_source` (
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

  CREATE TABLE IF NOT EXISTS `red_site` (
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

  CREATE TABLE IF NOT EXISTS `red_story` (
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

  CREATE TABLE IF NOT EXISTS `resource_media` (
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

  CREATE TABLE IF NOT EXISTS `school` (
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

  CREATE TABLE IF NOT EXISTS `school_geo_record` (
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

  CREATE TABLE IF NOT EXISTS `school_resource_calc_run` (
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

  CREATE TABLE IF NOT EXISTS `school_resource_rel` (
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

  CREATE TABLE IF NOT EXISTS `school_user_account` (
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

  CREATE TABLE IF NOT EXISTS `site_event_rel` (
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

  CREATE TABLE IF NOT EXISTS `site_hero_rel` (
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

  CREATE TABLE IF NOT EXISTS `story_entity_rel` (
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

  CREATE TABLE IF NOT EXISTS `student_profile` (
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

  CREATE TABLE IF NOT EXISTS `student_resource_browse_history` (
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

  CREATE TABLE IF NOT EXISTS `student_task_attachment` (
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

  CREATE TABLE IF NOT EXISTS `student_task_progress` (
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

  CREATE TABLE IF NOT EXISTS `student_task_review` (
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

  CREATE TABLE IF NOT EXISTS `student_task_submission` (
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

  CREATE TABLE IF NOT EXISTS `sys_account_role` (
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

  CREATE TABLE IF NOT EXISTS `sys_permission` (
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

  CREATE TABLE IF NOT EXISTS `sys_role` (
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

  CREATE TABLE IF NOT EXISTS `sys_role_permission` (
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

  CREATE TABLE IF NOT EXISTS `tag_info` (
  `tag_id` bigint NOT NULL AUTO_INCREMENT,
  `tag_name` varchar(100) NOT NULL,
  `tag_type` enum('theme','period','region','education','route','other') NOT NULL DEFAULT 'other',
  `description` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`tag_id`),
  UNIQUE KEY `uk_tag_name_type` (`tag_name`,`tag_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE IF NOT EXISTS `task_resource_rel` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `resource_id` bigint NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_resource` (`task_id`,`resource_id`),
  KEY `idx_task_resource_resource` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE IF NOT EXISTS `teacher_profile` (
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

  CREATE TABLE IF NOT EXISTS `teacher_registration_invite` (
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

  CREATE TABLE IF NOT EXISTS `teacher_resource_favorite` (
  `favorite_id` bigint NOT NULL AUTO_INCREMENT,
  `teacher_id` bigint NOT NULL,
  `resource_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`favorite_id`),
  UNIQUE KEY `uk_teacher_resource_favorite` (`teacher_id`,`resource_id`),
  KEY `idx_teacher_resource_favorite_resource` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE IF NOT EXISTS `teaching_activity_plan` (
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

  CREATE TABLE IF NOT EXISTS `teaching_activity_plan_resource` (
  `plan_id` bigint NOT NULL,
  `resource_id` bigint NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `is_primary` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`plan_id`,`resource_id`),
  KEY `idx_plan_resource_resource` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE IF NOT EXISTS `user_profile` (
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

  CREATE TABLE IF NOT EXISTS `ai_teaching_plan_generation` (
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

  CREATE TABLE IF NOT EXISTS `auth_refresh_token` (
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

  CREATE TABLE IF NOT EXISTS `content_chunk` (
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

  CREATE TABLE IF NOT EXISTS `entity_tag_rel` (
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

  CREATE TABLE IF NOT EXISTS `event_hero_rel` (
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

  CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
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

  CREATE TABLE IF NOT EXISTS `memorial_event_rel` (
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

  CREATE TABLE IF NOT EXISTS `memorial_site_rel` (
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

  CREATE TABLE IF NOT EXISTS `resource_discovery_candidate` (
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

  CREATE TABLE IF NOT EXISTS `resource_discovery_run` (
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

  CREATE TABLE IF NOT EXISTS `resource_discovery_run_item` (
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

  CREATE TABLE IF NOT EXISTS `teaching_plan_feedback` (
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

  -- 删除旧“一校一账号”唯一约束前，先确保有普通索引承接外键。
  CALL gc_sql_index('school_user_account','idx_school_user_account_school_id','school_id',1,'BTREE','KEY `idx_school_user_account_school_id` (`school_id`)');
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='school_user_account' AND index_name='uk_school_user_account_school') THEN
    IF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='school_user_account' AND index_name='uk_school_user_account_school') <> 'school_id' THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected uk_school_user_account_school definition';
    END IF;
    ALTER TABLE school_user_account DROP INDEX uk_school_user_account_school;
  END IF;

  -- audit_log.entity_type：仅扩展已知枚举，不覆盖自定义枚举值。
  IF (SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type') = 'enum' THEN
    IF LOCATE('''region''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `audit_log` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''region'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''site''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `audit_log` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''site'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''hero''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `audit_log` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''hero'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''event''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `audit_log` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''event'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''memorial''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `audit_log` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''memorial'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''story''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `audit_log` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''story'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''tag''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `audit_log` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''tag'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''school''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `audit_log` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''school'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''resource''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `audit_log` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''resource'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''activity_plan''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `audit_log` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''activity_plan'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_log' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
  END IF;

  -- entity_source_rel.entity_type：仅扩展已知枚举，不覆盖自定义枚举值。
  IF (SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type') = 'enum' THEN
    IF LOCATE('''site''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_source_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''site'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''hero''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_source_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''hero'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''event''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_source_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''event'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''memorial''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_source_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''memorial'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''story''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_source_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''story'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''school''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_source_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''school'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''resource''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_source_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''resource'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''activity_plan''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_source_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''activity_plan'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_source_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
  END IF;

  -- resource_media.entity_type：仅扩展已知枚举，不覆盖自定义枚举值。
  IF (SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type') = 'enum' THEN
    IF LOCATE('''site''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `resource_media` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''site'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''hero''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `resource_media` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''hero'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''event''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `resource_media` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''event'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''memorial''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `resource_media` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''memorial'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''story''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `resource_media` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''story'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''school''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `resource_media` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''school'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''resource''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `resource_media` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''resource'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''activity_plan''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `resource_media` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''activity_plan'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_media' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
  END IF;

  -- story_entity_rel.entity_type：仅扩展已知枚举，不覆盖自定义枚举值。
  IF (SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type') = 'enum' THEN
    IF LOCATE('''site''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `story_entity_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''site'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''hero''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `story_entity_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''hero'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''event''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `story_entity_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''event'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''memorial''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `story_entity_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''memorial'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''school''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `story_entity_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''school'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''resource''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `story_entity_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''resource'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='story_entity_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
  END IF;

  -- teaching_activity_plan.review_status：仅扩展已知枚举，不覆盖自定义枚举值。
  IF (SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status') = 'enum' THEN
    IF LOCATE('''draft''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status'))=0 THEN
      SELECT CONCAT('ALTER TABLE `teaching_activity_plan` MODIFY COLUMN `review_status` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''draft'') NOT NULL DEFAULT ''draft''') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''pending''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status'))=0 THEN
      SELECT CONCAT('ALTER TABLE `teaching_activity_plan` MODIFY COLUMN `review_status` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''pending'') NOT NULL DEFAULT ''draft''') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''approved''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status'))=0 THEN
      SELECT CONCAT('ALTER TABLE `teaching_activity_plan` MODIFY COLUMN `review_status` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''approved'') NOT NULL DEFAULT ''draft''') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''adopted''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status'))=0 THEN
      SELECT CONCAT('ALTER TABLE `teaching_activity_plan` MODIFY COLUMN `review_status` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''adopted'') NOT NULL DEFAULT ''draft''') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''rejected''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status'))=0 THEN
      SELECT CONCAT('ALTER TABLE `teaching_activity_plan` MODIFY COLUMN `review_status` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''rejected'') NOT NULL DEFAULT ''draft''') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teaching_activity_plan' AND column_name='review_status';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
  END IF;

  -- content_chunk.entity_type：仅扩展已知枚举，不覆盖自定义枚举值。
  IF (SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type') = 'enum' THEN
    IF LOCATE('''site''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `content_chunk` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''site'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''hero''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `content_chunk` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''hero'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''event''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `content_chunk` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''event'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''memorial''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `content_chunk` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''memorial'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''story''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `content_chunk` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''story'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''school''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `content_chunk` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''school'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''resource''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `content_chunk` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''resource'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''activity_plan''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `content_chunk` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''activity_plan'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_chunk' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
  END IF;

  -- entity_tag_rel.entity_type：仅扩展已知枚举，不覆盖自定义枚举值。
  IF (SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type') = 'enum' THEN
    IF LOCATE('''site''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_tag_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''site'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''hero''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_tag_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''hero'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''event''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_tag_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''event'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''memorial''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_tag_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''memorial'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''story''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_tag_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''story'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''school''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_tag_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''school'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''resource''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_tag_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''resource'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
    IF LOCATE('''activity_plan''',(SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type'))=0 THEN
      SELECT CONCAT('ALTER TABLE `entity_tag_rel` MODIFY COLUMN `entity_type` ', LEFT(column_type,CHAR_LENGTH(column_type)-1), ',''activity_plan'') NOT NULL') INTO @gc_enum_ddl FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='entity_tag_rel' AND column_name='entity_type';
      CALL gc_sql_exec(@gc_enum_ddl); SET @gc_enum_ddl=NULL;
    END IF;
  END IF;
  CALL gc_sql_index('administrative_region','PRIMARY','region_id',0,'BTREE','PRIMARY KEY (`region_id`)');
  CALL gc_sql_index('administrative_region','uk_region_adcode','adcode',0,'BTREE','UNIQUE KEY `uk_region_adcode` (`adcode`)');
  CALL gc_sql_index('administrative_region','idx_region_parent','parent_region_id',1,'BTREE','KEY `idx_region_parent` (`parent_region_id`)');
  CALL gc_sql_index('administrative_region','idx_region_name_level','region_name,region_level',1,'BTREE','KEY `idx_region_name_level` (`region_name`,`region_level`)');
  CALL gc_sql_fk('administrative_region','fk_region_parent','parent_region_id','administrative_region','region_id','CONSTRAINT `fk_region_parent` FOREIGN KEY (`parent_region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_index('agent_action_idempotency','PRIMARY','action_id',0,'BTREE','PRIMARY KEY (`action_id`)');
  CALL gc_sql_index('agent_action_idempotency','idx_agent_action_idempotency_turn','turn_id,created_at',1,'BTREE','KEY `idx_agent_action_idempotency_turn` (`turn_id`,`created_at`)');
  CALL gc_sql_index('agent_action_idempotency','idx_agent_action_idempotency_cleanup','completed_at,payload_redacted_at',1,'BTREE','KEY `idx_agent_action_idempotency_cleanup` (`completed_at`,`payload_redacted_at`)');
  CALL gc_sql_index('agent_action_outbox','PRIMARY','event_id',0,'BTREE','PRIMARY KEY (`event_id`)');
  CALL gc_sql_index('agent_action_outbox','uk_agent_action_outbox_action_type','action_id,event_type',0,'BTREE','UNIQUE KEY `uk_agent_action_outbox_action_type` (`action_id`,`event_type`)');
  CALL gc_sql_index('agent_action_outbox','idx_agent_action_outbox_claim','status,next_attempt_at,lease_expires_at',1,'BTREE','KEY `idx_agent_action_outbox_claim` (`status`,`next_attempt_at`,`lease_expires_at`)');
  CALL gc_sql_index('agent_action_outbox','idx_agent_action_outbox_cleanup','published_at,payload_redacted_at',1,'BTREE','KEY `idx_agent_action_outbox_cleanup` (`published_at`,`payload_redacted_at`)');
  CALL gc_sql_fk('agent_action_outbox','fk_agent_action_outbox_action','action_id','agent_action_idempotency','action_id','CONSTRAINT `fk_agent_action_outbox_action` FOREIGN KEY (`action_id`) REFERENCES `agent_action_idempotency` (`action_id`) ON DELETE RESTRICT');
  CALL gc_sql_index('agent_debug_event','PRIMARY','event_id',0,'BTREE','PRIMARY KEY (`event_id`)');
  CALL gc_sql_index('agent_debug_event','idx_agent_debug_event_debug_id','debug_id',1,'BTREE','KEY `idx_agent_debug_event_debug_id` (`debug_id`)');
  CALL gc_sql_index('agent_debug_event','idx_agent_debug_event_name','event_name',1,'BTREE','KEY `idx_agent_debug_event_name` (`event_name`)');
  CALL gc_sql_index('agent_debug_event','idx_agent_debug_event_stage','event_stage',1,'BTREE','KEY `idx_agent_debug_event_stage` (`event_stage`)');
  CALL gc_sql_index('agent_debug_event','idx_agent_debug_event_status','status',1,'BTREE','KEY `idx_agent_debug_event_status` (`status`)');
  CALL gc_sql_index('agent_debug_event','idx_agent_debug_event_created_at','created_at',1,'BTREE','KEY `idx_agent_debug_event_created_at` (`created_at`)');
  CALL gc_sql_index('agent_debug_session','PRIMARY','debug_id',0,'BTREE','PRIMARY KEY (`debug_id`)');
  CALL gc_sql_index('agent_debug_session','idx_agent_debug_scope','scope_type,scope_id',1,'BTREE','KEY `idx_agent_debug_scope` (`scope_type`,`scope_id`)');
  CALL gc_sql_index('agent_debug_session','idx_agent_debug_status','status',1,'BTREE','KEY `idx_agent_debug_status` (`status`)');
  CALL gc_sql_index('agent_debug_session','idx_agent_debug_created_by','created_by',1,'BTREE','KEY `idx_agent_debug_created_by` (`created_by`)');
  CALL gc_sql_index('agent_debug_session','idx_agent_debug_created_at','created_at',1,'BTREE','KEY `idx_agent_debug_created_at` (`created_at`)');
  CALL gc_sql_index('audit_log','PRIMARY','log_id',0,'BTREE','PRIMARY KEY (`log_id`)');
  CALL gc_sql_index('audit_log','idx_audit_entity','entity_type,entity_id',1,'BTREE','KEY `idx_audit_entity` (`entity_type`,`entity_id`)');
  CALL gc_sql_index('audit_log','idx_audit_created_at','created_at',1,'BTREE','KEY `idx_audit_created_at` (`created_at`)');
  CALL gc_sql_index('catalog_import_batch','PRIMARY','batch_id',0,'BTREE','PRIMARY KEY (`batch_id`)');
  CALL gc_sql_index('catalog_import_row','PRIMARY','row_id',0,'BTREE','PRIMARY KEY (`row_id`)');
  CALL gc_sql_index('catalog_import_row','fk_catalog_import_row_batch','batch_id',1,'BTREE','KEY `fk_catalog_import_row_batch` (`batch_id`)');
  CALL gc_sql_fk('catalog_import_row','fk_catalog_import_row_batch','batch_id','catalog_import_batch','batch_id','CONSTRAINT `fk_catalog_import_row_batch` FOREIGN KEY (`batch_id`) REFERENCES `catalog_import_batch` (`batch_id`)');
  CALL gc_sql_index('catalog_projection_task','PRIMARY','task_id',0,'BTREE','PRIMARY KEY (`task_id`)');
  CALL gc_sql_index('catalog_projection_task','idx_catalog_projection_task_status','status,updated_at',1,'BTREE','KEY `idx_catalog_projection_task_status` (`status`,`updated_at`)');
  CALL gc_sql_index('class_info','PRIMARY','class_id',0,'BTREE','PRIMARY KEY (`class_id`)');
  CALL gc_sql_index('class_info','uk_class_invite_code','invite_code',0,'BTREE','UNIQUE KEY `uk_class_invite_code` (`invite_code`)');
  CALL gc_sql_index('class_info','idx_class_school_id','school_id',1,'BTREE','KEY `idx_class_school_id` (`school_id`)');
  CALL gc_sql_index('class_info','idx_class_grade_name','grade_name',1,'BTREE','KEY `idx_class_grade_name` (`grade_name`)');
  CALL gc_sql_index('class_info','idx_class_status','status',1,'BTREE','KEY `idx_class_status` (`status`)');
  CALL gc_sql_index('class_learning_task','PRIMARY','task_id',0,'BTREE','PRIMARY KEY (`task_id`)');
  CALL gc_sql_index('class_learning_task','idx_class_learning_task_class','class_id',1,'BTREE','KEY `idx_class_learning_task_class` (`class_id`)');
  CALL gc_sql_index('class_learning_task','idx_class_learning_task_status','status',1,'BTREE','KEY `idx_class_learning_task_status` (`status`)');
  CALL gc_sql_index('class_member','PRIMARY','id',0,'BTREE','PRIMARY KEY (`id`)');
  CALL gc_sql_index('class_member','uk_class_member','class_id,student_id',0,'BTREE','UNIQUE KEY `uk_class_member` (`class_id`,`student_id`)');
  CALL gc_sql_index('class_member','idx_class_member_class_id','class_id',1,'BTREE','KEY `idx_class_member_class_id` (`class_id`)');
  CALL gc_sql_index('class_member','idx_class_member_student_id','student_id',1,'BTREE','KEY `idx_class_member_student_id` (`student_id`)');
  CALL gc_sql_index('class_member','idx_class_member_status','status',1,'BTREE','KEY `idx_class_member_status` (`status`)');
  CALL gc_sql_index('class_member','idx_class_member_primary','is_primary',1,'BTREE','KEY `idx_class_member_primary` (`is_primary`)');
  CALL gc_sql_index('class_teacher','PRIMARY','id',0,'BTREE','PRIMARY KEY (`id`)');
  CALL gc_sql_index('class_teacher','uk_class_teacher_role','class_id,teacher_id,teacher_role',0,'BTREE','UNIQUE KEY `uk_class_teacher_role` (`class_id`,`teacher_id`,`teacher_role`)');
  CALL gc_sql_index('class_teacher','idx_class_teacher_class_id','class_id',1,'BTREE','KEY `idx_class_teacher_class_id` (`class_id`)');
  CALL gc_sql_index('class_teacher','idx_class_teacher_teacher_id','teacher_id',1,'BTREE','KEY `idx_class_teacher_teacher_id` (`teacher_id`)');
  CALL gc_sql_index('class_teacher','idx_class_teacher_status','status',1,'BTREE','KEY `idx_class_teacher_status` (`status`)');
  CALL gc_sql_index('data_source','PRIMARY','source_id',0,'BTREE','PRIMARY KEY (`source_id`)');
  CALL gc_sql_index('data_source','idx_source_type','source_type',1,'BTREE','KEY `idx_source_type` (`source_type`)');
  CALL gc_sql_index('data_source','idx_source_name','source_name',1,'BTREE','KEY `idx_source_name` (`source_name`)');
  CALL gc_sql_index('entity_source_rel','PRIMARY','rel_id',0,'BTREE','PRIMARY KEY (`rel_id`)');
  CALL gc_sql_index('entity_source_rel','uk_entity_source','entity_type,entity_id,source_id,source_url',0,'BTREE','UNIQUE KEY `uk_entity_source` (`entity_type`,`entity_id`,`source_id`,`source_url`(255))');
  CALL gc_sql_index('entity_source_rel','idx_entity_source_lookup','entity_type,entity_id',1,'BTREE','KEY `idx_entity_source_lookup` (`entity_type`,`entity_id`)');
  CALL gc_sql_index('entity_source_rel','idx_entity_source_source','source_id',1,'BTREE','KEY `idx_entity_source_source` (`source_id`)');
  CALL gc_sql_fk('entity_source_rel','fk_entity_source_rel_source','source_id','data_source','source_id','CONSTRAINT `fk_entity_source_rel_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)');
  CALL gc_sql_index('hero_person','PRIMARY','hero_id',0,'BTREE','PRIMARY KEY (`hero_id`)');
  CALL gc_sql_index('hero_person','uk_hero_code','hero_code',0,'BTREE','UNIQUE KEY `uk_hero_code` (`hero_code`)');
  CALL gc_sql_index('hero_person','idx_hero_name','hero_name',1,'BTREE','KEY `idx_hero_name` (`hero_name`)');
  CALL gc_sql_index('hero_person','idx_hero_birth_death','birth_year,death_year',1,'BTREE','KEY `idx_hero_birth_death` (`birth_year`,`death_year`)');
  CALL gc_sql_index('hero_person','idx_hero_region','native_place_region_id',1,'BTREE','KEY `idx_hero_region` (`native_place_region_id`)');
  CALL gc_sql_fk('hero_person','fk_hero_native_region','native_place_region_id','administrative_region','region_id','CONSTRAINT `fk_hero_native_region` FOREIGN KEY (`native_place_region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_index('historical_event','PRIMARY','event_id',0,'BTREE','PRIMARY KEY (`event_id`)');
  CALL gc_sql_index('historical_event','uk_event_code','event_code',0,'BTREE','UNIQUE KEY `uk_event_code` (`event_code`)');
  CALL gc_sql_index('historical_event','idx_event_name','event_name',1,'BTREE','KEY `idx_event_name` (`event_name`)');
  CALL gc_sql_index('historical_event','idx_event_time','start_date,end_date',1,'BTREE','KEY `idx_event_time` (`start_date`,`end_date`)');
  CALL gc_sql_index('historical_event','idx_event_region','primary_region_id',1,'BTREE','KEY `idx_event_region` (`primary_region_id`)');
  CALL gc_sql_index('historical_event','idx_event_geo','longitude,latitude',1,'BTREE','KEY `idx_event_geo` (`longitude`,`latitude`)');
  CALL gc_sql_fk('historical_event','fk_event_region','primary_region_id','administrative_region','region_id','CONSTRAINT `fk_event_region` FOREIGN KEY (`primary_region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_index('knowledge_document','PRIMARY','id',0,'BTREE','PRIMARY KEY (`id`)');
  CALL gc_sql_index('knowledge_document','idx_knowledge_document_scope_hash','school_id,sha256',1,'BTREE','KEY `idx_knowledge_document_scope_hash` (`school_id`,`sha256`)');
  CALL gc_sql_index('knowledge_document','idx_knowledge_document_status','status',1,'BTREE','KEY `idx_knowledge_document_status` (`status`)');
  CALL gc_sql_index('knowledge_document_image','PRIMARY','id',0,'BTREE','PRIMARY KEY (`id`)');
  CALL gc_sql_index('knowledge_document_image','uk_knowledge_image_document_hash','document_id,sha256',0,'BTREE','UNIQUE KEY `uk_knowledge_image_document_hash` (`document_id`,`sha256`)');
  CALL gc_sql_index('knowledge_document_image','idx_knowledge_image_hash','sha256',1,'BTREE','KEY `idx_knowledge_image_hash` (`sha256`)');
  CALL gc_sql_fk('knowledge_document_image','fk_knowledge_image_document','document_id','knowledge_document','id','CONSTRAINT `fk_knowledge_image_document` FOREIGN KEY (`document_id`) REFERENCES `knowledge_document` (`id`) ON DELETE CASCADE');
  CALL gc_sql_index('knowledge_ingest_job','PRIMARY','id',0,'BTREE','PRIMARY KEY (`id`)');
  CALL gc_sql_index('knowledge_ingest_job','uk_knowledge_ingest_job_document','document_id',0,'BTREE','UNIQUE KEY `uk_knowledge_ingest_job_document` (`document_id`)');
  CALL gc_sql_fk('knowledge_ingest_job','fk_knowledge_ingest_job_document','document_id','knowledge_document','id','CONSTRAINT `fk_knowledge_ingest_job_document` FOREIGN KEY (`document_id`) REFERENCES `knowledge_document` (`id`) ON DELETE CASCADE');
  CALL gc_sql_index('knowledge_ingest_outbox','PRIMARY','event_id',0,'BTREE','PRIMARY KEY (`event_id`)');
  CALL gc_sql_index('knowledge_ingest_outbox','uk_ingest_event_generation','job_id,generation',0,'BTREE','UNIQUE KEY `uk_ingest_event_generation` (`job_id`,`generation`)');
  CALL gc_sql_index('knowledge_ingest_outbox','idx_ingest_outbox_ready','status,next_attempt_at,lease_expires_at',1,'BTREE','KEY `idx_ingest_outbox_ready` (`status`,`next_attempt_at`,`lease_expires_at`)');
  CALL gc_sql_fk('knowledge_ingest_outbox','fk_ingest_outbox_job','job_id','knowledge_ingest_job','id','CONSTRAINT `fk_ingest_outbox_job` FOREIGN KEY (`job_id`) REFERENCES `knowledge_ingest_job` (`id`) ON DELETE CASCADE');
  CALL gc_sql_index('local_edu_resource','PRIMARY','resource_id',0,'BTREE','PRIMARY KEY (`resource_id`)');
  CALL gc_sql_index('local_edu_resource','uk_local_edu_resource_code','resource_code',0,'BTREE','UNIQUE KEY `uk_local_edu_resource_code` (`resource_code`)');
  CALL gc_sql_index('local_edu_resource','uk_local_resource_external_place','external_provider,external_place_id',0,'BTREE','UNIQUE KEY `uk_local_resource_external_place` (`external_provider`,`external_place_id`)');
  CALL gc_sql_index('local_edu_resource','fk_local_edu_resource_county_region','county_region_id',1,'BTREE','KEY `fk_local_edu_resource_county_region` (`county_region_id`)');
  CALL gc_sql_index('local_edu_resource','fk_local_edu_resource_township_region','township_region_id',1,'BTREE','KEY `fk_local_edu_resource_township_region` (`township_region_id`)');
  CALL gc_sql_index('local_edu_resource','fk_local_edu_resource_source','source_id',1,'BTREE','KEY `fk_local_edu_resource_source` (`source_id`)');
  CALL gc_sql_index('local_edu_resource','idx_local_edu_resource_name','resource_name',1,'BTREE','KEY `idx_local_edu_resource_name` (`resource_name`)');
  CALL gc_sql_index('local_edu_resource','idx_local_edu_resource_category','resource_category,resource_subcategory',1,'BTREE','KEY `idx_local_edu_resource_category` (`resource_category`,`resource_subcategory`)');
  CALL gc_sql_index('local_edu_resource','idx_local_edu_resource_region','region_id',1,'BTREE','KEY `idx_local_edu_resource_region` (`region_id`)');
  CALL gc_sql_index('local_edu_resource','idx_local_edu_resource_status','review_status,is_active',1,'BTREE','KEY `idx_local_edu_resource_status` (`review_status`,`is_active`)');
  CALL gc_sql_index('local_edu_resource','idx_local_edu_resource_geo','longitude,latitude',1,'BTREE','KEY `idx_local_edu_resource_geo` (`longitude`,`latitude`)');
  CALL gc_sql_fk('local_edu_resource','fk_local_edu_resource_county_region','county_region_id','administrative_region','region_id','CONSTRAINT `fk_local_edu_resource_county_region` FOREIGN KEY (`county_region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_fk('local_edu_resource','fk_local_edu_resource_region','region_id','administrative_region','region_id','CONSTRAINT `fk_local_edu_resource_region` FOREIGN KEY (`region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_fk('local_edu_resource','fk_local_edu_resource_source','source_id','data_source','source_id','CONSTRAINT `fk_local_edu_resource_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)');
  CALL gc_sql_fk('local_edu_resource','fk_local_edu_resource_township_region','township_region_id','administrative_region','region_id','CONSTRAINT `fk_local_edu_resource_township_region` FOREIGN KEY (`township_region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_index('memorial_hall','PRIMARY','memorial_id',0,'BTREE','PRIMARY KEY (`memorial_id`)');
  CALL gc_sql_index('memorial_hall','uk_memorial_code','memorial_code',0,'BTREE','UNIQUE KEY `uk_memorial_code` (`memorial_code`)');
  CALL gc_sql_index('memorial_hall','idx_memorial_name','memorial_name',1,'BTREE','KEY `idx_memorial_name` (`memorial_name`)');
  CALL gc_sql_index('memorial_hall','idx_memorial_region','region_id',1,'BTREE','KEY `idx_memorial_region` (`region_id`)');
  CALL gc_sql_index('memorial_hall','idx_memorial_geo','longitude,latitude',1,'BTREE','KEY `idx_memorial_geo` (`longitude`,`latitude`)');
  CALL gc_sql_fk('memorial_hall','fk_memorial_region','region_id','administrative_region','region_id','CONSTRAINT `fk_memorial_region` FOREIGN KEY (`region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_index('memorial_hero_rel','PRIMARY','rel_id',0,'BTREE','PRIMARY KEY (`rel_id`)');
  CALL gc_sql_index('memorial_hero_rel','uk_memorial_hero','memorial_id,hero_id,relation_type',0,'BTREE','UNIQUE KEY `uk_memorial_hero` (`memorial_id`,`hero_id`,`relation_type`)');
  CALL gc_sql_index('memorial_hero_rel','idx_memorial_hero_hero','hero_id',1,'BTREE','KEY `idx_memorial_hero_hero` (`hero_id`)');
  CALL gc_sql_fk('memorial_hero_rel','fk_memorial_hero_hero','hero_id','hero_person','hero_id','CONSTRAINT `fk_memorial_hero_hero` FOREIGN KEY (`hero_id`) REFERENCES `hero_person` (`hero_id`)');
  CALL gc_sql_fk('memorial_hero_rel','fk_memorial_hero_memorial','memorial_id','memorial_hall','memorial_id','CONSTRAINT `fk_memorial_hero_memorial` FOREIGN KEY (`memorial_id`) REFERENCES `memorial_hall` (`memorial_id`)');
  CALL gc_sql_index('rag_index_job','PRIMARY','job_id',0,'BTREE','PRIMARY KEY (`job_id`)');
  CALL gc_sql_index('rag_index_job','idx_rag_index_job_status','status',1,'BTREE','KEY `idx_rag_index_job_status` (`status`)');
  CALL gc_sql_index('rag_index_job','idx_rag_index_job_target','target_entity_type,target_entity_id',1,'BTREE','KEY `idx_rag_index_job_target` (`target_entity_type`,`target_entity_id`)');
  CALL gc_sql_index('rag_index_job','idx_rag_index_job_started_by','started_by',1,'BTREE','KEY `idx_rag_index_job_started_by` (`started_by`)');
  CALL gc_sql_index('rag_index_job','idx_rag_index_job_started_at','started_at',1,'BTREE','KEY `idx_rag_index_job_started_at` (`started_at`)');
  CALL gc_sql_index('rag_retrieval_test_log','PRIMARY','test_id',0,'BTREE','PRIMARY KEY (`test_id`)');
  CALL gc_sql_index('rag_retrieval_test_log','idx_retrieval_test_scope','scope_type,scope_id',1,'BTREE','KEY `idx_retrieval_test_scope` (`scope_type`,`scope_id`)');
  CALL gc_sql_index('rag_retrieval_test_log','idx_retrieval_test_status','retrieval_status',1,'BTREE','KEY `idx_retrieval_test_status` (`retrieval_status`)');
  CALL gc_sql_index('rag_retrieval_test_log','idx_retrieval_test_created_by','created_by',1,'BTREE','KEY `idx_retrieval_test_created_by` (`created_by`)');
  CALL gc_sql_index('rag_retrieval_test_log','idx_retrieval_test_created_at','created_at',1,'BTREE','KEY `idx_retrieval_test_created_at` (`created_at`)');
  CALL gc_sql_index('rag_web_source','PRIMARY','source_id',0,'BTREE','PRIMARY KEY (`source_id`)');
  CALL gc_sql_index('rag_web_source','uk_rag_web_source_domain','domain',0,'BTREE','UNIQUE KEY `uk_rag_web_source_domain` (`domain`)');
  CALL gc_sql_index('rag_web_source','idx_rag_web_source_enabled_sort','enabled,sort_order',1,'BTREE','KEY `idx_rag_web_source_enabled_sort` (`enabled`,`sort_order`)');
  CALL gc_sql_index('red_site','PRIMARY','site_id',0,'BTREE','PRIMARY KEY (`site_id`)');
  CALL gc_sql_index('red_site','uk_site_code','site_code',0,'BTREE','UNIQUE KEY `uk_site_code` (`site_code`)');
  CALL gc_sql_index('red_site','idx_site_name','site_name',1,'BTREE','KEY `idx_site_name` (`site_name`)');
  CALL gc_sql_index('red_site','idx_site_region','region_id',1,'BTREE','KEY `idx_site_region` (`region_id`)');
  CALL gc_sql_index('red_site','idx_site_status','review_status,is_active',1,'BTREE','KEY `idx_site_status` (`review_status`,`is_active`)');
  CALL gc_sql_index('red_site','idx_site_geo','longitude,latitude',1,'BTREE','KEY `idx_site_geo` (`longitude`,`latitude`)');
  CALL gc_sql_fk('red_site','fk_site_region','region_id','administrative_region','region_id','CONSTRAINT `fk_site_region` FOREIGN KEY (`region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_index('red_story','PRIMARY','story_id',0,'BTREE','PRIMARY KEY (`story_id`)');
  CALL gc_sql_index('red_story','uk_story_code','story_code',0,'BTREE','UNIQUE KEY `uk_story_code` (`story_code`)');
  CALL gc_sql_index('red_story','fk_story_source','source_id',1,'BTREE','KEY `fk_story_source` (`source_id`)');
  CALL gc_sql_index('red_story','idx_story_title','story_title',1,'BTREE','KEY `idx_story_title` (`story_title`)');
  CALL gc_sql_index('red_story','idx_story_region','related_region_id',1,'BTREE','KEY `idx_story_region` (`related_region_id`)');
  CALL gc_sql_fk('red_story','fk_story_region','related_region_id','administrative_region','region_id','CONSTRAINT `fk_story_region` FOREIGN KEY (`related_region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_fk('red_story','fk_story_source','source_id','data_source','source_id','CONSTRAINT `fk_story_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)');
  CALL gc_sql_index('resource_media','PRIMARY','media_id',0,'BTREE','PRIMARY KEY (`media_id`)');
  CALL gc_sql_index('resource_media','fk_media_source','source_id',1,'BTREE','KEY `fk_media_source` (`source_id`)');
  CALL gc_sql_index('resource_media','idx_media_entity','entity_type,entity_id',1,'BTREE','KEY `idx_media_entity` (`entity_type`,`entity_id`)');
  CALL gc_sql_index('resource_media','idx_media_type','media_type',1,'BTREE','KEY `idx_media_type` (`media_type`)');
  CALL gc_sql_fk('resource_media','fk_media_source','source_id','data_source','source_id','CONSTRAINT `fk_media_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)');
  CALL gc_sql_index('school','PRIMARY','school_id',0,'BTREE','PRIMARY KEY (`school_id`)');
  CALL gc_sql_index('school','uk_school_code','school_code',0,'BTREE','UNIQUE KEY `uk_school_code` (`school_code`)');
  CALL gc_sql_index('school','fk_school_village_region','village_region_id',1,'BTREE','KEY `fk_school_village_region` (`village_region_id`)');
  CALL gc_sql_index('school','fk_school_source','source_id',1,'BTREE','KEY `fk_school_source` (`source_id`)');
  CALL gc_sql_index('school','idx_school_name','school_name',1,'BTREE','KEY `idx_school_name` (`school_name`)');
  CALL gc_sql_index('school','idx_school_region','region_id',1,'BTREE','KEY `idx_school_region` (`region_id`)');
  CALL gc_sql_index('school','idx_school_county','county_region_id',1,'BTREE','KEY `idx_school_county` (`county_region_id`)');
  CALL gc_sql_index('school','idx_school_township','township_region_id',1,'BTREE','KEY `idx_school_township` (`township_region_id`)');
  CALL gc_sql_index('school','idx_school_status','review_status,is_active',1,'BTREE','KEY `idx_school_status` (`review_status`,`is_active`)');
  CALL gc_sql_index('school','idx_school_geo','longitude,latitude',1,'BTREE','KEY `idx_school_geo` (`longitude`,`latitude`)');
  CALL gc_sql_index('school','idx_school_province_region_id','province_region_id',1,'BTREE','KEY `idx_school_province_region_id` (`province_region_id`)');
  CALL gc_sql_index('school','idx_school_city_region_id','city_region_id',1,'BTREE','KEY `idx_school_city_region_id` (`city_region_id`)');
  CALL gc_sql_fk('school','fk_school_county_region','county_region_id','administrative_region','region_id','CONSTRAINT `fk_school_county_region` FOREIGN KEY (`county_region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_fk('school','fk_school_region','region_id','administrative_region','region_id','CONSTRAINT `fk_school_region` FOREIGN KEY (`region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_fk('school','fk_school_source','source_id','data_source','source_id','CONSTRAINT `fk_school_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)');
  CALL gc_sql_fk('school','fk_school_township_region','township_region_id','administrative_region','region_id','CONSTRAINT `fk_school_township_region` FOREIGN KEY (`township_region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_fk('school','fk_school_village_region','village_region_id','administrative_region','region_id','CONSTRAINT `fk_school_village_region` FOREIGN KEY (`village_region_id`) REFERENCES `administrative_region` (`region_id`)');
  CALL gc_sql_index('school_geo_record','PRIMARY','geo_record_id',0,'BTREE','PRIMARY KEY (`geo_record_id`)');
  CALL gc_sql_index('school_geo_record','idx_school_geo_record_school','school_id',1,'BTREE','KEY `idx_school_geo_record_school` (`school_id`)');
  CALL gc_sql_index('school_geo_record','idx_school_geo_record_current','school_id,is_current',1,'BTREE','KEY `idx_school_geo_record_current` (`school_id`,`is_current`)');
  CALL gc_sql_fk('school_geo_record','fk_school_geo_record_school','school_id','school','school_id','CONSTRAINT `fk_school_geo_record_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)');
  CALL gc_sql_index('school_resource_calc_run','PRIMARY','run_id',0,'BTREE','PRIMARY KEY (`run_id`)');
  CALL gc_sql_index('school_resource_calc_run','idx_resource_calc_school_id','school_id',1,'BTREE','KEY `idx_resource_calc_school_id` (`school_id`)');
  CALL gc_sql_index('school_resource_calc_run','idx_resource_calc_status','status',1,'BTREE','KEY `idx_resource_calc_status` (`status`)');
  CALL gc_sql_index('school_resource_calc_run','idx_resource_calc_started_at','started_at',1,'BTREE','KEY `idx_resource_calc_started_at` (`started_at`)');
  CALL gc_sql_index('school_resource_rel','PRIMARY','rel_id',0,'BTREE','PRIMARY KEY (`rel_id`)');
  CALL gc_sql_index('school_resource_rel','uk_school_resource_rel','school_id,resource_id,relation_type',0,'BTREE','UNIQUE KEY `uk_school_resource_rel` (`school_id`,`resource_id`,`relation_type`)');
  CALL gc_sql_index('school_resource_rel','fk_school_resource_rel_source','source_id',1,'BTREE','KEY `fk_school_resource_rel_source` (`source_id`)');
  CALL gc_sql_index('school_resource_rel','idx_school_resource_rel_school','school_id',1,'BTREE','KEY `idx_school_resource_rel_school` (`school_id`)');
  CALL gc_sql_index('school_resource_rel','idx_school_resource_rel_resource','resource_id',1,'BTREE','KEY `idx_school_resource_rel_resource` (`resource_id`)');
  CALL gc_sql_index('school_resource_rel','idx_school_resource_rel_distance','distance_meters',1,'BTREE','KEY `idx_school_resource_rel_distance` (`distance_meters`)');
  CALL gc_sql_index('school_resource_rel','idx_school_resource_rel_calc_run_id','calc_run_id',1,'BTREE','KEY `idx_school_resource_rel_calc_run_id` (`calc_run_id`)');
  CALL gc_sql_index('school_resource_rel','idx_school_resource_rel_manual_locked','manual_locked',1,'BTREE','KEY `idx_school_resource_rel_manual_locked` (`manual_locked`)');
  CALL gc_sql_fk('school_resource_rel','fk_school_resource_rel_resource','resource_id','local_edu_resource','resource_id','CONSTRAINT `fk_school_resource_rel_resource` FOREIGN KEY (`resource_id`) REFERENCES `local_edu_resource` (`resource_id`)');
  CALL gc_sql_fk('school_resource_rel','fk_school_resource_rel_school','school_id','school','school_id','CONSTRAINT `fk_school_resource_rel_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)');
  CALL gc_sql_fk('school_resource_rel','fk_school_resource_rel_source','source_id','data_source','source_id','CONSTRAINT `fk_school_resource_rel_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)');
  CALL gc_sql_index('school_user_account','PRIMARY','account_id',0,'BTREE','PRIMARY KEY (`account_id`)');
  CALL gc_sql_index('school_user_account','uk_school_user_account_username','username',0,'BTREE','UNIQUE KEY `uk_school_user_account_username` (`username`)');
  CALL gc_sql_index('school_user_account','idx_school_user_account_school_id','school_id',1,'BTREE','KEY `idx_school_user_account_school_id` (`school_id`)');
  CALL gc_sql_index('school_user_account','idx_school_user_account_status','status',1,'BTREE','KEY `idx_school_user_account_status` (`status`)');
  CALL gc_sql_index('school_user_account','idx_school_user_account_role','role_code',1,'BTREE','KEY `idx_school_user_account_role` (`role_code`)');
  CALL gc_sql_fk('school_user_account','fk_school_user_account_school','school_id','school','school_id','CONSTRAINT `fk_school_user_account_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)');
  CALL gc_sql_index('site_event_rel','PRIMARY','rel_id',0,'BTREE','PRIMARY KEY (`rel_id`)');
  CALL gc_sql_index('site_event_rel','uk_site_event','site_id,event_id,relation_type',0,'BTREE','UNIQUE KEY `uk_site_event` (`site_id`,`event_id`,`relation_type`)');
  CALL gc_sql_index('site_event_rel','idx_site_event_event','event_id',1,'BTREE','KEY `idx_site_event_event` (`event_id`)');
  CALL gc_sql_index('site_event_rel','idx_site_event_site','site_id',1,'BTREE','KEY `idx_site_event_site` (`site_id`)');
  CALL gc_sql_fk('site_event_rel','fk_site_event_event','event_id','historical_event','event_id','CONSTRAINT `fk_site_event_event` FOREIGN KEY (`event_id`) REFERENCES `historical_event` (`event_id`)');
  CALL gc_sql_fk('site_event_rel','fk_site_event_site','site_id','red_site','site_id','CONSTRAINT `fk_site_event_site` FOREIGN KEY (`site_id`) REFERENCES `red_site` (`site_id`)');
  CALL gc_sql_index('site_hero_rel','PRIMARY','rel_id',0,'BTREE','PRIMARY KEY (`rel_id`)');
  CALL gc_sql_index('site_hero_rel','uk_site_hero','site_id,hero_id,relation_type',0,'BTREE','UNIQUE KEY `uk_site_hero` (`site_id`,`hero_id`,`relation_type`)');
  CALL gc_sql_index('site_hero_rel','idx_site_hero_hero','hero_id',1,'BTREE','KEY `idx_site_hero_hero` (`hero_id`)');
  CALL gc_sql_index('site_hero_rel','idx_site_hero_site','site_id',1,'BTREE','KEY `idx_site_hero_site` (`site_id`)');
  CALL gc_sql_fk('site_hero_rel','fk_site_hero_hero','hero_id','hero_person','hero_id','CONSTRAINT `fk_site_hero_hero` FOREIGN KEY (`hero_id`) REFERENCES `hero_person` (`hero_id`)');
  CALL gc_sql_fk('site_hero_rel','fk_site_hero_site','site_id','red_site','site_id','CONSTRAINT `fk_site_hero_site` FOREIGN KEY (`site_id`) REFERENCES `red_site` (`site_id`)');
  CALL gc_sql_index('story_entity_rel','PRIMARY','rel_id',0,'BTREE','PRIMARY KEY (`rel_id`)');
  CALL gc_sql_index('story_entity_rel','uk_story_entity','story_id,entity_type,entity_id,relation_type',0,'BTREE','UNIQUE KEY `uk_story_entity` (`story_id`,`entity_type`,`entity_id`,`relation_type`)');
  CALL gc_sql_index('story_entity_rel','idx_story_entity_lookup','entity_type,entity_id',1,'BTREE','KEY `idx_story_entity_lookup` (`entity_type`,`entity_id`)');
  CALL gc_sql_fk('story_entity_rel','fk_story_entity_story','story_id','red_story','story_id','CONSTRAINT `fk_story_entity_story` FOREIGN KEY (`story_id`) REFERENCES `red_story` (`story_id`)');
  CALL gc_sql_index('student_profile','PRIMARY','student_id',0,'BTREE','PRIMARY KEY (`student_id`)');
  CALL gc_sql_index('student_profile','uk_student_account_id','account_id',0,'BTREE','UNIQUE KEY `uk_student_account_id` (`account_id`)');
  CALL gc_sql_index('student_profile','uk_student_school_no','school_id,student_no',0,'BTREE','UNIQUE KEY `uk_student_school_no` (`school_id`,`student_no`)');
  CALL gc_sql_index('student_profile','idx_student_school_id','school_id',1,'BTREE','KEY `idx_student_school_id` (`school_id`)');
  CALL gc_sql_index('student_profile','idx_student_grade_name','grade_name',1,'BTREE','KEY `idx_student_grade_name` (`grade_name`)');
  CALL gc_sql_index('student_profile','idx_student_status','status',1,'BTREE','KEY `idx_student_status` (`status`)');
  CALL gc_sql_index('student_profile','idx_student_profile_id','profile_id',1,'BTREE','KEY `idx_student_profile_id` (`profile_id`)');
  CALL gc_sql_index('student_resource_browse_history','PRIMARY','browse_id',0,'BTREE','PRIMARY KEY (`browse_id`)');
  CALL gc_sql_index('student_resource_browse_history','uk_student_resource','student_id,resource_id',0,'BTREE','UNIQUE KEY `uk_student_resource` (`student_id`,`resource_id`)');
  CALL gc_sql_index('student_resource_browse_history','idx_student_browse_time','student_id,viewed_at',1,'BTREE','KEY `idx_student_browse_time` (`student_id`,`viewed_at`)');
  CALL gc_sql_index('student_resource_browse_history','fk_student_browse_resource','resource_id',1,'BTREE','KEY `fk_student_browse_resource` (`resource_id`)');
  CALL gc_sql_fk('student_resource_browse_history','fk_student_browse_resource','resource_id','local_edu_resource','resource_id','CONSTRAINT `fk_student_browse_resource` FOREIGN KEY (`resource_id`) REFERENCES `local_edu_resource` (`resource_id`)');
  CALL gc_sql_fk('student_resource_browse_history','fk_student_browse_student','student_id','student_profile','student_id','CONSTRAINT `fk_student_browse_student` FOREIGN KEY (`student_id`) REFERENCES `student_profile` (`student_id`)');
  CALL gc_sql_index('student_task_attachment','PRIMARY','attachment_id',0,'BTREE','PRIMARY KEY (`attachment_id`)');
  CALL gc_sql_index('student_task_attachment','uk_submission_storage_key','storage_key',0,'BTREE','UNIQUE KEY `uk_submission_storage_key` (`storage_key`)');
  CALL gc_sql_index('student_task_attachment','idx_submission_attachment','submission_id',1,'BTREE','KEY `idx_submission_attachment` (`submission_id`)');
  CALL gc_sql_index('student_task_progress','PRIMARY','id',0,'BTREE','PRIMARY KEY (`id`)');
  CALL gc_sql_index('student_task_progress','uk_student_task_progress','task_id,student_id',0,'BTREE','UNIQUE KEY `uk_student_task_progress` (`task_id`,`student_id`)');
  CALL gc_sql_index('student_task_progress','idx_student_task_progress_student','student_id',1,'BTREE','KEY `idx_student_task_progress_student` (`student_id`)');
  CALL gc_sql_index('student_task_progress','idx_student_task_progress_status','status',1,'BTREE','KEY `idx_student_task_progress_status` (`status`)');
  CALL gc_sql_index('student_task_review','PRIMARY','review_id',0,'BTREE','PRIMARY KEY (`review_id`)');
  CALL gc_sql_index('student_task_review','idx_task_review_submission','submission_id',1,'BTREE','KEY `idx_task_review_submission` (`submission_id`)');
  CALL gc_sql_index('student_task_submission','PRIMARY','submission_id',0,'BTREE','PRIMARY KEY (`submission_id`)');
  CALL gc_sql_index('student_task_submission','uk_submission_version','task_id,student_id,version_no',0,'BTREE','UNIQUE KEY `uk_submission_version` (`task_id`,`student_id`,`version_no`)');
  CALL gc_sql_index('student_task_submission','idx_submission_task_student','task_id,student_id',1,'BTREE','KEY `idx_submission_task_student` (`task_id`,`student_id`)');
  CALL gc_sql_index('student_task_submission','idx_submission_current','task_id,student_id,is_current',1,'BTREE','KEY `idx_submission_current` (`task_id`,`student_id`,`is_current`)');
  CALL gc_sql_index('sys_account_role','PRIMARY','id',0,'BTREE','PRIMARY KEY (`id`)');
  CALL gc_sql_index('sys_account_role','uk_account_role','account_id,role_id',0,'BTREE','UNIQUE KEY `uk_account_role` (`account_id`,`role_id`)');
  CALL gc_sql_index('sys_account_role','idx_account_role_account_id','account_id',1,'BTREE','KEY `idx_account_role_account_id` (`account_id`)');
  CALL gc_sql_index('sys_account_role','idx_account_role_role_id','role_id',1,'BTREE','KEY `idx_account_role_role_id` (`role_id`)');
  CALL gc_sql_index('sys_permission','PRIMARY','permission_id',0,'BTREE','PRIMARY KEY (`permission_id`)');
  CALL gc_sql_index('sys_permission','uk_sys_permission_code','permission_code',0,'BTREE','UNIQUE KEY `uk_sys_permission_code` (`permission_code`)');
  CALL gc_sql_index('sys_permission','idx_sys_permission_parent_id','parent_id',1,'BTREE','KEY `idx_sys_permission_parent_id` (`parent_id`)');
  CALL gc_sql_index('sys_permission','idx_sys_permission_type','permission_type',1,'BTREE','KEY `idx_sys_permission_type` (`permission_type`)');
  CALL gc_sql_index('sys_role','PRIMARY','role_id',0,'BTREE','PRIMARY KEY (`role_id`)');
  CALL gc_sql_index('sys_role','uk_sys_role_code','role_code',0,'BTREE','UNIQUE KEY `uk_sys_role_code` (`role_code`)');
  CALL gc_sql_index('sys_role','idx_sys_role_scope','role_scope',1,'BTREE','KEY `idx_sys_role_scope` (`role_scope`)');
  CALL gc_sql_index('sys_role','idx_sys_role_status','status',1,'BTREE','KEY `idx_sys_role_status` (`status`)');
  CALL gc_sql_index('sys_role_permission','PRIMARY','id',0,'BTREE','PRIMARY KEY (`id`)');
  CALL gc_sql_index('sys_role_permission','uk_role_permission','role_id,permission_id',0,'BTREE','UNIQUE KEY `uk_role_permission` (`role_id`,`permission_id`)');
  CALL gc_sql_index('sys_role_permission','idx_role_permission_role_id','role_id',1,'BTREE','KEY `idx_role_permission_role_id` (`role_id`)');
  CALL gc_sql_index('sys_role_permission','idx_role_permission_permission_id','permission_id',1,'BTREE','KEY `idx_role_permission_permission_id` (`permission_id`)');
  CALL gc_sql_index('tag_info','PRIMARY','tag_id',0,'BTREE','PRIMARY KEY (`tag_id`)');
  CALL gc_sql_index('tag_info','uk_tag_name_type','tag_name,tag_type',0,'BTREE','UNIQUE KEY `uk_tag_name_type` (`tag_name`,`tag_type`)');
  CALL gc_sql_index('task_resource_rel','PRIMARY','id',0,'BTREE','PRIMARY KEY (`id`)');
  CALL gc_sql_index('task_resource_rel','uk_task_resource','task_id,resource_id',0,'BTREE','UNIQUE KEY `uk_task_resource` (`task_id`,`resource_id`)');
  CALL gc_sql_index('task_resource_rel','idx_task_resource_resource','resource_id',1,'BTREE','KEY `idx_task_resource_resource` (`resource_id`)');
  CALL gc_sql_index('teacher_profile','PRIMARY','teacher_id',0,'BTREE','PRIMARY KEY (`teacher_id`)');
  CALL gc_sql_index('teacher_profile','uk_teacher_account_id','account_id',0,'BTREE','UNIQUE KEY `uk_teacher_account_id` (`account_id`)');
  CALL gc_sql_index('teacher_profile','uk_teacher_school_no','school_id,teacher_no',0,'BTREE','UNIQUE KEY `uk_teacher_school_no` (`school_id`,`teacher_no`)');
  CALL gc_sql_index('teacher_profile','idx_teacher_school_id','school_id',1,'BTREE','KEY `idx_teacher_school_id` (`school_id`)');
  CALL gc_sql_index('teacher_profile','idx_teacher_status','status',1,'BTREE','KEY `idx_teacher_status` (`status`)');
  CALL gc_sql_index('teacher_profile','idx_teacher_profile_id','profile_id',1,'BTREE','KEY `idx_teacher_profile_id` (`profile_id`)');
  CALL gc_sql_index('teacher_registration_invite','PRIMARY','invite_id',0,'BTREE','PRIMARY KEY (`invite_id`)');
  CALL gc_sql_index('teacher_registration_invite','uk_teacher_registration_invite_hash','code_hash',0,'BTREE','UNIQUE KEY `uk_teacher_registration_invite_hash` (`code_hash`)');
  CALL gc_sql_index('teacher_registration_invite','idx_teacher_registration_invite_school_status','school_id,status,expires_at',1,'BTREE','KEY `idx_teacher_registration_invite_school_status` (`school_id`,`status`,`expires_at`)');
  CALL gc_sql_index('teacher_registration_invite','fk_teacher_registration_invite_creator','created_by_account_id',1,'BTREE','KEY `fk_teacher_registration_invite_creator` (`created_by_account_id`)');
  CALL gc_sql_fk('teacher_registration_invite','fk_teacher_registration_invite_creator','created_by_account_id','school_user_account','account_id','CONSTRAINT `fk_teacher_registration_invite_creator` FOREIGN KEY (`created_by_account_id`) REFERENCES `school_user_account` (`account_id`)');
  CALL gc_sql_fk('teacher_registration_invite','fk_teacher_registration_invite_school','school_id','school','school_id','CONSTRAINT `fk_teacher_registration_invite_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)');
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='teacher_registration_invite' AND constraint_name='chk_teacher_registration_invite_uses') THEN
    ALTER TABLE `teacher_registration_invite` ADD CONSTRAINT `chk_teacher_registration_invite_uses` CHECK (((`max_uses` > 0) and (`used_count` >= 0) and (`used_count` <= `max_uses`)));
  ELSEIF NOT EXISTS (SELECT 1 FROM information_schema.check_constraints cc
      JOIN information_schema.table_constraints tc ON tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name
      WHERE tc.constraint_schema=DATABASE() AND tc.table_name='teacher_registration_invite' AND tc.constraint_name='chk_teacher_registration_invite_uses'
        AND tc.enforced='YES' AND REPLACE(LOWER(cc.check_clause),' ','')=REPLACE(LOWER('((`max_uses` > 0) and (`used_count` >= 0) and (`used_count` <= `max_uses`))'),' ','')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Incompatible check: teacher_registration_invite.chk_teacher_registration_invite_uses';
  END IF;
  CALL gc_sql_index('teacher_resource_favorite','PRIMARY','favorite_id',0,'BTREE','PRIMARY KEY (`favorite_id`)');
  CALL gc_sql_index('teacher_resource_favorite','uk_teacher_resource_favorite','teacher_id,resource_id',0,'BTREE','UNIQUE KEY `uk_teacher_resource_favorite` (`teacher_id`,`resource_id`)');
  CALL gc_sql_index('teacher_resource_favorite','idx_teacher_resource_favorite_resource','resource_id',1,'BTREE','KEY `idx_teacher_resource_favorite_resource` (`resource_id`)');
  CALL gc_sql_index('teaching_activity_plan','PRIMARY','plan_id',0,'BTREE','PRIMARY KEY (`plan_id`)');
  CALL gc_sql_index('teaching_activity_plan','uk_teaching_activity_plan_code','plan_code',0,'BTREE','UNIQUE KEY `uk_teaching_activity_plan_code` (`plan_code`)');
  CALL gc_sql_index('teaching_activity_plan','fk_teaching_activity_plan_source','source_id',1,'BTREE','KEY `fk_teaching_activity_plan_source` (`source_id`)');
  CALL gc_sql_index('teaching_activity_plan','idx_teaching_activity_plan_school','school_id',1,'BTREE','KEY `idx_teaching_activity_plan_school` (`school_id`)');
  CALL gc_sql_index('teaching_activity_plan','idx_teaching_activity_plan_resource','resource_id',1,'BTREE','KEY `idx_teaching_activity_plan_resource` (`resource_id`)');
  CALL gc_sql_index('teaching_activity_plan','idx_teaching_activity_plan_theme','theme',1,'BTREE','KEY `idx_teaching_activity_plan_theme` (`theme`)');
  CALL gc_sql_index('teaching_activity_plan','idx_teaching_activity_plan_status','review_status,is_active',1,'BTREE','KEY `idx_teaching_activity_plan_status` (`review_status`,`is_active`)');
  CALL gc_sql_index('teaching_activity_plan','idx_teaching_activity_plan_owner','owner_account_id',1,'BTREE','KEY `idx_teaching_activity_plan_owner` (`owner_account_id`)');
  CALL gc_sql_index('teaching_activity_plan','idx_teaching_activity_plan_class','class_id',1,'BTREE','KEY `idx_teaching_activity_plan_class` (`class_id`)');
  CALL gc_sql_index('teaching_activity_plan','idx_teaching_activity_plan_publish','published_status',1,'BTREE','KEY `idx_teaching_activity_plan_publish` (`published_status`)');
  CALL gc_sql_fk('teaching_activity_plan','fk_teaching_activity_plan_resource','resource_id','local_edu_resource','resource_id','CONSTRAINT `fk_teaching_activity_plan_resource` FOREIGN KEY (`resource_id`) REFERENCES `local_edu_resource` (`resource_id`)');
  CALL gc_sql_fk('teaching_activity_plan','fk_teaching_activity_plan_school','school_id','school','school_id','CONSTRAINT `fk_teaching_activity_plan_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)');
  CALL gc_sql_fk('teaching_activity_plan','fk_teaching_activity_plan_source','source_id','data_source','source_id','CONSTRAINT `fk_teaching_activity_plan_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)');
  CALL gc_sql_index('teaching_activity_plan_resource','PRIMARY','plan_id,resource_id',0,'BTREE','PRIMARY KEY (`plan_id`,`resource_id`)');
  CALL gc_sql_index('teaching_activity_plan_resource','idx_plan_resource_resource','resource_id',1,'BTREE','KEY `idx_plan_resource_resource` (`resource_id`)');
  CALL gc_sql_index('user_profile','PRIMARY','profile_id',0,'BTREE','PRIMARY KEY (`profile_id`)');
  CALL gc_sql_index('user_profile','uk_user_profile_account_id','account_id',0,'BTREE','UNIQUE KEY `uk_user_profile_account_id` (`account_id`)');
  CALL gc_sql_index('user_profile','idx_user_profile_type','profile_type',1,'BTREE','KEY `idx_user_profile_type` (`profile_type`)');
  CALL gc_sql_index('user_profile','idx_user_profile_school_id','school_id',1,'BTREE','KEY `idx_user_profile_school_id` (`school_id`)');
  CALL gc_sql_index('user_profile','idx_user_profile_status','status',1,'BTREE','KEY `idx_user_profile_status` (`status`)');
  CALL gc_sql_index('ai_teaching_plan_generation','PRIMARY','generation_id',0,'BTREE','PRIMARY KEY (`generation_id`)');
  CALL gc_sql_index('ai_teaching_plan_generation','idx_ai_plan_generation_school_time','school_id,created_at',1,'BTREE','KEY `idx_ai_plan_generation_school_time` (`school_id`,`created_at`)');
  CALL gc_sql_index('ai_teaching_plan_generation','idx_ai_plan_generation_account_time','account_id,created_at',1,'BTREE','KEY `idx_ai_plan_generation_account_time` (`account_id`,`created_at`)');
  CALL gc_sql_index('ai_teaching_plan_generation','idx_ai_plan_generation_role_status_time','actor_role,generation_status,created_at',1,'BTREE','KEY `idx_ai_plan_generation_role_status_time` (`actor_role`,`generation_status`,`created_at`)');
  CALL gc_sql_index('ai_teaching_plan_generation','idx_ai_plan_generation_saved_plan','saved_plan_id',1,'BTREE','KEY `idx_ai_plan_generation_saved_plan` (`saved_plan_id`)');
  CALL gc_sql_fk('ai_teaching_plan_generation','fk_ai_plan_generation_saved_plan','saved_plan_id','teaching_activity_plan','plan_id','CONSTRAINT `fk_ai_plan_generation_saved_plan` FOREIGN KEY (`saved_plan_id`) REFERENCES `teaching_activity_plan` (`plan_id`)');
  CALL gc_sql_fk('ai_teaching_plan_generation','fk_ai_plan_generation_school','school_id','school','school_id','CONSTRAINT `fk_ai_plan_generation_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)');
  CALL gc_sql_index('auth_refresh_token','PRIMARY','token_id',0,'BTREE','PRIMARY KEY (`token_id`)');
  CALL gc_sql_index('auth_refresh_token','uk_auth_refresh_token_hash','token_hash',0,'BTREE','UNIQUE KEY `uk_auth_refresh_token_hash` (`token_hash`)');
  CALL gc_sql_index('auth_refresh_token','idx_auth_refresh_token_account_status','account_id,revoked_at,expires_at',1,'BTREE','KEY `idx_auth_refresh_token_account_status` (`account_id`,`revoked_at`,`expires_at`)');
  CALL gc_sql_index('auth_refresh_token','idx_auth_refresh_token_family','token_family_id',1,'BTREE','KEY `idx_auth_refresh_token_family` (`token_family_id`)');
  CALL gc_sql_fk('auth_refresh_token','fk_auth_refresh_token_account','account_id','school_user_account','account_id','CONSTRAINT `fk_auth_refresh_token_account` FOREIGN KEY (`account_id`) REFERENCES `school_user_account` (`account_id`)');
  CALL gc_sql_index('content_chunk','PRIMARY','chunk_id',0,'BTREE','PRIMARY KEY (`chunk_id`)');
  CALL gc_sql_index('content_chunk','uk_content_chunk','entity_type,entity_id,chunk_index',0,'BTREE','UNIQUE KEY `uk_content_chunk` (`entity_type`,`entity_id`,`chunk_index`)');
  CALL gc_sql_index('content_chunk','fk_chunk_source','source_id',1,'BTREE','KEY `fk_chunk_source` (`source_id`)');
  CALL gc_sql_index('content_chunk','idx_chunk_entity','entity_type,entity_id',1,'BTREE','KEY `idx_chunk_entity` (`entity_type`,`entity_id`)');
  CALL gc_sql_index('content_chunk','idx_content_chunk_content_hash','content_hash',1,'BTREE','KEY `idx_content_chunk_content_hash` (`content_hash`)');
  CALL gc_sql_index('content_chunk','idx_content_chunk_vector_point_id','vector_point_id',1,'BTREE','KEY `idx_content_chunk_vector_point_id` (`vector_point_id`)');
  CALL gc_sql_index('content_chunk','ft_chunk_text','chunk_title,chunk_text,retrieval_text',1,'FULLTEXT','FULLTEXT KEY `ft_chunk_text` (`chunk_title`,`chunk_text`,`retrieval_text`) /*!50100 WITH PARSER `ngram` */ ');
  CALL gc_sql_fk('content_chunk','fk_chunk_source','source_id','data_source','source_id','CONSTRAINT `fk_chunk_source` FOREIGN KEY (`source_id`) REFERENCES `data_source` (`source_id`)');
  CALL gc_sql_index('entity_tag_rel','PRIMARY','rel_id',0,'BTREE','PRIMARY KEY (`rel_id`)');
  CALL gc_sql_index('entity_tag_rel','uk_entity_tag','entity_type,entity_id,tag_id',0,'BTREE','UNIQUE KEY `uk_entity_tag` (`entity_type`,`entity_id`,`tag_id`)');
  CALL gc_sql_index('entity_tag_rel','fk_entity_tag_rel_tag','tag_id',1,'BTREE','KEY `fk_entity_tag_rel_tag` (`tag_id`)');
  CALL gc_sql_index('entity_tag_rel','idx_entity_tag_lookup','entity_type,entity_id',1,'BTREE','KEY `idx_entity_tag_lookup` (`entity_type`,`entity_id`)');
  CALL gc_sql_fk('entity_tag_rel','fk_entity_tag_rel_tag','tag_id','tag_info','tag_id','CONSTRAINT `fk_entity_tag_rel_tag` FOREIGN KEY (`tag_id`) REFERENCES `tag_info` (`tag_id`)');
  CALL gc_sql_index('event_hero_rel','PRIMARY','rel_id',0,'BTREE','PRIMARY KEY (`rel_id`)');
  CALL gc_sql_index('event_hero_rel','uk_event_hero','event_id,hero_id,relation_type',0,'BTREE','UNIQUE KEY `uk_event_hero` (`event_id`,`hero_id`,`relation_type`)');
  CALL gc_sql_index('event_hero_rel','idx_event_hero_hero','hero_id',1,'BTREE','KEY `idx_event_hero_hero` (`hero_id`)');
  CALL gc_sql_index('event_hero_rel','idx_event_hero_event','event_id',1,'BTREE','KEY `idx_event_hero_event` (`event_id`)');
  CALL gc_sql_fk('event_hero_rel','fk_event_hero_event','event_id','historical_event','event_id','CONSTRAINT `fk_event_hero_event` FOREIGN KEY (`event_id`) REFERENCES `historical_event` (`event_id`)');
  CALL gc_sql_fk('event_hero_rel','fk_event_hero_hero','hero_id','hero_person','hero_id','CONSTRAINT `fk_event_hero_hero` FOREIGN KEY (`hero_id`) REFERENCES `hero_person` (`hero_id`)');
  CALL gc_sql_index('knowledge_chunk','PRIMARY','id',0,'BTREE','PRIMARY KEY (`id`)');
  CALL gc_sql_index('knowledge_chunk','uk_knowledge_chunk_document_order','document_id,chunk_order',0,'BTREE','UNIQUE KEY `uk_knowledge_chunk_document_order` (`document_id`,`chunk_order`)');
  CALL gc_sql_index('knowledge_chunk','idx_knowledge_chunk_document','document_id',1,'BTREE','KEY `idx_knowledge_chunk_document` (`document_id`)');
  CALL gc_sql_fk('knowledge_chunk','fk_knowledge_chunk_document','document_id','knowledge_document','id','CONSTRAINT `fk_knowledge_chunk_document` FOREIGN KEY (`document_id`) REFERENCES `knowledge_document` (`id`) ON DELETE CASCADE');
  CALL gc_sql_index('memorial_event_rel','PRIMARY','rel_id',0,'BTREE','PRIMARY KEY (`rel_id`)');
  CALL gc_sql_index('memorial_event_rel','uk_memorial_event','memorial_id,event_id,relation_type',0,'BTREE','UNIQUE KEY `uk_memorial_event` (`memorial_id`,`event_id`,`relation_type`)');
  CALL gc_sql_index('memorial_event_rel','idx_memorial_event_event','event_id',1,'BTREE','KEY `idx_memorial_event_event` (`event_id`)');
  CALL gc_sql_fk('memorial_event_rel','fk_memorial_event_event','event_id','historical_event','event_id','CONSTRAINT `fk_memorial_event_event` FOREIGN KEY (`event_id`) REFERENCES `historical_event` (`event_id`)');
  CALL gc_sql_fk('memorial_event_rel','fk_memorial_event_memorial','memorial_id','memorial_hall','memorial_id','CONSTRAINT `fk_memorial_event_memorial` FOREIGN KEY (`memorial_id`) REFERENCES `memorial_hall` (`memorial_id`)');
  CALL gc_sql_index('memorial_site_rel','PRIMARY','rel_id',0,'BTREE','PRIMARY KEY (`rel_id`)');
  CALL gc_sql_index('memorial_site_rel','uk_memorial_site','memorial_id,site_id,relation_type',0,'BTREE','UNIQUE KEY `uk_memorial_site` (`memorial_id`,`site_id`,`relation_type`)');
  CALL gc_sql_index('memorial_site_rel','idx_memorial_site_site','site_id',1,'BTREE','KEY `idx_memorial_site_site` (`site_id`)');
  CALL gc_sql_fk('memorial_site_rel','fk_memorial_site_memorial','memorial_id','memorial_hall','memorial_id','CONSTRAINT `fk_memorial_site_memorial` FOREIGN KEY (`memorial_id`) REFERENCES `memorial_hall` (`memorial_id`)');
  CALL gc_sql_fk('memorial_site_rel','fk_memorial_site_site','site_id','red_site','site_id','CONSTRAINT `fk_memorial_site_site` FOREIGN KEY (`site_id`) REFERENCES `red_site` (`site_id`)');
  CALL gc_sql_index('resource_discovery_candidate','PRIMARY','candidate_id',0,'BTREE','PRIMARY KEY (`candidate_id`)');
  CALL gc_sql_index('resource_discovery_candidate','uk_discovery_candidate_place','school_id,provider,provider_place_id',0,'BTREE','UNIQUE KEY `uk_discovery_candidate_place` (`school_id`,`provider`,`provider_place_id`)');
  CALL gc_sql_index('resource_discovery_candidate','fk_discovery_candidate_resource','matched_resource_id',1,'BTREE','KEY `fk_discovery_candidate_resource` (`matched_resource_id`)');
  CALL gc_sql_index('resource_discovery_candidate','idx_discovery_candidate_review','decision_status,analysis_status',1,'BTREE','KEY `idx_discovery_candidate_review` (`decision_status`,`analysis_status`)');
  CALL gc_sql_index('resource_discovery_candidate','idx_discovery_candidate_school','school_id,last_seen_at',1,'BTREE','KEY `idx_discovery_candidate_school` (`school_id`,`last_seen_at`)');
  CALL gc_sql_index('resource_discovery_candidate','idx_discovery_candidate_geo','longitude,latitude',1,'BTREE','KEY `idx_discovery_candidate_geo` (`longitude`,`latitude`)');
  CALL gc_sql_fk('resource_discovery_candidate','fk_discovery_candidate_resource','matched_resource_id','local_edu_resource','resource_id','CONSTRAINT `fk_discovery_candidate_resource` FOREIGN KEY (`matched_resource_id`) REFERENCES `local_edu_resource` (`resource_id`)');
  CALL gc_sql_fk('resource_discovery_candidate','fk_discovery_candidate_school','school_id','school','school_id','CONSTRAINT `fk_discovery_candidate_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)');
  CALL gc_sql_index('resource_discovery_run','PRIMARY','run_id',0,'BTREE','PRIMARY KEY (`run_id`)');
  CALL gc_sql_index('resource_discovery_run','idx_discovery_run_cache','school_id,radius_meters,status,cache_expires_at',1,'BTREE','KEY `idx_discovery_run_cache` (`school_id`,`radius_meters`,`status`,`cache_expires_at`)');
  CALL gc_sql_index('resource_discovery_run','idx_discovery_run_status','status,started_at',1,'BTREE','KEY `idx_discovery_run_status` (`status`,`started_at`)');
  CALL gc_sql_fk('resource_discovery_run','fk_discovery_run_school','school_id','school','school_id','CONSTRAINT `fk_discovery_run_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`)');
  CALL gc_sql_index('resource_discovery_run_item','PRIMARY','run_id,candidate_id',0,'BTREE','PRIMARY KEY (`run_id`,`candidate_id`)');
  CALL gc_sql_index('resource_discovery_run_item','fk_discovery_item_candidate','candidate_id',1,'BTREE','KEY `fk_discovery_item_candidate` (`candidate_id`)');
  CALL gc_sql_index('resource_discovery_run_item','idx_discovery_item_rank','run_id,result_rank',1,'BTREE','KEY `idx_discovery_item_rank` (`run_id`,`result_rank`)');
  CALL gc_sql_fk('resource_discovery_run_item','fk_discovery_item_candidate','candidate_id','resource_discovery_candidate','candidate_id','CONSTRAINT `fk_discovery_item_candidate` FOREIGN KEY (`candidate_id`) REFERENCES `resource_discovery_candidate` (`candidate_id`)');
  CALL gc_sql_fk('resource_discovery_run_item','fk_discovery_item_run','run_id','resource_discovery_run','run_id','CONSTRAINT `fk_discovery_item_run` FOREIGN KEY (`run_id`) REFERENCES `resource_discovery_run` (`run_id`) ON DELETE CASCADE');
  CALL gc_sql_index('teaching_plan_feedback','PRIMARY','feedback_id',0,'BTREE','PRIMARY KEY (`feedback_id`)');
  CALL gc_sql_index('teaching_plan_feedback','uk_teaching_plan_feedback_generation','generation_id',0,'BTREE','UNIQUE KEY `uk_teaching_plan_feedback_generation` (`generation_id`)');
  CALL gc_sql_index('teaching_plan_feedback','idx_teaching_plan_feedback_teacher_time','teacher_account_id,submitted_at',1,'BTREE','KEY `idx_teaching_plan_feedback_teacher_time` (`teacher_account_id`,`submitted_at`)');
  CALL gc_sql_index('teaching_plan_feedback','idx_teaching_plan_feedback_adopted_rating','adopted,rating',1,'BTREE','KEY `idx_teaching_plan_feedback_adopted_rating` (`adopted`,`rating`)');
  CALL gc_sql_fk('teaching_plan_feedback','fk_teaching_plan_feedback_generation','generation_id','ai_teaching_plan_generation','generation_id','CONSTRAINT `fk_teaching_plan_feedback_generation` FOREIGN KEY (`generation_id`) REFERENCES `ai_teaching_plan_generation` (`generation_id`)');
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='teaching_plan_feedback' AND constraint_name='chk_teaching_plan_feedback_rating') THEN
    ALTER TABLE `teaching_plan_feedback` ADD CONSTRAINT `chk_teaching_plan_feedback_rating` CHECK ((`rating` between 1 and 5));
  ELSEIF NOT EXISTS (SELECT 1 FROM information_schema.check_constraints cc
      JOIN information_schema.table_constraints tc ON tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name
      WHERE tc.constraint_schema=DATABASE() AND tc.table_name='teaching_plan_feedback' AND tc.constraint_name='chk_teaching_plan_feedback_rating'
        AND tc.enforced='YES' AND REPLACE(LOWER(cc.check_clause),' ','')=REPLACE(LOWER('(`rating` between 1 and 5)'),' ','')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Incompatible check: teaching_plan_feedback.chk_teaching_plan_feedback_rating';
  END IF;

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
DROP PROCEDURE IF EXISTS gc_sql_fk;
DROP PROCEDURE IF EXISTS gc_sql_index;
DROP PROCEDURE IF EXISTS gc_sql_column;
DROP PROCEDURE IF EXISTS gc_sql_exec;

DROP PROCEDURE IF EXISTS gc_sql_validate_column;
