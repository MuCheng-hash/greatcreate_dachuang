USE red_culture_platform;

SET NAMES utf8mb4;

START TRANSACTION;

UPDATE school
SET address = '河北省石家庄市平山县西柏坡镇迎宾路7号',
    longitude = 113.9390000,
    latitude = 38.3484380,
    geo_source_type = 'amap_poi',
    poi_name = '西柏坡希望小学',
    poi_address = '迎宾路7号',
    poi_type = '科教文化服务;学校;小学',
    geo_confidence = 'high',
    geo_verified = 1
WHERE school_code = 'SCH_SJZ_PS_0001';

UPDATE school_geo_record sgr
JOIN school s ON s.school_id = sgr.school_id
SET sgr.is_current = 0
WHERE s.school_code = 'SCH_SJZ_PS_0001'
  AND NOT (
    sgr.longitude = 113.9390000
    AND sgr.latitude = 38.3484380
    AND sgr.source_type = 'amap_poi'
    AND sgr.is_current = 1
  );

INSERT INTO school_geo_record
  (school_id, longitude, latitude, source_type, poi_name, poi_address, poi_type, confidence_level,
   is_manual_reviewed, review_result, reviewer_name, reviewed_at, is_current, remark)
SELECT s.school_id, 113.9390000, 38.3484380, 'amap_poi', '西柏坡希望小学', '迎宾路7号',
       '科教文化服务;学校;小学', 'high', 1, 'confirmed', 'system_amap_verification', CURRENT_TIMESTAMP,
       1, '高德 POI B01370VWBV'
FROM school s
WHERE s.school_code = 'SCH_SJZ_PS_0001'
  AND NOT EXISTS (
    SELECT 1
    FROM school_geo_record current_geo
    WHERE current_geo.school_id = s.school_id
      AND current_geo.longitude = 113.9390000
      AND current_geo.latitude = 38.3484380
      AND current_geo.source_type = 'amap_poi'
      AND current_geo.is_current = 1
  );

UPDATE local_edu_resource
SET address = '河北省石家庄市平山县西柏坡镇西柏坡村',
    longitude = 113.9407980,
    latitude = 38.3410770,
    external_provider = 'amap',
    external_place_id = 'B013705X0E',
    source_checked_at = CURRENT_TIMESTAMP
WHERE resource_code = 'RES_SJZ_XBP_0001';

UPDATE local_edu_resource
SET address = '河北省石家庄市平山县西柏坡镇',
    longitude = 113.9448620,
    latitude = 38.3398480,
    external_provider = 'amap',
    external_place_id = 'B01370T0XJ',
    source_checked_at = CURRENT_TIMESTAMP
WHERE resource_code = 'RES_SJZ_XBP_0002';

UPDATE red_site
SET address = '河北省石家庄市平山县西柏坡镇西柏坡村',
    longitude = 113.9407980,
    latitude = 38.3410770
WHERE site_code = 'SITE_HEB_XBP_001';

UPDATE historical_event
SET longitude = 113.9407980,
    latitude = 38.3410770
WHERE event_code = 'EVENT_HEB_SDZY_001';

UPDATE memorial_hall
SET address = '河北省石家庄市平山县西柏坡镇',
    longitude = 113.9448620,
    latitude = 38.3398480
WHERE memorial_code = 'MEM_HEB_XBP_001';

UPDATE school_resource_rel rel
JOIN school s ON s.school_id = rel.school_id
JOIN local_edu_resource r ON r.resource_id = rel.resource_id
SET rel.distance_meters = CASE r.resource_code
      WHEN 'RES_SJZ_XBP_0001' THEN 834
      WHEN 'RES_SJZ_XBP_0002' THEN 1084
    END
WHERE s.school_code = 'SCH_SJZ_PS_0001'
  AND r.resource_code IN ('RES_SJZ_XBP_0001', 'RES_SJZ_XBP_0002');

UPDATE resource_discovery_run run
JOIN school s ON s.school_id = run.school_id
SET run.error_message = CASE
      WHEN run.status IN ('pending', 'running') THEN '学校坐标已校准，请重新执行周边资源发现'
      ELSE run.error_message
    END,
    run.completed_at = CASE
      WHEN run.status IN ('pending', 'running') THEN CURRENT_TIMESTAMP
      ELSE run.completed_at
    END,
    run.cache_expires_at = CURRENT_TIMESTAMP,
    run.status = CASE WHEN run.status IN ('pending', 'running') THEN 'failed' ELSE run.status END
WHERE s.school_code = 'SCH_SJZ_PS_0001';

COMMIT;
