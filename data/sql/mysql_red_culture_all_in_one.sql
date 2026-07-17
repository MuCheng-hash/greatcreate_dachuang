-- Merged MySQL 8.0 import script for red_culture_platform
-- Source files:
-- 1. mysql_red_culture_schema.sql
-- 2. mysql_red_culture_seed.sql
-- 3. mysql_hebei_region_extension.sql
-- 4. boundary_geojson_update_template.sql

CREATE DATABASE IF NOT EXISTS red_culture_platform
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE red_culture_platform;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- Reset existing tables so the script can be imported repeatedly.
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS content_chunk;
DROP TABLE IF EXISTS entity_source_rel;
DROP TABLE IF EXISTS resource_media;
DROP TABLE IF EXISTS story_entity_rel;
DROP TABLE IF EXISTS memorial_event_rel;
DROP TABLE IF EXISTS memorial_hero_rel;
DROP TABLE IF EXISTS memorial_site_rel;
DROP TABLE IF EXISTS event_hero_rel;
DROP TABLE IF EXISTS site_hero_rel;
DROP TABLE IF EXISTS site_event_rel;
DROP TABLE IF EXISTS entity_tag_rel;
DROP TABLE IF EXISTS red_story;
DROP TABLE IF EXISTS memorial_hall;
DROP TABLE IF EXISTS historical_event;
DROP TABLE IF EXISTS hero_person;
DROP TABLE IF EXISTS red_site;
DROP TABLE IF EXISTS tag_info;
DROP TABLE IF EXISTS data_source;
DROP TABLE IF EXISTS administrative_region;
DROP TABLE IF EXISTS teaching_activity_plan;
DROP TABLE IF EXISTS school_resource_rel;
DROP TABLE IF EXISTS school_user_account;
DROP TABLE IF EXISTS school_registration;
DROP TABLE IF EXISTS school_geo_record;
DROP TABLE IF EXISTS local_edu_resource;
DROP TABLE IF EXISTS school;

CREATE TABLE administrative_region (
  region_id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  parent_region_id     BIGINT NULL,
  region_name          VARCHAR(100) NOT NULL,
  region_level         ENUM('province', 'city', 'county', 'township', 'village') NOT NULL,
  adcode               VARCHAR(20) NULL,
  center_longitude     DECIMAL(10,7) NULL,
  center_latitude      DECIMAL(10,7) NULL,
  boundary_geojson     JSON NULL,
  intro                TEXT NULL,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_region_parent
    FOREIGN KEY (parent_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT uk_region_adcode UNIQUE (adcode),
  KEY idx_region_parent (parent_region_id),
  KEY idx_region_name_level (region_name, region_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE data_source (
  source_id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_name          VARCHAR(200) NOT NULL,
  source_type          ENUM('government', 'encyclopedia', 'news', 'museum', 'paper', 'other') NOT NULL,
  organization_name    VARCHAR(200) NULL,
  base_url             VARCHAR(500) NULL,
  reliability_level    TINYINT NOT NULL DEFAULT 3,
  license_note         VARCHAR(255) NULL,
  crawl_allowed        TINYINT(1) NOT NULL DEFAULT 1,
  last_crawled_at      DATETIME NULL,
  remark               TEXT NULL,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_source_type (source_type),
  KEY idx_source_name (source_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE red_site (
  site_id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  site_code            VARCHAR(50) NOT NULL,
  site_name            VARCHAR(200) NOT NULL,
  site_alias           VARCHAR(200) NULL,
  region_id            BIGINT NULL,
  address              VARCHAR(300) NULL,
  longitude            DECIMAL(10,7) NULL,
  latitude             DECIMAL(10,7) NULL,
  established_date     DATE NULL,
  established_year     SMALLINT NULL,
  site_level           ENUM('national', 'provincial', 'municipal', 'county', 'other') NOT NULL DEFAULT 'other',
  protection_level     VARCHAR(100) NULL,
  historical_background TEXT NULL,
  intro                TEXT NULL,
  opening_time_desc    VARCHAR(255) NULL,
  suggested_visit_minutes INT NULL,
  official_url         VARCHAR(500) NULL,
  review_status        ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active            TINYINT(1) NOT NULL DEFAULT 1,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_site_code UNIQUE (site_code),
  CONSTRAINT fk_site_region
    FOREIGN KEY (region_id) REFERENCES administrative_region(region_id),
  KEY idx_site_name (site_name),
  KEY idx_site_region (region_id),
  KEY idx_site_status (review_status, is_active),
  KEY idx_site_geo (longitude, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hero_person (
  hero_id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  hero_code            VARCHAR(50) NOT NULL,
  hero_name            VARCHAR(100) NOT NULL,
  hero_alias           VARCHAR(200) NULL,
  gender               ENUM('male', 'female', 'unknown') NOT NULL DEFAULT 'unknown',
  birth_year           SMALLINT NULL,
  death_year           SMALLINT NULL,
  birth_date_text      VARCHAR(50) NULL,
  death_date_text      VARCHAR(50) NULL,
  native_place_region_id BIGINT NULL,
  native_place_text    VARCHAR(200) NULL,
  profile_summary      TEXT NULL,
  main_deeds           LONGTEXT NULL,
  official_url         VARCHAR(500) NULL,
  review_status        ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active            TINYINT(1) NOT NULL DEFAULT 1,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_hero_code UNIQUE (hero_code),
  CONSTRAINT fk_hero_native_region
    FOREIGN KEY (native_place_region_id) REFERENCES administrative_region(region_id),
  KEY idx_hero_name (hero_name),
  KEY idx_hero_birth_death (birth_year, death_year),
  KEY idx_hero_region (native_place_region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE historical_event (
  event_id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_code           VARCHAR(50) NOT NULL,
  event_name           VARCHAR(200) NOT NULL,
  event_alias          VARCHAR(200) NULL,
  primary_region_id    BIGINT NULL,
  event_time_text      VARCHAR(100) NULL,
  start_date           DATE NULL,
  end_date             DATE NULL,
  start_year           SMALLINT NULL,
  end_year             SMALLINT NULL,
  longitude            DECIMAL(10,7) NULL,
  latitude             DECIMAL(10,7) NULL,
  historical_significance TEXT NULL,
  event_process        LONGTEXT NULL,
  result_impact        TEXT NULL,
  official_url         VARCHAR(500) NULL,
  review_status        ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active            TINYINT(1) NOT NULL DEFAULT 1,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_event_code UNIQUE (event_code),
  CONSTRAINT fk_event_region
    FOREIGN KEY (primary_region_id) REFERENCES administrative_region(region_id),
  KEY idx_event_name (event_name),
  KEY idx_event_time (start_date, end_date),
  KEY idx_event_region (primary_region_id),
  KEY idx_event_geo (longitude, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memorial_hall (
  memorial_id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  memorial_code        VARCHAR(50) NOT NULL,
  memorial_name        VARCHAR(200) NOT NULL,
  region_id            BIGINT NULL,
  address              VARCHAR(300) NULL,
  longitude            DECIMAL(10,7) NULL,
  latitude             DECIMAL(10,7) NULL,
  exhibition_content   LONGTEXT NULL,
  intro                TEXT NULL,
  opening_time_desc    VARCHAR(255) NULL,
  ticket_info          VARCHAR(255) NULL,
  contact_phone        VARCHAR(50) NULL,
  official_url         VARCHAR(500) NULL,
  review_status        ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active            TINYINT(1) NOT NULL DEFAULT 1,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_memorial_code UNIQUE (memorial_code),
  CONSTRAINT fk_memorial_region
    FOREIGN KEY (region_id) REFERENCES administrative_region(region_id),
  KEY idx_memorial_name (memorial_name),
  KEY idx_memorial_region (region_id),
  KEY idx_memorial_geo (longitude, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE red_story (
  story_id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  story_code           VARCHAR(50) NOT NULL,
  story_title          VARCHAR(200) NOT NULL,
  related_region_id    BIGINT NULL,
  age_group            ENUM('primary', 'middle', 'high', 'college', 'general') NOT NULL DEFAULT 'general',
  summary              TEXT NULL,
  story_content        LONGTEXT NOT NULL,
  source_id            BIGINT NULL,
  review_status        ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active            TINYINT(1) NOT NULL DEFAULT 1,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_story_code UNIQUE (story_code),
  CONSTRAINT fk_story_region
    FOREIGN KEY (related_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_story_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  KEY idx_story_title (story_title),
  KEY idx_story_region (related_region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tag_info (
  tag_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  tag_name             VARCHAR(100) NOT NULL,
  tag_type             ENUM('theme', 'period', 'region', 'education', 'route', 'other') NOT NULL DEFAULT 'other',
  description          VARCHAR(255) NULL,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_tag_name_type UNIQUE (tag_name, tag_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE entity_tag_rel (
  rel_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type          ENUM('site', 'hero', 'event', 'memorial', 'story') NOT NULL,
  entity_id            BIGINT NOT NULL,
  tag_id               BIGINT NOT NULL,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_entity_tag_rel_tag
    FOREIGN KEY (tag_id) REFERENCES tag_info(tag_id),
  CONSTRAINT uk_entity_tag UNIQUE (entity_type, entity_id, tag_id),
  KEY idx_entity_tag_lookup (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE site_event_rel (
  rel_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  site_id              BIGINT NOT NULL,
  event_id             BIGINT NOT NULL,
  relation_type        ENUM('occurred_at', 'related_to', 'memorialized_at') NOT NULL DEFAULT 'related_to',
  importance_level     TINYINT NOT NULL DEFAULT 3,
  remark               VARCHAR(255) NULL,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_site_event_site
    FOREIGN KEY (site_id) REFERENCES red_site(site_id),
  CONSTRAINT fk_site_event_event
    FOREIGN KEY (event_id) REFERENCES historical_event(event_id),
  CONSTRAINT uk_site_event UNIQUE (site_id, event_id, relation_type),
  KEY idx_site_event_event (event_id),
  KEY idx_site_event_site (site_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE site_hero_rel (
  rel_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  site_id              BIGINT NOT NULL,
  hero_id              BIGINT NOT NULL,
  relation_type        ENUM('born_in', 'fought_in', 'memorialized', 'visited', 'related_to') NOT NULL DEFAULT 'related_to',
  importance_level     TINYINT NOT NULL DEFAULT 3,
  remark               VARCHAR(255) NULL,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_site_hero_site
    FOREIGN KEY (site_id) REFERENCES red_site(site_id),
  CONSTRAINT fk_site_hero_hero
    FOREIGN KEY (hero_id) REFERENCES hero_person(hero_id),
  CONSTRAINT uk_site_hero UNIQUE (site_id, hero_id, relation_type),
  KEY idx_site_hero_hero (hero_id),
  KEY idx_site_hero_site (site_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE event_hero_rel (
  rel_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id             BIGINT NOT NULL,
  hero_id              BIGINT NOT NULL,
  relation_type        ENUM('participant', 'leader', 'witness', 'martyr', 'related_to') NOT NULL DEFAULT 'participant',
  contribution_text    TEXT NULL,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_event_hero_event
    FOREIGN KEY (event_id) REFERENCES historical_event(event_id),
  CONSTRAINT fk_event_hero_hero
    FOREIGN KEY (hero_id) REFERENCES hero_person(hero_id),
  CONSTRAINT uk_event_hero UNIQUE (event_id, hero_id, relation_type),
  KEY idx_event_hero_hero (hero_id),
  KEY idx_event_hero_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memorial_site_rel (
  rel_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  memorial_id          BIGINT NOT NULL,
  site_id              BIGINT NOT NULL,
  relation_type        ENUM('located_at', 'displays', 'related_to') NOT NULL DEFAULT 'related_to',
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_memorial_site_memorial
    FOREIGN KEY (memorial_id) REFERENCES memorial_hall(memorial_id),
  CONSTRAINT fk_memorial_site_site
    FOREIGN KEY (site_id) REFERENCES red_site(site_id),
  CONSTRAINT uk_memorial_site UNIQUE (memorial_id, site_id, relation_type),
  KEY idx_memorial_site_site (site_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memorial_hero_rel (
  rel_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  memorial_id          BIGINT NOT NULL,
  hero_id              BIGINT NOT NULL,
  relation_type        ENUM('commemorates', 'exhibits', 'related_to') NOT NULL DEFAULT 'commemorates',
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_memorial_hero_memorial
    FOREIGN KEY (memorial_id) REFERENCES memorial_hall(memorial_id),
  CONSTRAINT fk_memorial_hero_hero
    FOREIGN KEY (hero_id) REFERENCES hero_person(hero_id),
  CONSTRAINT uk_memorial_hero UNIQUE (memorial_id, hero_id, relation_type),
  KEY idx_memorial_hero_hero (hero_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memorial_event_rel (
  rel_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  memorial_id          BIGINT NOT NULL,
  event_id             BIGINT NOT NULL,
  relation_type        ENUM('commemorates', 'exhibits', 'related_to') NOT NULL DEFAULT 'commemorates',
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_memorial_event_memorial
    FOREIGN KEY (memorial_id) REFERENCES memorial_hall(memorial_id),
  CONSTRAINT fk_memorial_event_event
    FOREIGN KEY (event_id) REFERENCES historical_event(event_id),
  CONSTRAINT uk_memorial_event UNIQUE (memorial_id, event_id, relation_type),
  KEY idx_memorial_event_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE story_entity_rel (
  rel_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  story_id             BIGINT NOT NULL,
  entity_type          ENUM('site', 'hero', 'event', 'memorial') NOT NULL,
  entity_id            BIGINT NOT NULL,
  relation_type        ENUM('about', 'mentions', 'teaches') NOT NULL DEFAULT 'about',
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_story_entity_story
    FOREIGN KEY (story_id) REFERENCES red_story(story_id),
  CONSTRAINT uk_story_entity UNIQUE (story_id, entity_type, entity_id, relation_type),
  KEY idx_story_entity_lookup (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resource_media (
  media_id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type          ENUM('site', 'hero', 'event', 'memorial', 'story') NOT NULL,
  entity_id            BIGINT NOT NULL,
  media_type           ENUM('image', 'video', 'audio', 'document', 'link') NOT NULL DEFAULT 'image',
  media_title          VARCHAR(200) NULL,
  media_url            VARCHAR(500) NOT NULL,
  cover_url            VARCHAR(500) NULL,
  description          VARCHAR(255) NULL,
  source_id            BIGINT NULL,
  copyright_note       VARCHAR(255) NULL,
  sort_order           INT NOT NULL DEFAULT 0,
  is_primary           TINYINT(1) NOT NULL DEFAULT 0,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_media_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  KEY idx_media_entity (entity_type, entity_id),
  KEY idx_media_type (media_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE entity_source_rel (
  rel_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type          ENUM('site', 'hero', 'event', 'memorial', 'story') NOT NULL,
  entity_id            BIGINT NOT NULL,
  source_id            BIGINT NOT NULL,
  source_url           VARCHAR(500) NULL,
  captured_at          DATETIME NULL,
  source_excerpt       TEXT NULL,
  credibility_score    TINYINT NOT NULL DEFAULT 3,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_entity_source_rel_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  CONSTRAINT uk_entity_source UNIQUE (entity_type, entity_id, source_id, source_url(255)),
  KEY idx_entity_source_lookup (entity_type, entity_id),
  KEY idx_entity_source_source (source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_chunk (
  chunk_id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type          ENUM('site', 'hero', 'event', 'memorial', 'story') NOT NULL,
  entity_id            BIGINT NOT NULL,
  chunk_title          VARCHAR(200) NULL,
  chunk_text           LONGTEXT NOT NULL,
  chunk_index          INT NOT NULL DEFAULT 1,
  source_id            BIGINT NULL,
  token_count          INT NULL,
  embedding_status     ENUM('pending', 'done', 'failed') NOT NULL DEFAULT 'pending',
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_chunk_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  CONSTRAINT uk_content_chunk UNIQUE (entity_type, entity_id, chunk_index),
  KEY idx_chunk_entity (entity_type, entity_id),
  FULLTEXT KEY ft_chunk_text (chunk_title, chunk_text)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_log (
  log_id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type          ENUM('region', 'site', 'hero', 'event', 'memorial', 'story', 'tag') NOT NULL,
  entity_id            BIGINT NOT NULL,
  operation_type       ENUM('insert', 'update', 'delete', 'review') NOT NULL,
  operator_name        VARCHAR(100) NULL,
  change_summary       TEXT NULL,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_audit_entity (entity_type, entity_id),
  KEY idx_audit_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- administrative_region: seed + extension + boundary updates
INSERT INTO administrative_region
    (region_id, parent_region_id, region_name, region_level, adcode, center_longitude, center_latitude, intro)
VALUES
    (1,  NULL, '河北省',   'province', '130000',    114.5024610, 38.0454740, '河北省红色文化资源分布广泛，是本项目首期重点覆盖区域。'),
    (2,  1,    '石家庄市', 'city',     '130100',    114.5148600, 38.0423070, '河北省省会，拥有西柏坡等重要红色文化资源。'),
    (3,  2,    '平山县',   'county',   '130131',    114.1860000, 38.2590000, '平山县是西柏坡所在地。'),
    (4,  3,    '西柏坡镇', 'township', '130131100', 113.9776000, 38.3432000, '西柏坡镇拥有重要革命旧址群。'),
    (5,  1,    '保定市',   'city',     '130600',    115.4645890, 38.8744760, '保定市红色文化与抗战历史资源丰富。'),
    (6,  5,    '易县',     'county',   '130633',    115.4970000, 39.3520000, '狼牙山所在地。'),
    (7,  1,    '唐山市',   'city',     '130200',    118.1801930, 39.6308670, '唐山市拥有李大钊等重要红色文化资源。'),
    (8,  1,    '秦皇岛市', 'city',     '130300',    119.5865790, 39.9425310, '秦皇岛市是河北沿海重要城市。'),
    (9,  1,    '邯郸市',   'city',     '130400',    114.4906860, 36.6122730, '邯郸市红色资源与太行革命历史紧密相关。'),
    (10, 1,    '邢台市',   'city',     '130500',    114.5088510, 37.0682000, '邢台市拥有抗战与太行山区革命资源。'),
    (11, 1,    '张家口市', 'city',     '130700',    114.8840910, 40.8119010, '张家口市拥有长城沿线与革命传统资源。'),
    (12, 1,    '承德市',   'city',     '130800',    117.9391520, 40.9762040, '承德市兼具塞罕坝精神与革命纪念资源。'),
    (13, 1,    '沧州市',   'city',     '130900',    116.8574610, 38.3105820, '沧州市沿海与冀中平原红色文化资源较丰富。'),
    (14, 1,    '廊坊市',   'city',     '131000',    116.7044410, 39.5239270, '廊坊市毗邻京津，拥有冀中抗战相关资源。'),
    (15, 1,    '衡水市',   'city',     '131100',    115.6659930, 37.7350970, '衡水市是冀中平原红色文化传播的重要区域。'),
    (16, NULL, '定州市',   'city',     '130682',    114.9902060, 38.5167460, '定州市可作为河北省直管县级市示例数据。'),
    (17, NULL, '辛集市',   'city',     '130181',    115.2174510, 37.9290400, '辛集市可作为河北省直管县级市示例数据。'),
    (18, NULL, '雄安新区', 'city',     '133100',    115.9080000, 39.0430000, '雄安新区作为国家级新区，可预留展示位。'),
    (31, 5,    '阜平县',   'county',   '130624',    114.1951180, 38.8472760, '阜平县是晋察冀革命根据地重要区域。'),
    (32, 31,   '城南庄镇', 'township', NULL,        114.0998000, 38.8640000, '城南庄镇拥有晋察冀边区政府旧址等资源。'),
    (33, 9,    '涉县',     'county',   '130426',    113.6732970, 36.5631430, '涉县拥有一二九师司令部旧址等重要资源。'),
    (34, 33,   '河南店镇', 'township', NULL,        113.7020000, 36.5790000, '河南店镇可作为涉县红色文化展示乡镇。'),
    (35, 11,   '怀来县',   'county',   '130730',    115.5178680, 40.4156250, '怀来县与董存瑞等革命人物叙事相关。'),
    (36, 6,    '狼牙山镇', 'township', NULL,        115.4448000, 39.4054000, '狼牙山镇可作为狼牙山五壮士相关资源承载乡镇。'),
    (37, 7,    '乐亭县',   'county',   '130225',    118.9053410, 39.4257480, '乐亭县拥有李大钊故居等重要纪念资源。'),
    (38, 37,   '大黑坨镇', 'township', NULL,        118.9450000, 39.4320000, '大黑坨镇可作为李大钊相关乡镇展示点。'),
    (39, 12,   '隆化县',   'county',   '130825',    117.7363430, 41.3138120, '隆化县与解放战争时期革命纪念资源相关。')
ON DUPLICATE KEY UPDATE
    parent_region_id = VALUES(parent_region_id),
    region_name = VALUES(region_name),
    region_level = VALUES(region_level),
    adcode = VALUES(adcode),
    center_longitude = VALUES(center_longitude),
    center_latitude = VALUES(center_latitude),
    intro = VALUES(intro);

UPDATE administrative_region
SET boundary_geojson = '{
  "type": "Polygon",
  "coordinates": [[[114.0,38.0],[114.2,38.1],[114.1,38.3],[114.0,38.0]]]
}'
WHERE region_id = 1;

UPDATE administrative_region
SET boundary_geojson = '{
  "type": "Polygon",
  "coordinates": [[[114.3,37.9],[114.7,38.0],[114.6,38.3],[114.3,37.9]]]
}'
WHERE region_id = 2;

UPDATE administrative_region
SET boundary_geojson = '{
  "type": "Polygon",
  "coordinates": [[[113.9,38.1],[114.3,38.1],[114.2,38.4],[113.9,38.1]]]
}'
WHERE region_id = 3;

UPDATE administrative_region
SET boundary_geojson = '{
  "type": "Polygon",
  "coordinates": [[[113.96,38.33],[113.99,38.33],[113.99,38.35],[113.96,38.35],[113.96,38.33]]]
}'
WHERE region_id = 4;

UPDATE administrative_region
SET boundary_geojson = '{
  "type": "Polygon",
  "coordinates": [[[115.2,38.7],[115.8,38.8],[115.7,39.1],[115.2,38.7]]]
}'
WHERE region_id = 5;

UPDATE administrative_region
SET boundary_geojson = '{
  "type": "Polygon",
  "coordinates": [[[115.3,39.2],[115.6,39.2],[115.6,39.5],[115.3,39.5],[115.3,39.2]]]
}'
WHERE region_id = 6;

INSERT INTO data_source
    (source_id, source_name, source_type, organization_name, base_url, reliability_level, license_note, crawl_allowed, remark)
VALUES
    (1, '河北省文化和旅游厅官网', 'government', '河北省文化和旅游厅', 'https://whly.hebei.gov.cn', 5, '以官网公开信息为准', 1, '建议优先作为红色资源基础来源。'),
    (2, '人民网红色文化频道', 'news', '人民网', 'https://www.people.com.cn', 4, '新闻转载需注意版权', 1, '适合补充事件报道和纪念活动信息。'),
    (3, '纪念馆官网资料', 'museum', '西柏坡纪念馆', 'http://www.xbpjng.com', 5, '以场馆公开资料为准', 1, '适合补充展陈和开放时间。');

INSERT INTO tag_info
    (tag_id, tag_name, tag_type, description)
VALUES
    (1, '解放战争', 'period', '与解放战争时期相关的红色文化资源'),
    (2, '革命旧址', 'theme', '革命旧址与旧居类资源'),
    (3, '爱国主义教育', 'education', '适用于爱国主义教育场景'),
    (4, '就近研学', 'route', '适用于乡村学生就近研学路线');

INSERT INTO red_site
    (site_id, site_code, site_name, site_alias, region_id, address, longitude, latitude, established_year, site_level,
     protection_level, historical_background, intro, opening_time_desc, suggested_visit_minutes, official_url, review_status, is_active)
VALUES
    (1, 'SITE_HEB_XBP_001', '西柏坡中共中央旧址', '西柏坡旧址', 4, '河北省石家庄市平山县西柏坡镇', 113.9783000, 38.3439000, 1948, 'national',
     '全国重点文物保护单位', '1948年至1949年间，中共中央在西柏坡指挥了决定中国命运的三大战役。', '西柏坡中共中央旧址是河北红色文化的重要代表。', '08:30-17:00', 120,
     'http://www.xbpjng.com', 'approved', 1),
    (2, 'SITE_HEB_LYS_001', '狼牙山五壮士纪念地', '狼牙山纪念地', 6, '河北省保定市易县狼牙山景区', 115.4448000, 39.4054000, 1941, 'provincial',
     '省级重点保护资源', '狼牙山五壮士英勇抗敌的事迹在全国广为流传。', '狼牙山纪念地适合爱国主义教育与研学。', '08:00-17:30', 180,
     NULL, 'approved', 1);

INSERT INTO hero_person
    (hero_id, hero_code, hero_name, hero_alias, gender, birth_year, death_year, native_place_region_id, native_place_text,
     profile_summary, main_deeds, official_url, review_status, is_active)
VALUES
    (1, 'HERO_HEB_DXP_001', '董存瑞', NULL, 'male', 1929, 1948, 1, '河北省张家口市怀来县',
     '著名战斗英雄，全国知名革命烈士。', '在解放隆化战斗中舍身炸碉堡，展现了英勇无畏的革命精神。', NULL, 'approved', 1),
    (2, 'HERO_HEB_MBL_001', '毛岸英', NULL, 'male', 1922, 1950, NULL, '湖南省湘潭县',
     '革命烈士，曾在党中央工作体系中参与重要事务。', '其革命精神和家国情怀被广泛纪念。', NULL, 'approved', 1);

INSERT INTO historical_event
    (event_id, event_code, event_name, event_alias, primary_region_id, event_time_text, start_date, end_date, start_year, end_year,
     longitude, latitude, historical_significance, event_process, result_impact, official_url, review_status, is_active)
VALUES
    (1, 'EVENT_HEB_SDZY_001', '三大战役指挥决策', '西柏坡时期三大战役指挥', 4, '1948年9月至1949年1月', '1948-09-12', '1949-01-31', 1948, 1949,
     113.9783000, 38.3439000, '三大战役的胜利奠定了解放战争全国胜利的基础。', '中共中央在西柏坡先后指挥辽沈、淮海、平津三大战役。', '成为中国革命走向全国胜利的重要转折。', NULL, 'approved', 1),
    (2, 'EVENT_HEB_WYSZS_001', '狼牙山五壮士抗敌战斗', '狼牙山五壮士战斗', 6, '1941年9月', '1941-09-01', '1941-09-30', 1941, 1941,
     115.4448000, 39.4054000, '展现了中国军民英勇抗战的精神风貌。', '抗日战争时期，五位八路军战士为掩护群众和主力转移，英勇阻击日伪军。', '成为中国抗战精神的重要象征。', NULL, 'approved', 1);

INSERT INTO memorial_hall
    (memorial_id, memorial_code, memorial_name, region_id, address, longitude, latitude, exhibition_content, intro,
     opening_time_desc, ticket_info, contact_phone, official_url, review_status, is_active)
VALUES
    (1, 'MEM_HEB_XBP_001', '西柏坡纪念馆', 4, '河北省石家庄市平山县西柏坡镇', 113.9791000, 38.3445000,
     '展陈内容包括中共中央在西柏坡时期的重要历史文献、图片与实物。', '西柏坡纪念馆是开展红色文化教育的重要场馆。', '09:00-17:00', '免费开放，具体以馆方公告为准', NULL,
     'http://www.xbpjng.com', 'approved', 1);

INSERT INTO red_story
    (story_id, story_code, story_title, related_region_id, age_group, summary, story_content, source_id, review_status, is_active)
VALUES
    (1, 'STORY_HEB_XBP_001', '西柏坡的“两个务必”精神', 4, 'college',
     '围绕西柏坡时期形成的重要思想展开，适合大学生红色文化学习。',
     '西柏坡时期，中国共产党面临从革命党向执政党的历史转变，形成了“两个务必”等重要精神财富。这一精神对于今天的青年学习党史、理解初心使命具有重要教育意义。',
     3, 'approved', 1),
    (2, 'STORY_HEB_LYS_001', '狼牙山五壮士的英雄抉择', 6, 'middle',
     '讲述五壮士英勇抗敌、舍生取义的故事。',
     '狼牙山五壮士在危急时刻为掩护群众和部队主力转移，毅然把敌人引上绝路。他们体现出的忠诚、勇敢和担当，是中学生红色教育中的经典案例。',
     2, 'approved', 1);

INSERT INTO site_event_rel
    (rel_id, site_id, event_id, relation_type, importance_level, remark)
VALUES
    (1, 1, 1, 'occurred_at', 5, '三大战役指挥决策的重要发生地'),
    (2, 2, 2, 'occurred_at', 5, '狼牙山五壮士战斗发生地');

INSERT INTO site_hero_rel
    (rel_id, site_id, hero_id, relation_type, importance_level, remark)
VALUES
    (1, 1, 2, 'related_to', 3, '可扩展纪念和展陈关系'),
    (2, 2, 1, 'memorialized', 4, '用于示例展示英雄与遗址关联');

INSERT INTO event_hero_rel
    (rel_id, event_id, hero_id, relation_type, contribution_text)
VALUES
    (1, 2, 1, 'martyr', '作为革命英雄精神象征，用于示例关系展示。'),
    (2, 1, 2, 'related_to', '用于展示事件与人物的扩展关联。');

INSERT INTO memorial_site_rel
    (rel_id, memorial_id, site_id, relation_type)
VALUES
    (1, 1, 1, 'located_at');

INSERT INTO memorial_event_rel
    (rel_id, memorial_id, event_id, relation_type)
VALUES
    (1, 1, 1, 'commemorates');

INSERT INTO memorial_hero_rel
    (rel_id, memorial_id, hero_id, relation_type)
VALUES
    (1, 1, 2, 'exhibits');

INSERT INTO entity_tag_rel
    (rel_id, entity_type, entity_id, tag_id)
VALUES
    (1, 'site', 1, 2),
    (2, 'site', 1, 3),
    (3, 'event', 1, 1),
    (4, 'story', 1, 3),
    (5, 'site', 2, 4);

INSERT INTO story_entity_rel
    (rel_id, story_id, entity_type, entity_id, relation_type)
VALUES
    (1, 1, 'site', 1, 'about'),
    (2, 1, 'event', 1, 'teaches'),
    (3, 2, 'site', 2, 'about'),
    (4, 2, 'event', 2, 'teaches');

INSERT INTO entity_source_rel
    (rel_id, entity_type, entity_id, source_id, source_url, captured_at, source_excerpt, credibility_score)
VALUES
    (1, 'site', 1, 3, 'http://www.xbpjng.com', NOW(), '西柏坡旧址与纪念馆公开资料。', 5),
    (2, 'story', 1, 3, 'http://www.xbpjng.com', NOW(), '西柏坡精神教育资料。', 5),
    (3, 'story', 2, 2, 'https://www.people.com.cn', NOW(), '英雄事迹宣传报道。', 4);

INSERT INTO resource_media
    (media_id, entity_type, entity_id, media_type, media_title, media_url, description, source_id, sort_order, is_primary)
VALUES
    (1, 'site', 1, 'image', '西柏坡中共中央旧址示意图', 'https://example.com/media/xibaipo-site.jpg', '用于前端地图卡片展示', 3, 1, 1),
    (2, 'memorial', 1, 'image', '西柏坡纪念馆外观图', 'https://example.com/media/xibaipo-memorial.jpg', '用于纪念馆详情页展示', 3, 1, 1);

INSERT INTO content_chunk
    (chunk_id, entity_type, entity_id, chunk_title, chunk_text, chunk_index, source_id, token_count, embedding_status)
VALUES
    (1, 'story', 1, '西柏坡精神概述', '西柏坡时期形成的精神财富包括谦虚谨慎、艰苦奋斗以及面向执政考验的清醒认识。', 1, 3, 48, 'pending'),
    (2, 'event', 1, '三大战役指挥背景', '中共中央在西柏坡运筹帷幄，指挥三大战役，为全国胜利奠定基础。', 1, 3, 36, 'pending');

-- ============================================================
-- School module schema
-- Source: mysql_school_module_schema.sql
-- ============================================================
USE red_culture_platform;

SET NAMES utf8mb4;

-- 1. Extend shared polymorphic tables so school/resource/activity data
-- can reuse tags, media, sources, content chunks, and audit logs.

ALTER TABLE entity_tag_rel
MODIFY COLUMN entity_type ENUM('site', 'hero', 'event', 'memorial', 'story', 'school', 'resource', 'activity_plan') NOT NULL;

ALTER TABLE resource_media
MODIFY COLUMN entity_type ENUM('site', 'hero', 'event', 'memorial', 'story', 'school', 'resource', 'activity_plan') NOT NULL;

ALTER TABLE entity_source_rel
MODIFY COLUMN entity_type ENUM('site', 'hero', 'event', 'memorial', 'story', 'school', 'resource', 'activity_plan') NOT NULL;

ALTER TABLE content_chunk
MODIFY COLUMN entity_type ENUM('site', 'hero', 'event', 'memorial', 'story', 'school', 'resource', 'activity_plan') NOT NULL;

ALTER TABLE audit_log
MODIFY COLUMN entity_type ENUM('region', 'site', 'hero', 'event', 'memorial', 'story', 'tag', 'school', 'resource', 'activity_plan') NOT NULL;

ALTER TABLE story_entity_rel
MODIFY COLUMN entity_type ENUM('site', 'hero', 'event', 'memorial', 'school', 'resource') NOT NULL;

-- 2. School main table

CREATE TABLE IF NOT EXISTS school (
  school_id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_code            VARCHAR(50) NOT NULL,
  school_name            VARCHAR(200) NOT NULL,
  school_alias           VARCHAR(200) NULL,
  region_id              BIGINT NULL,
  county_region_id       BIGINT NULL,
  township_region_id     BIGINT NULL,
  village_region_id      BIGINT NULL,
  school_level           ENUM('kindergarten', 'primary', 'junior', 'senior', 'nine_year', 'twelve_year', 'vocational', 'special', 'other') NOT NULL DEFAULT 'primary',
  school_type            VARCHAR(100) NULL,
  school_nature          ENUM('public', 'private', 'other') NOT NULL DEFAULT 'public',
  is_rural_school        TINYINT(1) NOT NULL DEFAULT 1,
  is_teaching_point      TINYINT(1) NOT NULL DEFAULT 0,
  address                VARCHAR(300) NULL,
  postcode               VARCHAR(20) NULL,
  contact_phone          VARCHAR(50) NULL,
  principal_name         VARCHAR(100) NULL,
  longitude              DECIMAL(10,7) NULL,
  latitude               DECIMAL(10,7) NULL,
  geo_source_type        ENUM('amap_poi', 'manual', 'school_official', 'government_doc', 'satellite_fix', 'other') NOT NULL DEFAULT 'government_doc',
  poi_name               VARCHAR(200) NULL,
  poi_address            VARCHAR(300) NULL,
  poi_type               VARCHAR(200) NULL,
  geo_confidence         ENUM('high', 'medium', 'low', 'unknown') NOT NULL DEFAULT 'unknown',
  geo_verified           TINYINT(1) NOT NULL DEFAULT 0,
  intro                  TEXT NULL,
  source_id              BIGINT NULL,
  review_status          ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_school_code UNIQUE (school_code),
  CONSTRAINT fk_school_region
    FOREIGN KEY (region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_county_region
    FOREIGN KEY (county_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_township_region
    FOREIGN KEY (township_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_village_region
    FOREIGN KEY (village_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  KEY idx_school_name (school_name),
  KEY idx_school_region (region_id),
  KEY idx_school_county (county_region_id),
  KEY idx_school_township (township_region_id),
  KEY idx_school_status (review_status, is_active),
  KEY idx_school_geo (longitude, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. School coordinate audit/history table

CREATE TABLE IF NOT EXISTS school_geo_record (
  geo_record_id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_id              BIGINT NOT NULL,
  longitude              DECIMAL(10,7) NOT NULL,
  latitude               DECIMAL(10,7) NOT NULL,
  source_type            ENUM('amap_poi', 'manual', 'school_official', 'government_doc', 'satellite_fix', 'other') NOT NULL DEFAULT 'amap_poi',
  poi_name               VARCHAR(200) NULL,
  poi_address            VARCHAR(300) NULL,
  poi_type               VARCHAR(200) NULL,
  confidence_level       ENUM('high', 'medium', 'low', 'unknown') NOT NULL DEFAULT 'unknown',
  is_manual_reviewed     TINYINT(1) NOT NULL DEFAULT 0,
  review_result          ENUM('pending', 'confirmed', 'corrected', 'rejected') NOT NULL DEFAULT 'pending',
  reviewer_name          VARCHAR(100) NULL,
  reviewed_at            DATETIME NULL,
  is_current             TINYINT(1) NOT NULL DEFAULT 1,
  remark                 VARCHAR(255) NULL,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_school_geo_record_school
    FOREIGN KEY (school_id) REFERENCES school(school_id),
  KEY idx_school_geo_record_school (school_id),
  KEY idx_school_geo_record_current (school_id, is_current)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. Generic local educational resource table

CREATE TABLE IF NOT EXISTS local_edu_resource (
  resource_id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  resource_code          VARCHAR(50) NOT NULL,
  resource_name          VARCHAR(200) NOT NULL,
  resource_alias         VARCHAR(200) NULL,
  resource_category      ENUM('red_culture', 'intangible_culture', 'traditional_culture', 'local_history', 'public_culture', 'labor_education', 'public_welfare', 'ecological_civilization', 'patriotism_base', 'social_practice', 'other') NOT NULL DEFAULT 'other',
  resource_subcategory   VARCHAR(100) NULL,
  region_id              BIGINT NULL,
  county_region_id       BIGINT NULL,
  township_region_id     BIGINT NULL,
  address                VARCHAR(300) NULL,
  longitude              DECIMAL(10,7) NULL,
  latitude               DECIMAL(10,7) NULL,
  organization_name      VARCHAR(200) NULL,
  contact_phone          VARCHAR(50) NULL,
  opening_time_desc      VARCHAR(255) NULL,
  reservation_required   TINYINT(1) NOT NULL DEFAULT 0,
  recommended_visit_minutes INT NULL,
  intro                  TEXT NULL,
  education_value        TEXT NULL,
  activity_suggestion    LONGTEXT NULL,
  target_grade           VARCHAR(100) NULL,
  safety_note            TEXT NULL,
  source_id              BIGINT NULL,
  review_status          ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_local_edu_resource_code UNIQUE (resource_code),
  CONSTRAINT fk_local_edu_resource_region
    FOREIGN KEY (region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_local_edu_resource_county_region
    FOREIGN KEY (county_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_local_edu_resource_township_region
    FOREIGN KEY (township_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_local_edu_resource_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  KEY idx_local_edu_resource_name (resource_name),
  KEY idx_local_edu_resource_category (resource_category, resource_subcategory),
  KEY idx_local_edu_resource_region (region_id),
  KEY idx_local_edu_resource_status (review_status, is_active),
  KEY idx_local_edu_resource_geo (longitude, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. School-to-resource relation table

CREATE TABLE IF NOT EXISTS school_resource_rel (
  rel_id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_id              BIGINT NOT NULL,
  resource_id            BIGINT NOT NULL,
  relation_type          ENUM('nearby', 'cooperation', 'practice', 'curriculum_support', 'volunteer_base', 'research_route', 'other') NOT NULL DEFAULT 'nearby',
  distance_meters        INT NULL,
  recommended_travel_mode ENUM('walk', 'bike', 'bus', 'drive', 'mixed', 'unknown') NOT NULL DEFAULT 'unknown',
  estimated_duration_minutes INT NULL,
  reachability_level     ENUM('near', 'medium', 'far', 'very_far', 'unknown') NOT NULL DEFAULT 'unknown',
  priority_level         TINYINT NOT NULL DEFAULT 3,
  education_theme_summary VARCHAR(255) NULL,
  source_id              BIGINT NULL,
  review_status          ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_school_resource_rel_school
    FOREIGN KEY (school_id) REFERENCES school(school_id),
  CONSTRAINT fk_school_resource_rel_resource
    FOREIGN KEY (resource_id) REFERENCES local_edu_resource(resource_id),
  CONSTRAINT fk_school_resource_rel_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  CONSTRAINT uk_school_resource_rel UNIQUE (school_id, resource_id, relation_type),
  KEY idx_school_resource_rel_school (school_id),
  KEY idx_school_resource_rel_resource (resource_id),
  KEY idx_school_resource_rel_distance (distance_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. Teaching activity plan table

CREATE TABLE IF NOT EXISTS teaching_activity_plan (
  plan_id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_code              VARCHAR(50) NOT NULL,
  school_id              BIGINT NOT NULL,
  resource_id            BIGINT NULL,
  theme                  VARCHAR(200) NOT NULL,
  activity_type          ENUM('classroom', 'field_trip', 'volunteer_service', 'research_study', 'labor_practice', 'club_activity', 'school_based_course', 'other') NOT NULL DEFAULT 'classroom',
  suitable_grade         VARCHAR(100) NULL,
  objective_text         TEXT NULL,
  activity_content       LONGTEXT NOT NULL,
  preparation_text       TEXT NULL,
  safety_text            TEXT NULL,
  expected_outcome       TEXT NULL,
  duration_minutes       INT NULL,
  source_id              BIGINT NULL,
  review_status          ENUM('draft', 'pending', 'approved', 'rejected') NOT NULL DEFAULT 'draft',
  is_active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_teaching_activity_plan_code UNIQUE (plan_code),
  CONSTRAINT fk_teaching_activity_plan_school
    FOREIGN KEY (school_id) REFERENCES school(school_id),
  CONSTRAINT fk_teaching_activity_plan_resource
    FOREIGN KEY (resource_id) REFERENCES local_edu_resource(resource_id),
  CONSTRAINT fk_teaching_activity_plan_source
    FOREIGN KEY (source_id) REFERENCES data_source(source_id),
  KEY idx_teaching_activity_plan_school (school_id),
  KEY idx_teaching_activity_plan_resource (resource_id),
  KEY idx_teaching_activity_plan_theme (theme),
  KEY idx_teaching_activity_plan_status (review_status, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. Optional sample seed records for pilot schools
-- Keep this section commented until the design is confirmed.
--
-- INSERT INTO school
--   (school_code, school_name, county_region_id, township_region_id, school_level, school_type, school_nature,
--    is_rural_school, is_teaching_point, address, longitude, latitude, geo_source_type, poi_name, poi_type,
--    geo_confidence, geo_verified, review_status, is_active)
-- VALUES
--   ('SCH_SJZ_GC_0001', '石家庄市藁城区常安镇里庄小学', NULL, NULL, 'primary', '村小', 'public',
--    1, 0, '河北省石家庄市藁城区常安镇里庄村振兴大街西220号', 114.9537180, 38.0271030, 'amap_poi',
--    '石家庄市藁城区常安镇里庄小学', '科教文化服务;学校;小学', 'high', 0, 'draft', 1);

-- ============================================================
-- School auth module schema
-- Source: mysql_school_auth_module_schema.sql
-- ============================================================
USE red_culture_platform;

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS school_registration (
  registration_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  apply_account          VARCHAR(100) NOT NULL,
  password_hash          VARCHAR(255) NOT NULL,
  contact_name           VARCHAR(100) NULL,
  contact_phone          VARCHAR(50) NULL,
  contact_email          VARCHAR(120) NULL,
  school_name            VARCHAR(200) NOT NULL,
  school_alias           VARCHAR(200) NULL,
  school_level           ENUM('kindergarten', 'primary', 'junior', 'senior', 'nine_year', 'twelve_year', 'vocational', 'special', 'other') NOT NULL DEFAULT 'primary',
  school_type            VARCHAR(100) NULL,
  school_nature          ENUM('public', 'private', 'other') NOT NULL DEFAULT 'public',
  county_region_id       BIGINT NULL,
  township_region_id     BIGINT NULL,
  address                VARCHAR(300) NULL,
  longitude              DECIMAL(10,7) NULL,
  latitude               DECIMAL(10,7) NULL,
  geo_source_type        ENUM('amap_poi', 'manual', 'school_official', 'government_doc', 'satellite_fix', 'other') NOT NULL DEFAULT 'manual',
  geo_confidence         ENUM('high', 'medium', 'low', 'unknown') NOT NULL DEFAULT 'unknown',
  intro                  TEXT NULL,
  review_status          ENUM('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending',
  review_remark          VARCHAR(255) NULL,
  reviewed_by            VARCHAR(100) NULL,
  reviewed_at            DATETIME NULL,
  linked_school_id       BIGINT NULL,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_school_registration_account UNIQUE (apply_account),
  CONSTRAINT fk_school_registration_county_region
    FOREIGN KEY (county_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_registration_township_region
    FOREIGN KEY (township_region_id) REFERENCES administrative_region(region_id),
  CONSTRAINT fk_school_registration_linked_school
    FOREIGN KEY (linked_school_id) REFERENCES school(school_id),
  KEY idx_school_registration_status (review_status),
  KEY idx_school_registration_school_name (school_name),
  KEY idx_school_registration_geo (longitude, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS school_user_account (
  account_id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  username               VARCHAR(100) NOT NULL,
  password_hash          VARCHAR(255) NOT NULL,
  role_code              VARCHAR(50) NOT NULL DEFAULT 'school_admin',
  school_id              BIGINT NULL,
  registration_id        BIGINT NULL,
  display_name           VARCHAR(120) NULL,
  contact_name           VARCHAR(100) NULL,
  contact_phone          VARCHAR(50) NULL,
  status                 ENUM('pending_activation', 'active', 'disabled') NOT NULL DEFAULT 'active',
  last_login_at          DATETIME NULL,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_school_user_account_username UNIQUE (username),
  CONSTRAINT uk_school_user_account_school UNIQUE (school_id),
  CONSTRAINT fk_school_user_account_school
    FOREIGN KEY (school_id) REFERENCES school(school_id),
  CONSTRAINT fk_school_user_account_registration
    FOREIGN KEY (registration_id) REFERENCES school_registration(registration_id),
  KEY idx_school_user_account_status (status),
  KEY idx_school_user_account_role (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- School module sample data
-- Source: mysql_school_module_sample_data.sql
-- ============================================================
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

-- Extra pilot resources and RAG content for SCH_SJZ_GC_0001.
INSERT INTO local_edu_resource
  (resource_code, resource_name, resource_alias, resource_category, resource_subcategory, region_id, county_region_id,
   township_region_id, address, longitude, latitude, organization_name, opening_time_desc, reservation_required,
   recommended_visit_minutes, intro, education_value, activity_suggestion, target_grade, safety_note, source_id,
   review_status, is_active)
SELECT x.resource_code, x.resource_name, NULL, x.resource_category, x.resource_subcategory, NULL, NULL, NULL,
       x.address, x.longitude, x.latitude, x.organization_name, x.opening_time_desc, x.reservation_required,
       x.recommended_visit_minutes, x.intro, x.education_value, x.activity_suggestion, x.target_grade, x.safety_note,
       NULL, 'approved', 1
FROM (
    SELECT 'RES_SJZ_GC_0003' AS resource_code, '常安镇新时代文明实践站' AS resource_name, 'social_practice' AS resource_category, '文明实践' AS resource_subcategory,
           '河北省石家庄市藁城区常安镇示例地址' AS address, 114.9528000 AS longitude, 38.0287000 AS latitude, '常安镇新时代文明实践站' AS organization_name,
           '工作日开放' AS opening_time_desc, 1 AS reservation_required, 60 AS recommended_visit_minutes,
           '可组织文明礼仪、志愿服务和社区治理主题实践。' AS intro, '适合开展公共责任、文明行为和基层治理教育。' AS education_value,
           '设计文明劝导、社区观察、志愿服务记录等活动。' AS activity_suggestion, '小学高年级/初中' AS target_grade, '需提前联系实践站并分组行动。' AS safety_note
    UNION ALL SELECT 'RES_SJZ_GC_0004', '里庄村史馆', 'local_history', '村史教育', '河北省石家庄市藁城区里庄村示例地址', 114.9560000, 38.0259000, '里庄村村委会', '预约开放', 1, 45, '展示村庄发展、乡土记忆和基层建设变化。', '适合开展乡土认同、家乡变化和劳动创造教育。', '组织学生绘制家乡变化时间线，采访长辈讲述村史。', '小学/初中', '馆内参观保持安静，注意展陈保护。'
    UNION ALL SELECT 'RES_SJZ_GC_0005', '常安镇农耕体验园', 'labor_education', '农耕劳动', '河北省石家庄市藁城区常安镇示例农园', 114.9612000, 38.0303000, '常安镇农耕体验园', '季节性开放', 1, 90, '提供农作物观察、简单农事体验和劳动教育场景。', '适合开展劳动观念、粮食安全和生态文明教育。', '设计观察作物、记录农事流程、完成劳动体验反思。', '小学/初中', '户外活动注意防晒、饮水和工具使用安全。'
    UNION ALL SELECT 'RES_SJZ_GC_0006', '常安镇卫生院健康教育角', 'social_practice', '健康教育', '河北省石家庄市藁城区常安镇示例地址', 114.9489000, 38.0279000, '常安镇卫生院', '工作日开放', 1, 40, '可开展公共卫生、生命健康和社会服务主题教育。', '适合开展生命安全、公共卫生责任和服务意识教育。', '组织健康知识宣传海报制作、公共卫生访谈和服务岗位观察。', '小学高年级/初中', '进入医疗场所需遵守秩序并避免影响诊疗。'
    UNION ALL SELECT 'RES_SJZ_GC_0007', '里庄村红色记忆讲述点', 'red_culture', '红色故事', '河北省石家庄市藁城区里庄村示例地址', 114.9543000, 38.0269000, '里庄村党群服务中心', '预约开放', 1, 45, '依托村内老党员和地方故事开展红色记忆讲述。', '适合开展理想信念、榜样学习和口述历史教育。', '组织红色故事采访、讲述稿整理和班级分享。', '小学高年级/初中', '访谈活动需尊重讲述者并提前征得同意。'
    UNION ALL SELECT 'RES_SJZ_GC_0008', '常安镇生态小公园', 'ecological_civilization', '生态观察', '河北省石家庄市藁城区常安镇示例公园', 114.9516000, 38.0312000, '常安镇综合管理服务中心', '全天开放', 0, 50, '可用于生态文明、公共空间和环境保护主题观察。', '适合开展绿色发展、公共意识和环境责任教育。', '开展垃圾分类观察、植物记录和公共空间文明倡议。', '小学/初中', '户外活动注意交通、集合和天气变化。'
    UNION ALL SELECT 'RES_SJZ_GC_0009', '常安镇综合文化服务中心', 'public_culture', '公共文化', '河北省石家庄市藁城区常安镇示例地址', 114.9507000, 38.0284000, '常安镇综合文化服务中心', '工作日开放', 1, 60, '提供乡村阅读、文化展示和群众活动空间。', '适合开展公共文化服务、阅读推广和文化自信教育。', '组织阅读分享、公共文化服务观察和活动策划。', '小学/初中', '集体活动需遵守场馆管理要求。'
    UNION ALL SELECT 'RES_SJZ_GC_0010', '里庄小学劳动实践角', 'labor_education', '校园劳动', '河北省石家庄市藁城区常安镇里庄小学', 114.9539000, 38.0270000, '石家庄市藁城区常安镇里庄小学', '校内开放', 0, 35, '校内劳动实践空间，可连接校园责任岗和班级劳动课程。', '适合开展劳动习惯、责任分工和集体服务教育。', '设计校园清洁、种植观察、责任岗轮值和劳动日志。', '小学', '校内活动注意工具使用和教师看护。'
) x
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
  review_status = VALUES(review_status),
  is_active = VALUES(is_active);

INSERT INTO school_resource_rel
  (school_id, resource_id, relation_type, distance_meters, recommended_travel_mode, estimated_duration_minutes,
   reachability_level, priority_level, education_theme_summary, source_id, review_status)
SELECT s.school_id, r.resource_id, 'nearby', x.distance_meters, 'walk', x.duration_minutes,
       x.reachability_level, x.priority_level, x.theme, NULL, 'approved'
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
ON DUPLICATE KEY UPDATE
  distance_meters = VALUES(distance_meters),
  recommended_travel_mode = VALUES(recommended_travel_mode),
  estimated_duration_minutes = VALUES(estimated_duration_minutes),
  reachability_level = VALUES(reachability_level),
  priority_level = VALUES(priority_level),
  education_theme_summary = VALUES(education_theme_summary),
  review_status = VALUES(review_status);

INSERT INTO content_chunk
  (entity_type, entity_id, chunk_title, chunk_text, chunk_index, source_id, token_count, embedding_status)
SELECT 'resource', r.resource_id, CONCAT(r.resource_name, '教学资源说明'),
       CONCAT(r.intro, ' ', r.education_value, ' ', r.activity_suggestion, ' 安全提示：', r.safety_note),
       1, NULL, 120, 'pending'
FROM local_edu_resource r
WHERE r.resource_code LIKE 'RES_SJZ_GC_%'
ON DUPLICATE KEY UPDATE
  chunk_title = VALUES(chunk_title),
  chunk_text = VALUES(chunk_text),
  token_count = VALUES(token_count),
  embedding_status = VALUES(embedding_status);

SET FOREIGN_KEY_CHECKS = 1;

-- Optional validation
-- SELECT region_id, region_name, JSON_VALID(boundary_geojson) AS json_valid
-- FROM administrative_region
-- ORDER BY region_id;
