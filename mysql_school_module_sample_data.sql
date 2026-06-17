USE red_culture_platform;

SET NAMES utf8mb4;

-- Sample data for the school-centered map module.
-- This seed intentionally does not depend on administrative_region or data_source,
-- so it can run even after the red-culture sample data has been cleared.

INSERT INTO school
  (school_code, school_name, school_alias, region_id, county_region_id, township_region_id, village_region_id,
   school_level, school_type, school_nature, is_rural_school, is_teaching_point, address, postcode, contact_phone,
   principal_name, longitude, latitude, geo_source_type, poi_name, poi_address, poi_type, geo_confidence,
   geo_verified, intro, source_id, review_status, is_active)
VALUES
  ('SCH_SJZ_GC_0001', '石家庄市藁城区常安镇里庄小学', NULL, NULL, NULL, NULL, NULL,
   'primary', '村小', 'public', 1, 0, '河北省石家庄市藁城区常安镇里庄村振兴大街西220号', NULL, NULL,
   NULL, 114.9537180, 38.0271030, 'amap_poi', '石家庄市藁城区常安镇里庄小学', '河北省石家庄市藁城区常安镇里庄村振兴大街西220号', '科教文化服务;学校;小学', 'high',
   0, '可作为样例乡村小学，用于验证学校周边本土思政资源地图平台。', NULL, 'approved', 1),
  ('SCH_SJZ_PS_0001', '平山县西柏坡希望小学', NULL, NULL, NULL, NULL, NULL,
   'primary', '乡镇中心小学', 'public', 1, 0, '河北省石家庄市平山县西柏坡镇示例地址', NULL, NULL,
   NULL, 113.9815000, 38.3452000, 'manual', '平山县西柏坡希望小学', '河北省石家庄市平山县西柏坡镇示例地址', '科教文化服务;学校;小学', 'medium',
   0, '用于红色文化资源密集区域学校试点。', NULL, 'approved', 1),
  ('SCH_BD_YX_0001', '易县狼牙山镇中心小学', NULL, NULL, NULL, NULL, NULL,
   'primary', '乡镇中心小学', 'public', 1, 0, '河北省保定市易县狼牙山镇示例地址', NULL, NULL,
   NULL, 115.4419000, 39.4018000, 'manual', '易县狼牙山镇中心小学', '河北省保定市易县狼牙山镇示例地址', '科教文化服务;学校;小学', 'medium',
   0, '用于抗战主题资源样例学校试点。', NULL, 'approved', 1)
ON DUPLICATE KEY UPDATE
  school_name = VALUES(school_name),
  school_alias = VALUES(school_alias),
  region_id = VALUES(region_id),
  county_region_id = VALUES(county_region_id),
  township_region_id = VALUES(township_region_id),
  village_region_id = VALUES(village_region_id),
  school_level = VALUES(school_level),
  school_type = VALUES(school_type),
  school_nature = VALUES(school_nature),
  is_rural_school = VALUES(is_rural_school),
  is_teaching_point = VALUES(is_teaching_point),
  address = VALUES(address),
  longitude = VALUES(longitude),
  latitude = VALUES(latitude),
  geo_source_type = VALUES(geo_source_type),
  poi_name = VALUES(poi_name),
  poi_address = VALUES(poi_address),
  poi_type = VALUES(poi_type),
  geo_confidence = VALUES(geo_confidence),
  geo_verified = VALUES(geo_verified),
  intro = VALUES(intro),
  source_id = VALUES(source_id),
  review_status = VALUES(review_status),
  is_active = VALUES(is_active);

DELETE sgr
FROM school_geo_record sgr
JOIN school s ON s.school_id = sgr.school_id
WHERE s.school_code IN ('SCH_SJZ_GC_0001', 'SCH_SJZ_PS_0001', 'SCH_BD_YX_0001');

INSERT INTO school_geo_record
  (school_id, longitude, latitude, source_type, poi_name, poi_address, poi_type, confidence_level,
   is_manual_reviewed, review_result, reviewer_name, reviewed_at, is_current, remark)
SELECT school_id, longitude, latitude, geo_source_type, poi_name, poi_address, poi_type, geo_confidence,
       0, 'pending', NULL, NULL, 1, '初始化样例坐标'
FROM school
WHERE school_code IN ('SCH_SJZ_GC_0001', 'SCH_SJZ_PS_0001', 'SCH_BD_YX_0001');

INSERT INTO local_edu_resource
  (resource_code, resource_name, resource_alias, resource_category, resource_subcategory, region_id, county_region_id,
   township_region_id, address, longitude, latitude, organization_name, opening_time_desc, reservation_required,
   recommended_visit_minutes, intro, education_value, activity_suggestion, target_grade, safety_note, source_id,
   review_status, is_active)
VALUES
  ('RES_SJZ_XBP_0001', '西柏坡中共中央旧址', '西柏坡旧址', 'red_culture', '革命旧址', NULL, NULL,
   NULL, '河北省石家庄市平山县西柏坡镇', 113.9783000, 38.3439000, '西柏坡景区', '08:30-17:00', 0,
   120, '西柏坡中共中央旧址是河北红色文化的重要代表。', '可用于爱国主义教育、党史教育、理想信念教育。', '开展红色故事讲解、研学路线设计、主题班会。', '小学高年级/初中/高中', '山区活动需注意集体组织与交通安全。', NULL,
   'approved', 1),
  ('RES_SJZ_XBP_0002', '西柏坡纪念馆', NULL, 'patriotism_base', '纪念馆', NULL, NULL,
   NULL, '河北省石家庄市平山县西柏坡镇', 113.9791000, 38.3445000, '西柏坡纪念馆', '09:00-17:00', 0,
   90, '西柏坡纪念馆是开展红色文化教育的重要场馆。', '适合开展场馆式思政教育、图片文献教学和主题研学。', '可组织讲解参观、研学打卡、展陈观察记录。', '小学高年级/初中/高中', '集体参观需提前确认开放安排。', NULL,
   'approved', 1),
  ('RES_BD_LYS_0001', '狼牙山五壮士纪念地', '狼牙山纪念地', 'red_culture', '抗战遗址', NULL, NULL,
   NULL, '河北省保定市易县狼牙山景区', 115.4448000, 39.4054000, '狼牙山景区', '08:00-17:30', 0,
   180, '狼牙山纪念地适合爱国主义教育与研学。', '可用于抗战精神、英勇担当、集体主义教育。', '可开展抗战主题研学、英雄故事分享、路线式教育活动。', '小学高年级/初中', '山区路段较多，需重视行进安全。', NULL,
   'approved', 1),
  ('RES_SJZ_GC_0001', '常安镇敬老院', NULL, 'public_welfare', '养老院', NULL, NULL,
   NULL, '河北省石家庄市藁城区常安镇示例地址', 114.9498000, 38.0296000, '常安镇敬老院', NULL, 1,
   60, '可作为敬老爱老和社会责任教育的公益实践场所。', '适合开展尊老爱老、志愿服务、社会责任教育。', '可组织节日慰问、劳动服务、口述历史访谈。', '小学高年级/初中', '进入养老院需提前协调并注意礼仪与秩序。', NULL,
   'approved', 1),
  ('RES_SJZ_GC_0002', '里庄村乡贤文化墙', NULL, 'traditional_culture', '乡贤文化', NULL, NULL,
   NULL, '河北省石家庄市藁城区常安镇里庄村示例地址', 114.9552000, 38.0265000, '里庄村村委会', NULL, 0,
   30, '乡贤文化墙可作为本土优秀传统文化和家风教育资源。', '适合开展家风家训、乡土认同、优秀传统文化教育。', '可组织观察记录、村史讲述、主题讨论。', '小学/初中', '村内步行活动注意交通安全。', NULL,
   'approved', 1)
ON DUPLICATE KEY UPDATE
  resource_name = VALUES(resource_name),
  resource_alias = VALUES(resource_alias),
  resource_category = VALUES(resource_category),
  resource_subcategory = VALUES(resource_subcategory),
  region_id = VALUES(region_id),
  county_region_id = VALUES(county_region_id),
  township_region_id = VALUES(township_region_id),
  address = VALUES(address),
  longitude = VALUES(longitude),
  latitude = VALUES(latitude),
  organization_name = VALUES(organization_name),
  opening_time_desc = VALUES(opening_time_desc),
  reservation_required = VALUES(reservation_required),
  recommended_visit_minutes = VALUES(recommended_visit_minutes),
  intro = VALUES(intro),
  education_value = VALUES(education_value),
  activity_suggestion = VALUES(activity_suggestion),
  target_grade = VALUES(target_grade),
  safety_note = VALUES(safety_note),
  source_id = VALUES(source_id),
  review_status = VALUES(review_status),
  is_active = VALUES(is_active);

INSERT INTO school_resource_rel
  (school_id, resource_id, relation_type, distance_meters, recommended_travel_mode, estimated_duration_minutes,
   reachability_level, priority_level, education_theme_summary, source_id, review_status)
SELECT s.school_id, r.resource_id, x.relation_type, x.distance_meters, x.travel_mode, x.duration_minutes,
       x.reachability_level, x.priority_level, x.theme, NULL, 'approved'
FROM school s
JOIN (
    SELECT 'SCH_SJZ_GC_0001' AS school_code, 'RES_SJZ_GC_0001' AS resource_code, 'nearby' AS relation_type,
           1800 AS distance_meters, 'walk' AS travel_mode, 30 AS duration_minutes, 'medium' AS reachability_level,
           4 AS priority_level, '可开展敬老爱老与社会责任主题实践活动' AS theme
    UNION ALL
    SELECT 'SCH_SJZ_GC_0001', 'RES_SJZ_GC_0002', 'curriculum_support',
           250, 'walk', 10, 'near', 5, '适合开展乡贤文化与家风教育微课程'
    UNION ALL
    SELECT 'SCH_SJZ_PS_0001', 'RES_SJZ_XBP_0001', 'research_route',
           400, 'walk', 15, 'near', 5, '适合开展西柏坡红色文化研学与党史教育'
    UNION ALL
    SELECT 'SCH_SJZ_PS_0001', 'RES_SJZ_XBP_0002', 'practice',
           600, 'walk', 20, 'near', 4, '适合开展纪念馆参观与主题讲解活动'
    UNION ALL
    SELECT 'SCH_BD_YX_0001', 'RES_BD_LYS_0001', 'research_route',
           1200, 'walk', 35, 'medium', 5, '适合开展抗战精神、英雄故事与集体主义教育'
) x ON x.school_code = s.school_code
JOIN local_edu_resource r ON r.resource_code = x.resource_code
ON DUPLICATE KEY UPDATE
  distance_meters = VALUES(distance_meters),
  recommended_travel_mode = VALUES(recommended_travel_mode),
  estimated_duration_minutes = VALUES(estimated_duration_minutes),
  reachability_level = VALUES(reachability_level),
  priority_level = VALUES(priority_level),
  education_theme_summary = VALUES(education_theme_summary),
  source_id = VALUES(source_id),
  review_status = VALUES(review_status);

INSERT INTO teaching_activity_plan
  (plan_code, school_id, resource_id, theme, activity_type, suitable_grade, objective_text, activity_content,
   preparation_text, safety_text, expected_outcome, duration_minutes, source_id, review_status, is_active)
SELECT x.plan_code, s.school_id, r.resource_id, x.theme, x.activity_type, x.suitable_grade, x.objective_text,
       x.activity_content, x.preparation_text, x.safety_text, x.expected_outcome, x.duration_minutes, NULL, 'approved', 1
FROM school s
JOIN (
    SELECT 'PLAN_SJZ_GC_0001' AS plan_code, 'SCH_SJZ_GC_0001' AS school_code, 'RES_SJZ_GC_0001' AS resource_code,
           '敬老爱老与社会责任教育' AS theme, 'volunteer_service' AS activity_type, '小学高年级' AS suitable_grade,
           '引导学生理解尊老爱老的价值，形成服务意识。' AS objective_text,
           '组织学生了解养老院基本情况，开展节日慰问、打扫卫生、陪伴交流等志愿服务。' AS activity_content,
           '提前与养老院对接，准备慰问用品和分组安排。' AS preparation_text,
           '活动中注意秩序管理，避免喧闹，尊重老人隐私。' AS safety_text,
           '形成活动记录、感想分享和班级主题展示。' AS expected_outcome,
           120 AS duration_minutes
    UNION ALL
    SELECT 'PLAN_SJZ_XBP_0001', 'SCH_SJZ_PS_0001', 'RES_SJZ_XBP_0001',
           '西柏坡红色记忆主题研学', 'field_trip', '小学高年级/初中',
           '帮助学生理解西柏坡精神和革命历史。',
           '组织学生参观旧址，结合讲解词完成观察任务单，并围绕“两个务必”开展讨论。',
           '准备任务单、讲解资料和分组路线。',
           '山区与景区活动需统一行动，服从带队安排。',
           '完成主题笔记、研学汇报和班会展示。',
           180
) x ON x.school_code = s.school_code
JOIN local_edu_resource r ON r.resource_code = x.resource_code
ON DUPLICATE KEY UPDATE
  theme = VALUES(theme),
  activity_type = VALUES(activity_type),
  suitable_grade = VALUES(suitable_grade),
  objective_text = VALUES(objective_text),
  activity_content = VALUES(activity_content),
  preparation_text = VALUES(preparation_text),
  safety_text = VALUES(safety_text),
  expected_outcome = VALUES(expected_outcome),
  duration_minutes = VALUES(duration_minutes),
  source_id = VALUES(source_id),
  review_status = VALUES(review_status),
  is_active = VALUES(is_active);
