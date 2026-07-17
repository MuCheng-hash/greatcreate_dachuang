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

-- Extra pilot resources and RAG citations for SCH_SJZ_GC_0001.
INSERT INTO data_source
  (source_name, source_type, organization_name, base_url, reliability_level, license_note, crawl_allowed, remark)
SELECT '里庄小学本土思政资源采集表', 'other', '项目组样例数据', NULL, 4, '仅用于项目演示与课程方案生成测试', 0,
       '围绕石家庄市藁城区常安镇里庄小学构造的样例资源来源'
WHERE NOT EXISTS (
  SELECT 1 FROM data_source WHERE source_name = '里庄小学本土思政资源采集表'
);

INSERT INTO local_edu_resource
  (resource_code, resource_name, resource_alias, resource_category, resource_subcategory, region_id, county_region_id,
   township_region_id, address, longitude, latitude, organization_name, opening_time_desc, reservation_required,
   recommended_visit_minutes, intro, education_value, activity_suggestion, target_grade, safety_note, source_id,
   review_status, is_active)
SELECT x.resource_code, x.resource_name, NULL, x.resource_category, x.resource_subcategory, NULL, NULL, NULL,
       x.address, x.longitude, x.latitude, x.organization_name, x.opening_time_desc, x.reservation_required,
       x.recommended_visit_minutes, x.intro, x.education_value, x.activity_suggestion, x.target_grade, x.safety_note,
       ds.source_id, 'approved', 1
FROM (
    SELECT 'RES_SJZ_GC_0003' AS resource_code, '常安镇新时代文明实践站' AS resource_name, 'social_practice' AS resource_category, '文明实践' AS resource_subcategory,
           '河北省石家庄市藁城区常安镇示例地址' AS address, 114.9528000 AS longitude, 38.0287000 AS latitude, '常安镇新时代文明实践站' AS organization_name,
           '工作日开放' AS opening_time_desc, 1 AS reservation_required, 60 AS recommended_visit_minutes,
           '可组织文明礼仪、志愿服务和社区治理主题实践。' AS intro, '适合开展公共责任、文明行为和基层治理教育。' AS education_value,
           '设计文明劝导、社区观察、志愿服务记录等活动。' AS activity_suggestion, '小学高年级/初中' AS target_grade, '需提前联系实践站并分组行动。' AS safety_note
    UNION ALL SELECT 'RES_SJZ_GC_0004', '里庄村史馆', 'local_history', '村史教育',
           '河北省石家庄市藁城区里庄村示例地址', 114.9560000, 38.0259000, '里庄村村委会',
           '预约开放', 1, 45, '展示村庄发展、乡土记忆和基层建设变化。', '适合开展乡土认同、家乡变化和劳动创造教育。',
           '组织学生绘制家乡变化时间线，采访长辈讲述村史。', '小学/初中', '馆内参观保持安静，注意展陈保护。'
    UNION ALL SELECT 'RES_SJZ_GC_0005', '常安镇农耕体验园', 'labor_education', '农耕劳动',
           '河北省石家庄市藁城区常安镇示例农园', 114.9612000, 38.0303000, '常安镇农耕体验园',
           '季节性开放', 1, 90, '提供农作物观察、简单农事体验和劳动教育场景。', '适合开展劳动观念、粮食安全和生态文明教育。',
           '设计观察作物、记录农事流程、完成劳动体验反思。', '小学/初中', '户外活动注意防晒、饮水和工具使用安全。'
    UNION ALL SELECT 'RES_SJZ_GC_0006', '常安镇卫生院健康教育角', 'social_practice', '健康教育',
           '河北省石家庄市藁城区常安镇示例地址', 114.9489000, 38.0279000, '常安镇卫生院',
           '工作日开放', 1, 40, '可开展公共卫生、生命健康和社会服务主题教育。', '适合开展生命安全、公共卫生责任和服务意识教育。',
           '组织健康知识宣传海报制作、公共卫生访谈和服务岗位观察。', '小学高年级/初中', '进入医疗场所需遵守秩序并避免影响诊疗。'
    UNION ALL SELECT 'RES_SJZ_GC_0007', '里庄村红色记忆讲述点', 'red_culture', '红色故事',
           '河北省石家庄市藁城区里庄村示例地址', 114.9543000, 38.0269000, '里庄村党群服务中心',
           '预约开放', 1, 45, '依托村内老党员和地方故事开展红色记忆讲述。', '适合开展理想信念、榜样学习和口述历史教育。',
           '组织红色故事采访、讲述稿整理和班级分享。', '小学高年级/初中', '访谈活动需尊重讲述者并提前征得同意。'
    UNION ALL SELECT 'RES_SJZ_GC_0008', '常安镇生态小公园', 'ecological_civilization', '生态观察',
           '河北省石家庄市藁城区常安镇示例公园', 114.9516000, 38.0312000, '常安镇综合管理服务中心',
           '全天开放', 0, 50, '可用于生态文明、公共空间和环境保护主题观察。', '适合开展绿色发展、公共意识和环境责任教育。',
           '开展垃圾分类观察、植物记录和公共空间文明倡议。', '小学/初中', '户外活动注意交通、集合和天气变化。'
    UNION ALL SELECT 'RES_SJZ_GC_0009', '常安镇综合文化服务中心', 'public_culture', '公共文化',
           '河北省石家庄市藁城区常安镇示例地址', 114.9507000, 38.0284000, '常安镇综合文化服务中心',
           '工作日开放', 1, 60, '提供乡村阅读、文化展示和群众活动空间。', '适合开展公共文化服务、阅读推广和文化自信教育。',
           '组织阅读分享、公共文化服务观察和活动策划。', '小学/初中', '集体活动需遵守场馆管理要求。'
    UNION ALL SELECT 'RES_SJZ_GC_0010', '里庄小学劳动实践角', 'labor_education', '校园劳动',
           '河北省石家庄市藁城区常安镇里庄小学', 114.9539000, 38.0270000, '石家庄市藁城区常安镇里庄小学',
           '校内开放', 0, 35, '校内劳动实践空间，可连接校园责任岗和班级劳动课程。', '适合开展劳动习惯、责任分工和集体服务教育。',
           '设计校园清洁、种植观察、责任岗轮值和劳动日志。', '小学', '校内活动注意工具使用和教师看护。'
) x
JOIN data_source ds ON ds.source_name = '里庄小学本土思政资源采集表'
ON DUPLICATE KEY UPDATE
  resource_name = VALUES(resource_name),
  resource_category = VALUES(resource_category),
  resource_subcategory = VALUES(resource_subcategory),
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
SELECT s.school_id, r.resource_id, 'nearby', x.distance_meters, 'walk', x.duration_minutes,
       x.reachability_level, x.priority_level, x.theme, ds.source_id, 'approved'
FROM (
    SELECT 'RES_SJZ_GC_0003' AS resource_code, 600 AS distance_meters, 12 AS duration_minutes, 'near' AS reachability_level, 5 AS priority_level, '适合开展文明实践与社区责任教育' AS theme
    UNION ALL SELECT 'RES_SJZ_GC_0004', 450, 10, 'near', 4, '适合开展村史村情与家乡变化主题学习'
    UNION ALL SELECT 'RES_SJZ_GC_0005', 1500, 25, 'medium', 4, '适合开展劳动教育与粮食安全主题实践'
    UNION ALL SELECT 'RES_SJZ_GC_0006', 900, 15, 'near', 3, '适合开展公共卫生与生命健康教育'
    UNION ALL SELECT 'RES_SJZ_GC_0007', 350, 8, 'near', 5, '适合开展红色故事采访和口述历史学习'
    UNION ALL SELECT 'RES_SJZ_GC_0008', 1100, 18, 'medium', 3, '适合开展生态文明与公共空间观察'
    UNION ALL SELECT 'RES_SJZ_GC_0009', 750, 12, 'near', 4, '适合开展公共文化服务与阅读推广活动'
    UNION ALL SELECT 'RES_SJZ_GC_0010', 50, 3, 'near', 5, '适合开展校园劳动和责任岗位课程'
) x
JOIN school s ON s.school_code = 'SCH_SJZ_GC_0001'
JOIN local_edu_resource r ON r.resource_code = x.resource_code
JOIN data_source ds ON ds.source_name = '里庄小学本土思政资源采集表'
ON DUPLICATE KEY UPDATE
  distance_meters = VALUES(distance_meters),
  recommended_travel_mode = VALUES(recommended_travel_mode),
  estimated_duration_minutes = VALUES(estimated_duration_minutes),
  reachability_level = VALUES(reachability_level),
  priority_level = VALUES(priority_level),
  education_theme_summary = VALUES(education_theme_summary),
  source_id = VALUES(source_id),
  review_status = VALUES(review_status);

INSERT INTO content_chunk
  (entity_type, entity_id, chunk_title, chunk_text, chunk_index, source_id, token_count, embedding_status)
SELECT 'resource', r.resource_id, CONCAT(r.resource_name, '教学资源说明'),
       CONCAT(r.intro, ' ', r.education_value, ' ', r.activity_suggestion, ' 安全提示：', r.safety_note),
       1, ds.source_id, 120, 'pending'
FROM local_edu_resource r
JOIN data_source ds ON ds.source_name = '里庄小学本土思政资源采集表'
WHERE r.resource_code LIKE 'RES_SJZ_GC_%'
ON DUPLICATE KEY UPDATE
  chunk_title = VALUES(chunk_title),
  chunk_text = VALUES(chunk_text),
  source_id = VALUES(source_id),
  token_count = VALUES(token_count),
  embedding_status = VALUES(embedding_status);

INSERT INTO entity_source_rel
  (entity_type, entity_id, source_id, source_url, captured_at, source_excerpt, credibility_score)
SELECT 'resource', r.resource_id, ds.source_id, CONCAT('demo://lizhuang/', r.resource_code), NOW(),
       CONCAT(r.resource_name, '：', r.education_value), 4
FROM local_edu_resource r
JOIN data_source ds ON ds.source_name = '里庄小学本土思政资源采集表'
WHERE r.resource_code LIKE 'RES_SJZ_GC_%'
ON DUPLICATE KEY UPDATE
  source_excerpt = VALUES(source_excerpt),
  credibility_score = VALUES(credibility_score);
