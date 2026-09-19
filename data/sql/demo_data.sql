-- MySQL 8.0；先选中目标数据库并完成初始化/升级，再在 IDEA 执行整个文件。
-- 独立演示数据：test_teacher / test_student，初始密码均为 123456。
-- 仅用于本地演示；重复执行不重置密码、不覆盖已有业务内容。
-- 所有关联通过稳定代码/用户名查询 ID。标识冲突或孤立档案会报错并回滚。
-- 请用没有未提交事务的独立控制台执行。示例文案不是权威知识来源。
DELIMITER $$

DROP PROCEDURE IF EXISTS gc_demo_region$$
CREATE PROCEDURE gc_demo_region(IN code_value VARCHAR(20), IN name_value VARCHAR(100),
    IN level_value VARCHAR(20), IN parent_value BIGINT, OUT result_id BIGINT)
SQL SECURITY INVOKER
BEGIN
  IF EXISTS (SELECT 1 FROM administrative_region WHERE adcode=code_value
      AND (region_name<>name_value OR region_level<>level_value OR NOT(parent_region_id <=> parent_value))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo region code conflicts with existing region; no data overwritten';
  END IF;
  INSERT INTO administrative_region(adcode,region_name,region_level,parent_region_id)
  SELECT code_value,name_value,level_value,parent_value
  WHERE NOT EXISTS (SELECT 1 FROM administrative_region WHERE adcode=code_value);
  SELECT region_id INTO result_id FROM administrative_region WHERE adcode=code_value;
END$$

DROP PROCEDURE IF EXISTS gc_demo_apply$$
CREATE PROCEDURE gc_demo_apply()
SQL SECURITY INVOKER
BEGIN
  DECLARE v_province BIGINT;
  DECLARE v_city BIGINT;
  DECLARE v_county BIGINT;
  DECLARE v_town BIGINT;
  DECLARE v_source BIGINT;
  DECLARE v_school BIGINT;
  DECLARE v_resource BIGINT;
  DECLARE v_resource_two BIGINT;
  DECLARE v_teacher_account BIGINT;
  DECLARE v_student_account BIGINT;
  DECLARE v_teacher BIGINT;
  DECLARE v_student BIGINT;
  DECLARE v_class BIGINT;
  DECLARE v_task BIGINT;
  DECLARE v_plan BIGINT;
  DECLARE v_lock INT DEFAULT 0;
  DECLARE old_fk INT DEFAULT @@SESSION.foreign_key_checks;
  DECLARE old_unique INT DEFAULT @@SESSION.unique_checks;
  DECLARE v_hash VARCHAR(255) DEFAULT '$2b$10$blqD0CahbIA0E08KsiXy0OI8EoFYCFEZuo9CheBrykIqglWSiSoD6';
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    SET SESSION foreign_key_checks=old_fk;
    SET SESSION unique_checks=old_unique;
    IF v_lock=1 THEN DO RELEASE_LOCK(CONCAT('gc_schema:',DATABASE())); END IF;
    RESIGNAL;
  END;
  SELECT GET_LOCK(CONCAT('gc_schema:',DATABASE()),0) INTO v_lock;
  IF COALESCE(v_lock,0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Another schema/demo operation is running';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
      AND table_name IN ('teacher_registration_invite','knowledge_ingest_outbox','class_learning_task','student_resource_browse_history'))<>4 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Run database_setup.sql or database_setup_existing.sql first';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()
      AND table_type='BASE TABLE' AND engine<>'InnoDB') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo transaction requires InnoDB tables';
  END IF;
  SET SESSION foreign_key_checks=1;
  SET SESSION unique_checks=1;
  START TRANSACTION;
  -- 防止新账号的自增 ID 恰巧被历史孤立档案占用，不擅自认领或修复旧档案。
  IF EXISTS (SELECT 1 FROM teacher_profile p LEFT JOIN school_user_account a ON a.account_id=p.account_id WHERE a.account_id IS NULL)
    OR EXISTS (SELECT 1 FROM student_profile p LEFT JOIN school_user_account a ON a.account_id=p.account_id WHERE a.account_id IS NULL)
    OR EXISTS (SELECT 1 FROM user_profile p LEFT JOIN school_user_account a ON a.account_id=p.account_id WHERE a.account_id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Orphan account profiles found; repair separately or use a fresh demo database';
  END IF;

  CALL gc_demo_region('130000','河北省','province',NULL,v_province);
  CALL gc_demo_region('130100','石家庄市','city',v_province,v_city);
  CALL gc_demo_region('130131','平山县','county',v_city,v_county);
  -- 此代码是演示标识，不冒充官方乡镇行政区划编码。
  CALL gc_demo_region('DEMO_XBP_TOWN','西柏坡镇（演示）','township',v_county,v_town);

  IF (SELECT COUNT(*) FROM data_source WHERE source_name='gc_demo_v1')>1
    OR EXISTS (SELECT 1 FROM data_source WHERE source_name='gc_demo_v1' AND NOT(remark <=> 'gc_demo_v1:owned')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo source identity conflict';
  END IF;
  INSERT INTO data_source(source_name,source_type,organization_name,reliability_level,crawl_allowed,remark)
  SELECT 'gc_demo_v1','other','本地演示资料',1,0,'gc_demo_v1:owned'
  WHERE NOT EXISTS (SELECT 1 FROM data_source WHERE source_name='gc_demo_v1');
  SELECT source_id INTO v_source FROM data_source WHERE source_name='gc_demo_v1';

  IF EXISTS (SELECT 1 FROM school WHERE school_code='DEMO_XBP_001'
      AND (NOT(source_id <=> v_source) OR NOT(intro <=> 'gc_demo_v1:owned'))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo school identity conflict';
  END IF;
  -- 坐标采用原专项修正值；仅用于新演示学校，不更新现有学校。
  INSERT INTO school(school_code,school_name,region_id,province_region_id,city_region_id,
      county_region_id,township_region_id,address,longitude,latitude,school_level,
      school_nature,source_id,intro,review_status,is_active)
  SELECT 'DEMO_XBP_001','西柏坡希望小学（演示）',v_town,v_province,v_city,v_county,v_town,
      '河北省石家庄市平山县西柏坡镇迎宾路7号',113.9390000,38.3484380,'primary','public',v_source,'gc_demo_v1:owned','approved',1
  WHERE NOT EXISTS (SELECT 1 FROM school WHERE school_code='DEMO_XBP_001');
  SELECT school_id INTO v_school FROM school WHERE school_code='DEMO_XBP_001';

  IF EXISTS (SELECT 1 FROM local_edu_resource WHERE resource_code IN ('DEMO_XBP_MUSEUM','DEMO_XBP_CLASSROOM')
      AND NOT(source_id <=> v_source)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo resource identity conflict';
  END IF;
  INSERT INTO local_edu_resource(resource_code,resource_name,resource_category,region_id,county_region_id,
      township_region_id,address,longitude,latitude,intro,education_value,activity_suggestion,target_grade,source_id,review_status)
  SELECT 'DEMO_XBP_MUSEUM','西柏坡纪念馆（演示资料）','red_culture',v_town,v_county,v_town,
      '河北省石家庄市平山县西柏坡镇',113.9448620,38.3398480,
      '演示资料：围绕西柏坡历史开展红色文化教育。具体开放信息以馆方公告为准。',
      '认识革命历史，培养责任意识。','观察展陈、记录问题、小组讨论。','三至六年级',v_source,'approved'
  WHERE NOT EXISTS (SELECT 1 FROM local_edu_resource WHERE resource_code='DEMO_XBP_MUSEUM');
  INSERT INTO local_edu_resource(resource_code,resource_name,resource_category,region_id,county_region_id,
      township_region_id,address,longitude,latitude,intro,education_value,target_grade,source_id,review_status)
  SELECT 'DEMO_XBP_CLASSROOM','校内红色阅读角（虚拟演示）','public_culture',v_town,v_county,v_town,
      '演示学校内',113.9390000,38.3484380,'虚拟演示资源，用于展示雨天校内替代活动。',
      '通过阅读和讨论理解历史故事。','三至六年级',v_source,'approved'
  WHERE NOT EXISTS (SELECT 1 FROM local_edu_resource WHERE resource_code='DEMO_XBP_CLASSROOM');
  SELECT resource_id INTO v_resource FROM local_edu_resource WHERE resource_code='DEMO_XBP_MUSEUM';
  SELECT resource_id INTO v_resource_two FROM local_edu_resource WHERE resource_code='DEMO_XBP_CLASSROOM';
  INSERT INTO school_resource_rel(school_id,resource_id,relation_type,distance_meters,source_id,review_status)
  SELECT v_school,r.resource_id,'nearby',IF(r.resource_id=v_resource,1100,0),v_source,'approved'
  FROM local_edu_resource r WHERE r.resource_id IN (v_resource,v_resource_two)
  AND NOT EXISTS (SELECT 1 FROM school_resource_rel x WHERE x.school_id=v_school AND x.resource_id=r.resource_id AND x.relation_type='nearby');

  IF EXISTS (SELECT 1 FROM content_chunk WHERE entity_type='resource' AND entity_id IN (v_resource,v_resource_two)
      AND chunk_index=1 AND NOT(source_id <=> v_source)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo knowledge chunk identity conflict';
  END IF;
  INSERT INTO content_chunk(entity_type,entity_id,chunk_title,chunk_text,chunk_index,source_id,retrieval_text,embedding_status)
  SELECT 'resource',r.resource_id,r.resource_name,
      CONCAT(r.intro,' 教学建议：五年级可用40分钟分为导入、观察或阅读、小组讨论三个环节；三年级减少抽象概念，采用故事问答。'),
      1,v_source,CONCAT('西柏坡 爱国主义 思政 五年级 三年级 ',r.resource_name),'pending'
  FROM local_edu_resource r WHERE r.resource_id IN (v_resource,v_resource_two)
  AND NOT EXISTS (SELECT 1 FROM content_chunk x WHERE x.entity_type='resource' AND x.entity_id=r.resource_id AND x.chunk_index=1);

  -- 已有同名账号必须拥有本脚本的统一档案标记，否则整体回滚。
  IF EXISTS (SELECT 1 FROM school_user_account a LEFT JOIN user_profile p ON p.account_id=a.account_id
      WHERE a.username IN ('test_teacher','test_student') AND
        (NOT(p.remark <=> 'gc_demo_v1:owned') OR NOT(a.school_id <=> v_school)
         OR a.role_code<>IF(a.username='test_teacher','teacher','student')
         OR NOT(p.school_id <=> v_school) OR p.profile_type<>a.role_code)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test_teacher/test_student already belongs to other data; no account overwritten';
  END IF;
  INSERT INTO school_user_account(username,password_hash,role_code,school_id,display_name,real_name,account_type,force_password_change,status)
  SELECT 'test_teacher',v_hash,'teacher',v_school,'演示教师','演示教师','teacher',0,'active'
  WHERE NOT EXISTS (SELECT 1 FROM school_user_account WHERE username='test_teacher');
  INSERT INTO school_user_account(username,password_hash,role_code,school_id,display_name,real_name,account_type,force_password_change,status)
  SELECT 'test_student',v_hash,'student',v_school,'演示学生','演示学生','student',0,'active'
  WHERE NOT EXISTS (SELECT 1 FROM school_user_account WHERE username='test_student');
  SELECT account_id INTO v_teacher_account FROM school_user_account WHERE username='test_teacher';
  SELECT account_id INTO v_student_account FROM school_user_account WHERE username='test_student';
  INSERT INTO user_profile(account_id,profile_type,real_name,school_id,status,remark)
  SELECT a.account_id,a.role_code,a.real_name,v_school,'active','gc_demo_v1:owned'
  FROM school_user_account a WHERE a.account_id IN (v_teacher_account,v_student_account)
  AND NOT EXISTS (SELECT 1 FROM user_profile p WHERE p.account_id=a.account_id);

  IF EXISTS (SELECT 1 FROM teacher_profile p WHERE
      ((p.school_id=v_school AND p.teacher_no='DEMO_TEACHER') OR p.account_id=v_teacher_account)
      AND (NOT(p.account_id <=> v_teacher_account) OR NOT(p.school_id <=> v_school)
        OR NOT(p.teacher_no <=> 'DEMO_TEACHER') OR NOT(p.profile_id <=>
          (SELECT profile_id FROM user_profile WHERE account_id=v_teacher_account))))
    OR EXISTS (SELECT 1 FROM student_profile p WHERE
      ((p.school_id=v_school AND p.student_no='DEMO_STUDENT') OR p.account_id=v_student_account)
      AND (NOT(p.account_id <=> v_student_account) OR NOT(p.school_id <=> v_school)
        OR NOT(p.student_no <=> 'DEMO_STUDENT') OR NOT(p.profile_id <=>
          (SELECT profile_id FROM user_profile WHERE account_id=v_student_account)))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo teacher/student profile identity conflict';
  END IF;
  INSERT INTO teacher_profile(account_id,profile_id,school_id,teacher_no,teacher_name,title)
  SELECT v_teacher_account,p.profile_id,v_school,'DEMO_TEACHER','演示教师','思政教师'
  FROM user_profile p WHERE p.account_id=v_teacher_account
  AND NOT EXISTS (SELECT 1 FROM teacher_profile WHERE account_id=v_teacher_account);
  INSERT INTO student_profile(account_id,profile_id,school_id,student_no,student_name,grade_name,enrollment_year)
  SELECT v_student_account,p.profile_id,v_school,'DEMO_STUDENT','演示学生','五年级',2026
  FROM user_profile p WHERE p.account_id=v_student_account
  AND NOT EXISTS (SELECT 1 FROM student_profile WHERE account_id=v_student_account);
  SELECT teacher_id INTO v_teacher FROM teacher_profile WHERE account_id=v_teacher_account;
  SELECT student_id INTO v_student FROM student_profile WHERE account_id=v_student_account;

  IF EXISTS (SELECT 1 FROM class_info WHERE invite_code='GCDEMO5'
      AND (school_id<>v_school OR class_name<>'五年级演示班')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo class invite code conflict';
  END IF;
  INSERT INTO class_info(school_id,class_name,grade_name,invite_code)
  SELECT v_school,'五年级演示班','五年级','GCDEMO5'
  WHERE NOT EXISTS (SELECT 1 FROM class_info WHERE invite_code='GCDEMO5');
  SELECT class_id INTO v_class FROM class_info WHERE invite_code='GCDEMO5';
  INSERT INTO class_teacher(class_id,teacher_id,teacher_role)
  SELECT v_class,v_teacher,'head_teacher' WHERE NOT EXISTS
    (SELECT 1 FROM class_teacher WHERE class_id=v_class AND teacher_id=v_teacher AND teacher_role='head_teacher');
  INSERT INTO class_member(class_id,student_id,join_source,is_primary)
  SELECT v_class,v_student,'manual',1 WHERE NOT EXISTS
    (SELECT 1 FROM class_member WHERE class_id=v_class AND student_id=v_student);
  INSERT INTO sys_account_role(account_id,role_id,data_scope)
  SELECT a.account_id,r.role_id,IF(a.role_code='student','self','school')
  FROM school_user_account a JOIN sys_role r ON r.role_code COLLATE utf8mb4_0900_ai_ci=a.role_code COLLATE utf8mb4_0900_ai_ci
  WHERE a.account_id IN (v_teacher_account,v_student_account)
  AND NOT EXISTS (SELECT 1 FROM sys_account_role ar WHERE ar.account_id=a.account_id AND ar.role_id=r.role_id);

  IF (SELECT COUNT(*) FROM class_learning_task WHERE class_id=v_class AND title='西柏坡观察与讨论（演示任务）')>1
    OR EXISTS (SELECT 1 FROM class_learning_task WHERE class_id=v_class AND title='西柏坡观察与讨论（演示任务）'
      AND (NOT(publisher_teacher_id <=> v_teacher) OR NOT(description <=> 'gc_demo_v1:owned：记录一个历史问题，完成小组讨论。'))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo task identity conflict';
  END IF;
  INSERT INTO class_learning_task(class_id,publisher_teacher_id,title,description,status)
  SELECT v_class,v_teacher,'西柏坡观察与讨论（演示任务）','gc_demo_v1:owned：记录一个历史问题，完成小组讨论。','published'
  WHERE NOT EXISTS (SELECT 1 FROM class_learning_task WHERE class_id=v_class AND title='西柏坡观察与讨论（演示任务）');
  SELECT task_id INTO v_task FROM class_learning_task WHERE class_id=v_class AND title='西柏坡观察与讨论（演示任务）';
  INSERT INTO task_resource_rel(task_id,resource_id)
  SELECT v_task,v_resource WHERE NOT EXISTS (SELECT 1 FROM task_resource_rel WHERE task_id=v_task AND resource_id=v_resource);
  INSERT INTO student_task_progress(task_id,student_id,status)
  SELECT v_task,v_student,'pending' WHERE NOT EXISTS (SELECT 1 FROM student_task_progress WHERE task_id=v_task AND student_id=v_student);

  IF EXISTS (SELECT 1 FROM teaching_activity_plan WHERE plan_code='DEMO_XBP_PLAN'
      AND (school_id<>v_school OR NOT(owner_account_id <=> v_teacher_account) OR NOT(source_id <=> v_source))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo teaching plan identity conflict';
  END IF;
  INSERT INTO teaching_activity_plan(plan_code,school_id,resource_id,theme,activity_type,suitable_grade,
      objective_text,activity_content,duration_minutes,source_id,owner_account_id,generation_source,review_status)
  SELECT 'DEMO_XBP_PLAN',v_school,v_resource,'西柏坡爱国主义教育（演示）','field_trip','五年级',
      '了解历史，形成问题意识。','导入10分钟，观察或阅读15分钟，小组讨论15分钟。',40,v_source,v_teacher_account,'manual','approved'
  WHERE NOT EXISTS (SELECT 1 FROM teaching_activity_plan WHERE plan_code='DEMO_XBP_PLAN');
  SELECT plan_id INTO v_plan FROM teaching_activity_plan WHERE plan_code='DEMO_XBP_PLAN';
  INSERT INTO teaching_activity_plan_resource(plan_id,resource_id,is_primary)
  SELECT v_plan,v_resource,1 WHERE NOT EXISTS
    (SELECT 1 FROM teaching_activity_plan_resource WHERE plan_id=v_plan AND resource_id=v_resource);

  COMMIT;
  SET SESSION foreign_key_checks=old_fk;
  SET SESSION unique_checks=old_unique;
  DO RELEASE_LOCK(CONCAT('gc_schema:',DATABASE()));
  SELECT DATABASE() AS target_database,'Demo ready; existing passwords preserved' AS result,
      v_school AS school_id,'test_teacher' AS teacher_username,'test_student' AS student_username;
END$$
DELIMITER ;
CALL gc_demo_apply();
DROP PROCEDURE IF EXISTS gc_demo_apply;
DROP PROCEDURE IF EXISTS gc_demo_region;
