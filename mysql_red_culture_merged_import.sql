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

SET FOREIGN_KEY_CHECKS = 1;

-- Optional validation
-- SELECT region_id, region_name, JSON_VALID(boundary_geojson) AS json_valid
-- FROM administrative_region
-- ORDER BY region_id;
