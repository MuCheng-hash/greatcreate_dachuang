"""
MySQL -> Neo4j red culture graph sync prototype

Usage:
1. Install dependencies:
   pip install pymysql neo4j
2. Update MYSQL_CONFIG and NEO4J_CONFIG below
3. Run:
   python sync_mysql_to_neo4j.py
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Any

import pymysql
from neo4j import GraphDatabase


MYSQL_CONFIG = {
    "host": "127.0.0.1",
    "port": 3306,
    "user": "root",
    "password": "root",
    "database": "red_culture_platform",
    "charset": "utf8mb4",
    "cursorclass": pymysql.cursors.DictCursor,
}

NEO4J_CONFIG = {
    "uri": "bolt://127.0.0.1:7687",
    "user": "neo4j",
    "password": "12345678",
}


NODE_SQL = {
    "regions": """
        SELECT region_id, parent_region_id, region_name, region_level, adcode,
               center_longitude, center_latitude, intro
        FROM administrative_region
    """,
    "sites": """
        SELECT site_id, site_code, site_name, site_alias, region_id, address,
               longitude, latitude, established_year, site_level, protection_level,
               historical_background, intro, opening_time_desc, is_active,
               review_status
        FROM red_site
    """,
    "heroes": """
        SELECT hero_id, hero_code, hero_name, hero_alias, gender, birth_year, death_year,
               native_place_region_id, native_place_text, profile_summary, main_deeds, is_active,
               review_status
        FROM hero_person
    """,
    "events": """
        SELECT event_id, event_code, event_name, event_alias, primary_region_id, event_time_text,
               start_date, end_date, start_year, end_year, longitude, latitude,
               historical_significance, event_process, result_impact, is_active,
               review_status
        FROM historical_event
    """,
    "memorials": """
        SELECT memorial_id, memorial_code, memorial_name, region_id, address,
               longitude, latitude, exhibition_content, intro, opening_time_desc,
               ticket_info, is_active,
               review_status
        FROM memorial_hall
    """,
    "stories": """
        SELECT story_id, story_code, story_title, related_region_id, age_group,
               summary, story_content, is_active,
               review_status
        FROM red_story
    """,
    "tags": """
        SELECT tag_id, tag_name, tag_type, description
        FROM tag_info
    """,
    "schools": """
        SELECT school_id, school_code, school_name, school_alias, region_id,
               county_region_id, township_region_id, village_region_id,
               school_level, school_type, school_nature, is_rural_school,
               is_teaching_point, address, longitude, latitude, geo_confidence,
               geo_verified, intro, is_active,
               review_status
        FROM school
    """,
    "local_resources": """
        SELECT resource_id, resource_code, resource_name, resource_alias,
               resource_category, resource_subcategory, region_id,
               county_region_id, township_region_id, address, longitude, latitude,
               organization_name, opening_time_desc, intro, education_value,
               activity_suggestion, target_grade, safety_note, is_active,
               review_status
        FROM local_edu_resource
    """,
    "activity_plans": """
        SELECT plan_id, plan_code, school_id, resource_id, theme, activity_type,
               suitable_grade, objective_text, activity_content, preparation_text,
               safety_text, expected_outcome, duration_minutes, is_active,
               review_status
        FROM teaching_activity_plan
    """,
}


RELATION_SQL = {
    "region_parent": """
        SELECT region_id, parent_region_id
        FROM administrative_region
        WHERE parent_region_id IS NOT NULL
    """,
    "site_region": """
        SELECT site_id, region_id
        FROM red_site
        WHERE review_status = 'approved' AND is_active = 1 AND region_id IS NOT NULL
    """,
    "hero_region": """
        SELECT hero_id, native_place_region_id
        FROM hero_person
        WHERE review_status = 'approved' AND is_active = 1 AND native_place_region_id IS NOT NULL
    """,
    "event_region": """
        SELECT event_id, primary_region_id
        FROM historical_event
        WHERE review_status = 'approved' AND is_active = 1 AND primary_region_id IS NOT NULL
    """,
    "memorial_region": """
        SELECT memorial_id, region_id
        FROM memorial_hall
        WHERE review_status = 'approved' AND is_active = 1 AND region_id IS NOT NULL
    """,
    "story_region": """
        SELECT story_id, related_region_id
        FROM red_story
        WHERE review_status = 'approved' AND is_active = 1 AND related_region_id IS NOT NULL
    """,
    "site_event": """
        SELECT site_id, event_id, relation_type, importance_level, remark
        FROM site_event_rel
    """,
    "site_hero": """
        SELECT site_id, hero_id, relation_type, importance_level, remark
        FROM site_hero_rel
    """,
    "event_hero": """
        SELECT event_id, hero_id, relation_type, contribution_text
        FROM event_hero_rel
    """,
    "memorial_site": """
        SELECT memorial_id, site_id, relation_type
        FROM memorial_site_rel
    """,
    "memorial_hero": """
        SELECT memorial_id, hero_id, relation_type
        FROM memorial_hero_rel
    """,
    "memorial_event": """
        SELECT memorial_id, event_id, relation_type
        FROM memorial_event_rel
    """,
    "story_entity": """
        SELECT story_id, entity_type, entity_id, relation_type
        FROM story_entity_rel
    """,
    "entity_tag": """
        SELECT entity_type, entity_id, tag_id
        FROM entity_tag_rel
    """,
    "school_region": """
        SELECT school_id, region_id
        FROM school
        WHERE review_status = 'approved' AND is_active = 1 AND region_id IS NOT NULL
    """,
    "resource_region": """
        SELECT resource_id, region_id
        FROM local_edu_resource
        WHERE review_status = 'approved' AND is_active = 1 AND region_id IS NOT NULL
    """,
    "school_resource": """
        SELECT school_id, resource_id, relation_type, distance_meters,
               recommended_travel_mode, estimated_duration_minutes,
               reachability_level, priority_level, education_theme_summary
        FROM school_resource_rel
        WHERE review_status = 'approved'
    """,
    "activity_resource": """
        SELECT plan_id, resource_id
        FROM teaching_activity_plan
        WHERE review_status = 'approved' AND is_active = 1 AND resource_id IS NOT NULL
    """,
}


CREATE_CONSTRAINTS = [
    "CREATE CONSTRAINT region_id_unique IF NOT EXISTS FOR (n:Region) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT site_id_unique IF NOT EXISTS FOR (n:Site) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT hero_id_unique IF NOT EXISTS FOR (n:Hero) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT event_id_unique IF NOT EXISTS FOR (n:Event) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT memorial_id_unique IF NOT EXISTS FOR (n:Memorial) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT story_id_unique IF NOT EXISTS FOR (n:Story) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT tag_id_unique IF NOT EXISTS FOR (n:Tag) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT school_id_unique IF NOT EXISTS FOR (n:School) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT local_resource_id_unique IF NOT EXISTS FOR (n:LocalEduResource) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT activity_plan_id_unique IF NOT EXISTS FOR (n:ActivityPlan) REQUIRE n.id IS UNIQUE",
    "CREATE CONSTRAINT year_value_unique IF NOT EXISTS FOR (n:Year) REQUIRE n.value IS UNIQUE",
    "CREATE CONSTRAINT time_period_code_unique IF NOT EXISTS FOR (n:TimePeriod) REQUIRE n.code IS UNIQUE",
]

MYSQL_RELATION_TYPES = [
    "HAS_CHILD_REGION", "LOCATED_IN", "NATIVE_TO", "HAPPENED_IN", "RELATED_TO_REGION",
    "OCCURRED_AT", "RELATED_TO", "MEMORIALIZED_AT", "BORN_IN", "FOUGHT_IN", "VISITED",
    "PARTICIPATED_IN", "LED", "WITNESSED", "MARTYR_IN", "LOCATED_AT", "DISPLAYS",
    "COMMEMORATES", "EXHIBITS", "ABOUT", "MENTIONS", "TEACHES", "HAS_TAG",
    "SCHOOL_NEAR_RESOURCE", "HAS_ACTIVITY_PLAN", "RESOURCE_SUPPORTS_ACTIVITY",
    "HAPPENED_IN_YEAR", "STARTED_IN_YEAR", "ENDED_IN_YEAR", "HAPPENED_DURING",
    "BORN_IN_YEAR", "DIED_IN_YEAR", "ESTABLISHED_IN_YEAR",
]

TIME_PERIODS = [
    {"code": "land_revolution", "name": "土地革命战争时期", "startYear": 1927, "endYear": 1937},
    {"code": "war_of_resistance", "name": "全民族抗日战争时期", "startYear": 1937, "endYear": 1945},
    {"code": "liberation_war", "name": "解放战争时期", "startYear": 1945, "endYear": 1949},
    {"code": "early_prc", "name": "社会主义革命和建设初期", "startYear": 1949, "endYear": 1956},
]


@dataclass
class SyncStats:
    regions: int = 0
    sites: int = 0
    heroes: int = 0
    events: int = 0
    memorials: int = 0
    stories: int = 0
    tags: int = 0
    schools: int = 0
    local_resources: int = 0
    activity_plans: int = 0
    relations: int = 0


def fetch_rows(conn: pymysql.Connection, sql: str) -> list[dict[str, Any]]:
    with conn.cursor() as cursor:
        cursor.execute(sql)
        rows = cursor.fetchall()
        return [
            {key: to_neo4j_value(value) for key, value in row.items()}
            for row in rows
        ]


def to_neo4j_value(value: Any) -> Any:
    """Convert MySQL values that the Neo4j driver cannot serialize directly."""
    if isinstance(value, Decimal):
        return float(value)
    return value


def to_iso_date(value: Any) -> str | None:
    if value is None:
        return None
    return value.isoformat()


def prepare_reviewed_nodes(
    rows: list[dict[str, Any]], entity_type: str, id_key: str
) -> list[dict[str, Any]]:
    """Keep rejected/disabled rows in the projection, but make them unqueryable."""
    for row in rows:
        row["graph_active"] = bool(row.get("is_active")) and row.get("review_status") == "approved"
        row["canonical_id"] = f"mysql:{entity_type}:{row[id_key]}"
    return rows


def execute_write(tx, cypher: str, rows: list[dict[str, Any]]) -> None:
    for row in rows:
        tx.run(cypher, **row)


def create_constraints(driver) -> None:
    with driver.session() as session:
        for cypher in CREATE_CONSTRAINTS:
            session.run(cypher)


def clear_mysql_relationships(driver) -> None:
    """Rebuild the derived MySQL projection so removed or revoked links cannot linger."""
    cypher = """
    MATCH (a)-[r]->(b)
    WHERE type(r) IN $relation_types
      AND (a.sourceSystem = 'mysql' OR b.sourceSystem = 'mysql')
    DELETE r
    """
    with driver.session() as session:
        session.run(cypher, relation_types=MYSQL_RELATION_TYPES).consume()


def require_relation_type(mapping: dict[str, str], raw_type: str, family: str) -> str:
    try:
        return mapping[raw_type]
    except KeyError as exc:
        raise ValueError(f"Unsupported {family} relation type: {raw_type!r}") from exc


def sync_regions(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, NODE_SQL["regions"])
    cypher = """
    MERGE (r:Region {id: $region_id})
    SET r.name = $region_name,
        r.level = $region_level,
        r.adcode = $adcode,
        r.longitude = $center_longitude,
        r.latitude = $center_latitude,
        r.intro = $intro,
        r.active = true,
        r.sourceSystem = 'mysql',
        r.canonicalId = 'mysql:Region:' + toString($region_id)
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.regions = len(rows)


def sync_sites(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = prepare_reviewed_nodes(fetch_rows(mysql_conn, NODE_SQL["sites"]), "Site", "site_id")
    cypher = """
    MERGE (s:Site {id: $site_id})
    SET s.code = $site_code,
        s.name = $site_name,
        s.alias = $site_alias,
        s.address = $address,
        s.longitude = $longitude,
        s.latitude = $latitude,
        s.establishedYear = $established_year,
        s.siteLevel = $site_level,
        s.protectionLevel = $protection_level,
        s.historicalBackground = $historical_background,
        s.intro = $intro,
        s.openingTime = $opening_time_desc,
        s.active = $graph_active,
        s.sourceSystem = 'mysql',
        s.canonicalId = $canonical_id
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.sites = len(rows)


def sync_heroes(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = prepare_reviewed_nodes(fetch_rows(mysql_conn, NODE_SQL["heroes"]), "Hero", "hero_id")
    cypher = """
    MERGE (h:Hero {id: $hero_id})
    SET h.code = $hero_code,
        h.name = $hero_name,
        h.alias = $hero_alias,
        h.gender = $gender,
        h.birthYear = $birth_year,
        h.deathYear = $death_year,
        h.nativePlace = $native_place_text,
        h.profileSummary = $profile_summary,
        h.mainDeeds = $main_deeds,
        h.active = $graph_active,
        h.sourceSystem = 'mysql',
        h.canonicalId = $canonical_id
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.heroes = len(rows)


def sync_events(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = prepare_reviewed_nodes(fetch_rows(mysql_conn, NODE_SQL["events"]), "Event", "event_id")
    for row in rows:
        row["start_date"] = to_iso_date(row["start_date"])
        row["end_date"] = to_iso_date(row["end_date"])
    cypher = """
    MERGE (e:Event {id: $event_id})
    SET e.code = $event_code,
        e.name = $event_name,
        e.alias = $event_alias,
        e.eventTimeText = $event_time_text,
        e.startDate = CASE WHEN $start_date IS NULL THEN NULL ELSE date($start_date) END,
        e.endDate = CASE WHEN $end_date IS NULL THEN NULL ELSE date($end_date) END,
        e.startYear = $start_year,
        e.endYear = $end_year,
        e.longitude = $longitude,
        e.latitude = $latitude,
        e.significance = $historical_significance,
        e.process = $event_process,
        e.impact = $result_impact,
        e.active = $graph_active,
        e.sourceSystem = 'mysql',
        e.canonicalId = $canonical_id
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.events = len(rows)


def sync_memorials(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = prepare_reviewed_nodes(fetch_rows(mysql_conn, NODE_SQL["memorials"]), "Memorial", "memorial_id")
    cypher = """
    MERGE (m:Memorial {id: $memorial_id})
    SET m.code = $memorial_code,
        m.name = $memorial_name,
        m.address = $address,
        m.longitude = $longitude,
        m.latitude = $latitude,
        m.exhibitionContent = $exhibition_content,
        m.intro = $intro,
        m.openingTime = $opening_time_desc,
        m.ticketInfo = $ticket_info,
        m.active = $graph_active,
        m.sourceSystem = 'mysql',
        m.canonicalId = $canonical_id
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.memorials = len(rows)


def sync_stories(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = prepare_reviewed_nodes(fetch_rows(mysql_conn, NODE_SQL["stories"]), "Story", "story_id")
    cypher = """
    MERGE (s:Story {id: $story_id})
    SET s.code = $story_code,
        s.title = $story_title,
        s.ageGroup = $age_group,
        s.summary = $summary,
        s.content = $story_content,
        s.active = $graph_active,
        s.sourceSystem = 'mysql',
        s.canonicalId = $canonical_id
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.stories = len(rows)


def sync_tags(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, NODE_SQL["tags"])
    cypher = """
    MERGE (t:Tag {id: $tag_id})
    SET t.name = $tag_name,
        t.type = $tag_type,
        t.description = $description,
        t.active = true,
        t.sourceSystem = 'mysql',
        t.canonicalId = 'mysql:Tag:' + toString($tag_id)
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.tags = len(rows)


def sync_schools(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = prepare_reviewed_nodes(fetch_rows(mysql_conn, NODE_SQL["schools"]), "School", "school_id")
    cypher = """
    MERGE (s:School {id: $school_id})
    SET s.code = $school_code,
        s.name = $school_name,
        s.alias = $school_alias,
        s.schoolLevel = $school_level,
        s.schoolType = $school_type,
        s.schoolNature = $school_nature,
        s.ruralSchool = $is_rural_school,
        s.teachingPoint = $is_teaching_point,
        s.address = $address,
        s.longitude = $longitude,
        s.latitude = $latitude,
        s.geoConfidence = $geo_confidence,
        s.geoVerified = $geo_verified,
        s.intro = $intro,
        s.active = $graph_active,
        s.sourceSystem = 'mysql',
        s.canonicalId = $canonical_id
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.schools = len(rows)


def sync_local_resources(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = prepare_reviewed_nodes(fetch_rows(mysql_conn, NODE_SQL["local_resources"]), "LocalEduResource", "resource_id")
    cypher = """
    MERGE (r:LocalEduResource {id: $resource_id})
    SET r.code = $resource_code,
        r.name = $resource_name,
        r.alias = $resource_alias,
        r.category = $resource_category,
        r.subcategory = $resource_subcategory,
        r.address = $address,
        r.longitude = $longitude,
        r.latitude = $latitude,
        r.organizationName = $organization_name,
        r.openingTime = $opening_time_desc,
        r.intro = $intro,
        r.educationValue = $education_value,
        r.activitySuggestion = $activity_suggestion,
        r.targetGrade = $target_grade,
        r.safetyNote = $safety_note,
        r.active = $graph_active,
        r.sourceSystem = 'mysql',
        r.canonicalId = $canonical_id
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.local_resources = len(rows)


def sync_activity_plans(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = prepare_reviewed_nodes(fetch_rows(mysql_conn, NODE_SQL["activity_plans"]), "ActivityPlan", "plan_id")
    cypher = """
    MERGE (a:ActivityPlan {id: $plan_id})
    SET a.code = $plan_code,
        a.theme = $theme,
        a.activityType = $activity_type,
        a.suitableGrade = $suitable_grade,
        a.objectiveText = $objective_text,
        a.activityContent = $activity_content,
        a.preparationText = $preparation_text,
        a.safetyText = $safety_text,
        a.expectedOutcome = $expected_outcome,
        a.durationMinutes = $duration_minutes,
        a.active = $graph_active,
        a.sourceSystem = 'mysql',
        a.canonicalId = $canonical_id
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.activity_plans = len(rows)


def sync_region_parent_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, RELATION_SQL["region_parent"])
    cypher = """
    MATCH (child:Region {id: $region_id, active: true})
    MATCH (parent:Region {id: $parent_region_id, active: true})
    MERGE (parent)-[:HAS_CHILD_REGION]->(child)
    """
    with neo4j_driver.session() as session:
        session.execute_write(execute_write, cypher, rows)
    stats.relations += len(rows)


def sync_entity_region_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    mapping = [
        ("site_region", "Site", "site_id", "region_id", "LOCATED_IN"),
        ("hero_region", "Hero", "hero_id", "native_place_region_id", "NATIVE_TO"),
        ("event_region", "Event", "event_id", "primary_region_id", "HAPPENED_IN"),
        ("memorial_region", "Memorial", "memorial_id", "region_id", "LOCATED_IN"),
        ("story_region", "Story", "story_id", "related_region_id", "RELATED_TO_REGION"),
        ("school_region", "School", "school_id", "region_id", "LOCATED_IN"),
        ("resource_region", "LocalEduResource", "resource_id", "region_id", "LOCATED_IN"),
    ]
    with neo4j_driver.session() as session:
        for sql_key, label, entity_id_key, region_id_key, relation_name in mapping:
            rows = fetch_rows(mysql_conn, RELATION_SQL[sql_key])
            cypher = f"""
            MATCH (a:{label} {{id: ${entity_id_key}, active: true}})
            MATCH (r:Region {{id: ${region_id_key}, active: true}})
            MERGE (a)-[:{relation_name}]->(r)
            """
            session.execute_write(execute_write, cypher, rows)
            stats.relations += len(rows)


def sync_site_event_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, RELATION_SQL["site_event"])
    type_mapping = {
        "occurred_at": "OCCURRED_AT",
        "related_to": "RELATED_TO",
        "memorialized_at": "MEMORIALIZED_AT",
    }
    with neo4j_driver.session() as session:
        for row in rows:
            relation_name = require_relation_type(type_mapping, row["relation_type"], "site-event")
            cypher = f"""
            MATCH (e:Event {{id: $event_id, active: true}})
            MATCH (s:Site {{id: $site_id, active: true}})
            MERGE (e)-[r:{relation_name}]->(s)
            SET r.importanceLevel = $importance_level,
                r.remark = $remark
            """
            session.run(cypher, **row)
    stats.relations += len(rows)


def sync_site_hero_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, RELATION_SQL["site_hero"])
    type_mapping = {
        "born_in": "BORN_IN",
        "fought_in": "FOUGHT_IN",
        "memorialized": "MEMORIALIZED_AT",
        "visited": "VISITED",
        "related_to": "RELATED_TO",
    }
    with neo4j_driver.session() as session:
        for row in rows:
            relation_name = require_relation_type(type_mapping, row["relation_type"], "site-hero")
            cypher = f"""
            MATCH (h:Hero {{id: $hero_id, active: true}})
            MATCH (s:Site {{id: $site_id, active: true}})
            MERGE (h)-[r:{relation_name}]->(s)
            SET r.importanceLevel = $importance_level,
                r.remark = $remark
            """
            session.run(cypher, **row)
    stats.relations += len(rows)


def sync_event_hero_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, RELATION_SQL["event_hero"])
    type_mapping = {
        "participant": "PARTICIPATED_IN",
        "leader": "LED",
        "witness": "WITNESSED",
        "martyr": "MARTYR_IN",
        "related_to": "RELATED_TO",
    }
    with neo4j_driver.session() as session:
        for row in rows:
            relation_name = require_relation_type(type_mapping, row["relation_type"], "event-hero")
            cypher = f"""
            MATCH (h:Hero {{id: $hero_id, active: true}})
            MATCH (e:Event {{id: $event_id, active: true}})
            MERGE (h)-[r:{relation_name}]->(e)
            SET r.contributionText = $contribution_text
            """
            session.run(cypher, **row)
    stats.relations += len(rows)


def sync_memorial_site_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, RELATION_SQL["memorial_site"])
    type_mapping = {
        "located_at": "LOCATED_AT",
        "displays": "DISPLAYS",
        "related_to": "RELATED_TO",
    }
    with neo4j_driver.session() as session:
        for row in rows:
            relation_name = require_relation_type(type_mapping, row["relation_type"], "memorial-site")
            cypher = f"""
            MATCH (m:Memorial {{id: $memorial_id, active: true}})
            MATCH (s:Site {{id: $site_id, active: true}})
            MERGE (m)-[:{relation_name}]->(s)
            """
            session.run(cypher, **row)
    stats.relations += len(rows)


def sync_memorial_hero_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, RELATION_SQL["memorial_hero"])
    type_mapping = {
        "commemorates": "COMMEMORATES",
        "exhibits": "EXHIBITS",
        "related_to": "RELATED_TO",
    }
    with neo4j_driver.session() as session:
        for row in rows:
            relation_name = require_relation_type(type_mapping, row["relation_type"], "memorial-hero")
            cypher = f"""
            MATCH (m:Memorial {{id: $memorial_id, active: true}})
            MATCH (h:Hero {{id: $hero_id, active: true}})
            MERGE (m)-[:{relation_name}]->(h)
            """
            session.run(cypher, **row)
    stats.relations += len(rows)


def sync_memorial_event_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, RELATION_SQL["memorial_event"])
    type_mapping = {
        "commemorates": "COMMEMORATES",
        "exhibits": "EXHIBITS",
        "related_to": "RELATED_TO",
    }
    with neo4j_driver.session() as session:
        for row in rows:
            relation_name = require_relation_type(type_mapping, row["relation_type"], "memorial-event")
            cypher = f"""
            MATCH (m:Memorial {{id: $memorial_id, active: true}})
            MATCH (e:Event {{id: $event_id, active: true}})
            MERGE (m)-[:{relation_name}]->(e)
            """
            session.run(cypher, **row)
    stats.relations += len(rows)


def sync_story_entity_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, RELATION_SQL["story_entity"])
    entity_label_mapping = {
        "site": "Site",
        "hero": "Hero",
        "event": "Event",
        "memorial": "Memorial",
    }
    relation_mapping = {
        "about": "ABOUT",
        "mentions": "MENTIONS",
        "teaches": "TEACHES",
    }
    with neo4j_driver.session() as session:
        for row in rows:
            label = entity_label_mapping.get(row["entity_type"])
            relation_name = require_relation_type(relation_mapping, row["relation_type"], "story-entity")
            if not label:
                continue
            cypher = f"""
            MATCH (s:Story {{id: $story_id, active: true}})
            MATCH (e:{label} {{id: $entity_id, active: true}})
            MERGE (s)-[:{relation_name}]->(e)
            """
            session.run(cypher, **row)
    stats.relations += len(rows)


def sync_entity_tag_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, RELATION_SQL["entity_tag"])
    entity_label_mapping = {
        "site": "Site",
        "hero": "Hero",
        "event": "Event",
        "memorial": "Memorial",
        "story": "Story",
        "school": "School",
        "resource": "LocalEduResource",
        "activity_plan": "ActivityPlan",
    }
    with neo4j_driver.session() as session:
        for row in rows:
            label = entity_label_mapping.get(row["entity_type"])
            if not label:
                continue
            cypher = f"""
            MATCH (e:{label} {{id: $entity_id, active: true}})
            MATCH (t:Tag {{id: $tag_id, active: true}})
            MERGE (e)-[:HAS_TAG]->(t)
            """
            session.run(cypher, **row)
    stats.relations += len(rows)


def sync_school_resource_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    rows = fetch_rows(mysql_conn, RELATION_SQL["school_resource"])
    with neo4j_driver.session() as session:
        for row in rows:
            cypher = """
            MATCH (s:School {id: $school_id, active: true})
            MATCH (r:LocalEduResource {id: $resource_id, active: true})
            MERGE (s)-[rel:SCHOOL_NEAR_RESOURCE]->(r)
            SET rel.relationType = $relation_type,
                rel.distanceMeters = $distance_meters,
                rel.recommendedTravelMode = $recommended_travel_mode,
                rel.estimatedDurationMinutes = $estimated_duration_minutes,
                rel.reachabilityLevel = $reachability_level,
                rel.priorityLevel = $priority_level,
                rel.educationThemeSummary = $education_theme_summary
            """
            session.run(cypher, **row)
    stats.relations += len(rows)


def sync_activity_plan_relations(mysql_conn, neo4j_driver, stats: SyncStats) -> None:
    plan_rows = fetch_rows(mysql_conn, NODE_SQL["activity_plans"])
    with neo4j_driver.session() as session:
        for row in plan_rows:
            cypher = """
            MATCH (s:School {id: $school_id, active: true})
            MATCH (a:ActivityPlan {id: $plan_id, active: true})
            MERGE (s)-[:HAS_ACTIVITY_PLAN]->(a)
            """
            session.run(cypher, **row)
    stats.relations += len(plan_rows)

    resource_rows = fetch_rows(mysql_conn, RELATION_SQL["activity_resource"])
    with neo4j_driver.session() as session:
        for row in resource_rows:
            cypher = """
            MATCH (a:ActivityPlan {id: $plan_id, active: true})
            MATCH (r:LocalEduResource {id: $resource_id, active: true})
            MERGE (r)-[:RESOURCE_SUPPORTS_ACTIVITY]->(a)
            """
            session.run(cypher, **row)
    stats.relations += len(resource_rows)


def sync_time_dimension_relations(neo4j_driver, stats: SyncStats) -> None:
    """Create first-class time nodes and connect reviewed graph entities to them."""
    cyphers = [
        (
            """
            UNWIND $periods AS period
            MERGE (p:TimePeriod {code: period.code})
            SET p.name = period.name,
                p.startYear = period.startYear,
                p.endYear = period.endYear,
                p.active = true,
                p.sourceSystem = 'system',
                p.canonicalId = 'system:TimePeriod:' + period.code
            """,
            {"periods": TIME_PERIODS},
        ),
        (
            """
            MATCH (e:Event {sourceSystem: 'mysql', active: true})
            WHERE e.startYear IS NOT NULL
            WITH e, toInteger(e.startYear) AS startYear,
                 toInteger(coalesce(e.endYear, e.startYear)) AS rawEndYear
            WITH e, startYear,
                 CASE WHEN rawEndYear < startYear THEN startYear ELSE rawEndYear END AS endYear
            WHERE endYear - startYear <= 20
            UNWIND range(startYear, endYear) AS yearValue
            MERGE (y:Year {value: yearValue})
            SET y.active = true,
                y.sourceSystem = 'system',
                y.canonicalId = 'system:Year:' + toString(yearValue)
            MERGE (e)-[:HAPPENED_IN_YEAR]->(y)
            """,
            {},
        ),
        (
            """
            MATCH (e:Event {sourceSystem: 'mysql', active: true})
            WHERE e.startYear IS NOT NULL
            MERGE (y:Year {value: toInteger(e.startYear)})
            SET y.active = true,
                y.sourceSystem = 'system',
                y.canonicalId = 'system:Year:' + toString(toInteger(e.startYear))
            MERGE (e)-[:STARTED_IN_YEAR]->(y)
            """,
            {},
        ),
        (
            """
            MATCH (e:Event {sourceSystem: 'mysql', active: true})
            WHERE e.endYear IS NOT NULL
            MERGE (y:Year {value: toInteger(e.endYear)})
            SET y.active = true,
                y.sourceSystem = 'system',
                y.canonicalId = 'system:Year:' + toString(toInteger(e.endYear))
            MERGE (e)-[:ENDED_IN_YEAR]->(y)
            """,
            {},
        ),
        (
            """
            MATCH (e:Event {sourceSystem: 'mysql', active: true})
            WHERE e.startYear IS NOT NULL
            WITH e, toInteger(e.startYear) AS startYear,
                 toInteger(coalesce(e.endYear, e.startYear)) AS rawEndYear
            WITH e, startYear,
                 CASE WHEN rawEndYear < startYear THEN startYear ELSE rawEndYear END AS endYear
            MATCH (p:TimePeriod {active: true})
            WHERE startYear <= p.endYear AND endYear >= p.startYear
            MERGE (e)-[:HAPPENED_DURING]->(p)
            """,
            {},
        ),
        (
            """
            MATCH (h:Hero {sourceSystem: 'mysql', active: true})
            WHERE h.birthYear IS NOT NULL
            MERGE (y:Year {value: toInteger(h.birthYear)})
            SET y.active = true,
                y.sourceSystem = 'system',
                y.canonicalId = 'system:Year:' + toString(toInteger(h.birthYear))
            MERGE (h)-[:BORN_IN_YEAR]->(y)
            """,
            {},
        ),
        (
            """
            MATCH (h:Hero {sourceSystem: 'mysql', active: true})
            WHERE h.deathYear IS NOT NULL
            MERGE (y:Year {value: toInteger(h.deathYear)})
            SET y.active = true,
                y.sourceSystem = 'system',
                y.canonicalId = 'system:Year:' + toString(toInteger(h.deathYear))
            MERGE (h)-[:DIED_IN_YEAR]->(y)
            """,
            {},
        ),
        (
            """
            MATCH (s:Site {sourceSystem: 'mysql', active: true})
            WHERE s.establishedYear IS NOT NULL
            MERGE (y:Year {value: toInteger(s.establishedYear)})
            SET y.active = true,
                y.sourceSystem = 'system',
                y.canonicalId = 'system:Year:' + toString(toInteger(s.establishedYear))
            MERGE (s)-[:ESTABLISHED_IN_YEAR]->(y)
            """,
            {},
        ),
    ]
    with neo4j_driver.session() as session:
        for cypher, parameters in cyphers:
            summary = session.run(cypher, **parameters).consume()
            stats.relations += summary.counters.relationships_created


def main() -> None:
    stats = SyncStats()
    mysql_conn = pymysql.connect(**MYSQL_CONFIG)
    neo4j_driver = GraphDatabase.driver(
        NEO4J_CONFIG["uri"],
        auth=(NEO4J_CONFIG["user"], NEO4J_CONFIG["password"]),
    )

    try:
        print("Creating Neo4j constraints...")
        create_constraints(neo4j_driver)

        print("Syncing nodes...")
        sync_regions(mysql_conn, neo4j_driver, stats)
        sync_sites(mysql_conn, neo4j_driver, stats)
        sync_heroes(mysql_conn, neo4j_driver, stats)
        sync_events(mysql_conn, neo4j_driver, stats)
        sync_memorials(mysql_conn, neo4j_driver, stats)
        sync_stories(mysql_conn, neo4j_driver, stats)
        sync_tags(mysql_conn, neo4j_driver, stats)
        sync_schools(mysql_conn, neo4j_driver, stats)
        sync_local_resources(mysql_conn, neo4j_driver, stats)
        sync_activity_plans(mysql_conn, neo4j_driver, stats)

        print("Syncing relations...")
        clear_mysql_relationships(neo4j_driver)
        sync_region_parent_relations(mysql_conn, neo4j_driver, stats)
        sync_entity_region_relations(mysql_conn, neo4j_driver, stats)
        sync_site_event_relations(mysql_conn, neo4j_driver, stats)
        sync_site_hero_relations(mysql_conn, neo4j_driver, stats)
        sync_event_hero_relations(mysql_conn, neo4j_driver, stats)
        sync_memorial_site_relations(mysql_conn, neo4j_driver, stats)
        sync_memorial_hero_relations(mysql_conn, neo4j_driver, stats)
        sync_memorial_event_relations(mysql_conn, neo4j_driver, stats)
        sync_story_entity_relations(mysql_conn, neo4j_driver, stats)
        sync_entity_tag_relations(mysql_conn, neo4j_driver, stats)
        sync_school_resource_relations(mysql_conn, neo4j_driver, stats)
        sync_activity_plan_relations(mysql_conn, neo4j_driver, stats)
        sync_time_dimension_relations(neo4j_driver, stats)

        print("Sync completed.")
        print(f"Regions: {stats.regions}")
        print(f"Sites: {stats.sites}")
        print(f"Heroes: {stats.heroes}")
        print(f"Events: {stats.events}")
        print(f"Memorials: {stats.memorials}")
        print(f"Stories: {stats.stories}")
        print(f"Tags: {stats.tags}")
        print(f"Schools: {stats.schools}")
        print(f"Local resources: {stats.local_resources}")
        print(f"Activity plans: {stats.activity_plans}")
        print(f"Relations: {stats.relations}")
    finally:
        mysql_conn.close()
        neo4j_driver.close()


if __name__ == "__main__":
    main()
