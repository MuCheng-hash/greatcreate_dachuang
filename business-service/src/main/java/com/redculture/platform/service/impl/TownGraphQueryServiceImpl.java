package com.redculture.platform.service.impl;

import com.redculture.platform.service.TownGraphQueryService;
import com.redculture.platform.vo.EventSummaryVO;
import com.redculture.platform.vo.HeroSummaryVO;
import com.redculture.platform.vo.MapResourceMarkerVO;
import com.redculture.platform.vo.StorySummaryVO;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TownGraphQueryServiceImpl implements TownGraphQueryService {

    private final Neo4jClient neo4jClient;

    public TownGraphQueryServiceImpl(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    public TownGraphSnapshot loadTownGraph(Long regionId) {
        try {
            if (!regionExists(regionId)) {
                return new TownGraphSnapshot(
                        false,
                        "town region has not been synced to neo4j",
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList()
                );
            }

            List<MapResourceMarkerVO> markers = loadMarkers(regionId);
            List<EventSummaryVO> events = loadEvents(regionId);
            List<HeroSummaryVO> heroes = loadHeroes(regionId);
            List<StorySummaryVO> stories = loadStories(regionId);
            return new TownGraphSnapshot(
                    true,
                    buildGraphMessage(markers, heroes, stories, events),
                    markers,
                    heroes,
                    stories,
                    events
            );
        } catch (Exception exception) {
            return new TownGraphSnapshot(
                    false,
                    "neo4j unavailable or graph data missing",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }
    }

    private boolean regionExists(Long regionId) {
        String cypher = "MATCH (r:Region {id: $regionId}) RETURN count(r) AS total";
        Map<String, Object> row = neo4jClient.query(cypher)
                .bind(regionId).to("regionId")
                .fetch()
                .one()
                .orElse(Collections.emptyMap());
        Long total = longValue(row.get("total"));
        return total != null && total > 0;
    }

    private List<MapResourceMarkerVO> loadMarkers(Long regionId) {
        String cypher = ""
                + "MATCH (r:Region {id: $regionId}) "
                + "OPTIONAL MATCH (site:Site)-[:LOCATED_IN]->(r) "
                + "WHERE coalesce(site.active, true) = true "
                + "OPTIONAL MATCH (eventForSite:Event)-[:OCCURRED_AT|RELATED_TO|MEMORIALIZED_AT]->(site) "
                + "WITH r, site, collect(DISTINCT eventForSite.name) AS siteEventNames "
                + "RETURN site.id AS id, "
                + "'site' AS type, "
                + "site.name AS name, "
                + "site.longitude AS longitude, "
                + "site.latitude AS latitude, "
                + "site.address AS address, "
                + "coalesce(site.intro, site.historicalBackground) AS summary, "
                + "CASE WHEN size(siteEventNames) = 0 THEN site.protectionLevel "
                + "ELSE '关联事件：' + reduce(text = '', item IN siteEventNames | CASE WHEN text = '' THEN item ELSE text + '、' + item END) END AS relationHint "
                + "UNION "
                + "MATCH (r:Region {id: $regionId}) "
                + "OPTIONAL MATCH (memorial:Memorial)-[:LOCATED_IN]->(r) "
                + "WHERE coalesce(memorial.active, true) = true "
                + "OPTIONAL MATCH (memorial)-[:LOCATED_AT|DISPLAYS|RELATED_TO]->(site:Site) "
                + "OPTIONAL MATCH (memorial)-[:COMMEMORATES|EXHIBITS|RELATED_TO]->(hero:Hero) "
                + "OPTIONAL MATCH (memorial)-[:COMMEMORATES|EXHIBITS|RELATED_TO]->(event:Event) "
                + "WITH memorial, collect(DISTINCT site.name) + collect(DISTINCT hero.name) + collect(DISTINCT event.name) AS linkedNames "
                + "RETURN memorial.id AS id, "
                + "'memorial' AS type, "
                + "memorial.name AS name, "
                + "memorial.longitude AS longitude, "
                + "memorial.latitude AS latitude, "
                + "memorial.address AS address, "
                + "coalesce(memorial.intro, memorial.exhibitionContent) AS summary, "
                + "CASE WHEN size(linkedNames) = 0 THEN memorial.openingTime "
                + "ELSE reduce(text = '', item IN linkedNames | CASE WHEN item IS NULL OR item = '' THEN text WHEN text = '' THEN item ELSE text + '、' + item END) END AS relationHint "
                + "UNION "
                + "MATCH (r:Region {id: $regionId}) "
                + "OPTIONAL MATCH (event:Event)-[:HAPPENED_IN]->(r) "
                + "WHERE coalesce(event.active, true) = true "
                + "OPTIONAL MATCH (event)-[:OCCURRED_AT|RELATED_TO|MEMORIALIZED_AT]->(site:Site) "
                + "OPTIONAL MATCH (memorial:Memorial)-[:COMMEMORATES|EXHIBITS|RELATED_TO]->(event) "
                + "WITH event, collect(DISTINCT site.name) + collect(DISTINCT memorial.name) AS linkedNames "
                + "RETURN event.id AS id, "
                + "'event' AS type, "
                + "event.name AS name, "
                + "event.longitude AS longitude, "
                + "event.latitude AS latitude, "
                + "NULL AS address, "
                + "coalesce(event.impact, event.significance, event.process) AS summary, "
                + "CASE WHEN size(linkedNames) = 0 THEN event.eventTimeText "
                + "ELSE reduce(text = '', item IN linkedNames | CASE WHEN item IS NULL OR item = '' THEN text WHEN text = '' THEN item ELSE text + '、' + item END) END AS relationHint "
                + "ORDER BY type, name";
        List<Map<String, Object>> rows = fetchRows(cypher, regionId);
        List<MapResourceMarkerVO> markers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (longValue(row.get("id")) == null) {
                continue;
            }
            MapResourceMarkerVO marker = new MapResourceMarkerVO();
            marker.setId(longValue(row.get("id")));
            marker.setType(stringValue(row.get("type")));
            marker.setName(stringValue(row.get("name")));
            marker.setLongitude(decimalValue(row.get("longitude")));
            marker.setLatitude(decimalValue(row.get("latitude")));
            marker.setAddress(stringValue(row.get("address")));
            marker.setSummary(stringValue(row.get("summary")));
            marker.setRelationHint(stringValue(row.get("relationHint")));
            markers.add(marker);
        }
        return deduplicateMarkers(markers);
    }

    private List<EventSummaryVO> loadEvents(Long regionId) {
        String cypher = ""
                + "MATCH (r:Region {id: $regionId}) "
                + "OPTIONAL MATCH (directEvent:Event)-[:HAPPENED_IN]->(r) "
                + "WHERE coalesce(directEvent.active, true) = true "
                + "OPTIONAL MATCH (site:Site)-[:LOCATED_IN]->(r) "
                + "OPTIONAL MATCH (site)<-[:OCCURRED_AT|RELATED_TO|MEMORIALIZED_AT]-(siteEvent:Event) "
                + "WHERE siteEvent IS NULL OR coalesce(siteEvent.active, true) = true "
                + "OPTIONAL MATCH (memorial:Memorial)-[:LOCATED_IN]->(r) "
                + "OPTIONAL MATCH (memorial)-[:COMMEMORATES|EXHIBITS|RELATED_TO]->(memorialEvent:Event) "
                + "WHERE memorialEvent IS NULL OR coalesce(memorialEvent.active, true) = true "
                + "WITH collect(DISTINCT directEvent) + collect(DISTINCT siteEvent) + collect(DISTINCT memorialEvent) AS eventNodes "
                + "UNWIND eventNodes AS event "
                + "WITH DISTINCT event "
                + "WHERE event IS NOT NULL "
                + "OPTIONAL MATCH (event)-[:OCCURRED_AT|RELATED_TO|MEMORIALIZED_AT]->(site:Site) "
                + "OPTIONAL MATCH (memorial:Memorial)-[:COMMEMORATES|EXHIBITS|RELATED_TO]->(event) "
                + "WITH event, collect(DISTINCT site.name) + collect(DISTINCT memorial.name) AS linkedNames "
                + "RETURN event.id AS id, "
                + "event.name AS name, "
                + "event.eventTimeText AS eventTimeText, "
                + "coalesce(event.impact, event.significance, event.process) AS summary, "
                + "linkedNames AS linkedNames "
                + "ORDER BY name";
        List<Map<String, Object>> rows = fetchRows(cypher, regionId);
        List<EventSummaryVO> events = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (longValue(row.get("id")) == null) {
                continue;
            }
            EventSummaryVO event = new EventSummaryVO();
            event.setEventId(longValue(row.get("id")));
            event.setEventName(stringValue(row.get("name")));
            event.setEventTimeText(stringValue(row.get("eventTimeText")));
            event.setSummary(firstNonBlank(
                    stringValue(row.get("summary")),
                    joinNames(stringListValue(row.get("linkedNames")), "关联资源：")));
            events.add(event);
        }
        return deduplicateEvents(events);
    }

    private List<HeroSummaryVO> loadHeroes(Long regionId) {
        String cypher = ""
                + "MATCH (r:Region {id: $regionId}) "
                + "OPTIONAL MATCH (site:Site)-[:LOCATED_IN]->(r) "
                + "OPTIONAL MATCH (event:Event)-[:HAPPENED_IN]->(r) "
                + "OPTIONAL MATCH (memorial:Memorial)-[:LOCATED_IN]->(r) "
                + "WITH r, collect(DISTINCT site) AS sites, collect(DISTINCT event) AS events, collect(DISTINCT memorial) AS memorials "
                + "MATCH (hero:Hero) "
                + "WHERE coalesce(hero.active, true) = true AND ("
                + "EXISTS { MATCH (hero)-[:NATIVE_TO]->(r) } "
                + "OR any(siteNode IN sites WHERE siteNode IS NOT NULL AND EXISTS { MATCH (hero)-[:BORN_IN|FOUGHT_IN|MEMORIALIZED_AT|VISITED|RELATED_TO]->(siteNode) }) "
                + "OR any(eventNode IN events WHERE eventNode IS NOT NULL AND EXISTS { MATCH (hero)-[:PARTICIPATED_IN|LED|WITNESSED|MARTYR_IN|RELATED_TO]->(eventNode) }) "
                + "OR any(memorialNode IN memorials WHERE memorialNode IS NOT NULL AND EXISTS { MATCH (memorialNode)-[:COMMEMORATES|EXHIBITS|RELATED_TO]->(hero) })"
                + ") "
                + "OPTIONAL MATCH (hero)-[:BORN_IN|FOUGHT_IN|MEMORIALIZED_AT|VISITED|RELATED_TO]->(heroSite:Site) "
                + "OPTIONAL MATCH (hero)-[:PARTICIPATED_IN|LED|WITNESSED|MARTYR_IN|RELATED_TO]->(heroEvent:Event) "
                + "OPTIONAL MATCH (hero)-[:NATIVE_TO]->(nativeRegion:Region) "
                + "OPTIONAL MATCH (heroMemorial:Memorial)-[:COMMEMORATES|EXHIBITS|RELATED_TO]->(hero) "
                + "WITH hero, nativeRegion, "
                + "collect(DISTINCT heroSite.name) + collect(DISTINCT heroEvent.name) + collect(DISTINCT heroMemorial.name) AS relatedNames "
                + "RETURN hero.id AS id, "
                + "hero.name AS name, "
                + "coalesce(hero.nativePlace, nativeRegion.name) AS nativePlace, "
                + "hero.profileSummary AS profileSummary, "
                + "hero.mainDeeds AS mainDeeds, "
                + "relatedNames AS relatedNames "
                + "ORDER BY name";
        List<Map<String, Object>> rows = fetchRows(cypher, regionId);
        List<HeroSummaryVO> heroes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (longValue(row.get("id")) == null) {
                continue;
            }
            HeroSummaryVO hero = new HeroSummaryVO();
            hero.setHeroId(longValue(row.get("id")));
            hero.setHeroName(stringValue(row.get("name")));
            hero.setNativePlaceText(stringValue(row.get("nativePlace")));
            hero.setProfileSummary(stringValue(row.get("profileSummary")));
            hero.setMainDeeds(stringValue(row.get("mainDeeds")));
            hero.setRelatedResourceNames(stringListValue(row.get("relatedNames")));
            heroes.add(hero);
        }
        return deduplicateHeroes(heroes);
    }

    private List<StorySummaryVO> loadStories(Long regionId) {
        String cypher = ""
                + "MATCH (r:Region {id: $regionId}) "
                + "OPTIONAL MATCH (regionStory:Story)-[:RELATED_TO_REGION]->(r) "
                + "WHERE coalesce(regionStory.active, true) = true "
                + "OPTIONAL MATCH (site:Site)-[:LOCATED_IN]->(r) "
                + "OPTIONAL MATCH (event:Event)-[:HAPPENED_IN]->(r) "
                + "OPTIONAL MATCH (memorial:Memorial)-[:LOCATED_IN]->(r) "
                + "OPTIONAL MATCH (hero:Hero)-[:NATIVE_TO]->(r) "
                + "WITH collect(DISTINCT regionStory) AS regionStories, "
                + "collect(DISTINCT site) AS sites, "
                + "collect(DISTINCT event) AS events, "
                + "collect(DISTINCT memorial) AS memorials, "
                + "collect(DISTINCT hero) AS nativeHeroes "
                + "OPTIONAL MATCH (story:Story)-[:ABOUT|MENTIONS|TEACHES]->(entity) "
                + "WHERE coalesce(story.active, true) = true AND (entity IN sites OR entity IN events OR entity IN memorials OR entity IN nativeHeroes) "
                + "WITH regionStories + collect(DISTINCT story) AS storyNodes "
                + "UNWIND storyNodes AS story "
                + "WITH DISTINCT story "
                + "WHERE story IS NOT NULL "
                + "OPTIONAL MATCH (story)-[:ABOUT|MENTIONS|TEACHES]->(entity) "
                + "WHERE entity:Site OR entity:Hero OR entity:Event OR entity:Memorial OR entity:Region "
                + "RETURN story.id AS id, "
                + "story.title AS title, "
                + "story.ageGroup AS ageGroup, "
                + "story.summary AS summary, "
                + "collect(DISTINCT entity.name) AS relatedNames "
                + "ORDER BY title";
        List<Map<String, Object>> rows = fetchRows(cypher, regionId);
        Map<Long, StorySummaryVO> storyMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long id = longValue(row.get("id"));
            if (id == null) {
                continue;
            }
            StorySummaryVO story = storyMap.computeIfAbsent(id, ignored -> {
                StorySummaryVO vo = new StorySummaryVO();
                vo.setStoryId(id);
                vo.setStoryTitle(stringValue(row.get("title")));
                vo.setAgeGroup(stringValue(row.get("ageGroup")));
                vo.setSummary(stringValue(row.get("summary")));
                return vo;
            });
            story.setRelatedEntityNames(stringListValue(row.get("relatedNames")));
        }
        return new ArrayList<>(storyMap.values());
    }

    private List<Map<String, Object>> fetchRows(String cypher, Long regionId) {
        return new ArrayList<>(neo4jClient.query(cypher)
                .bind(regionId).to("regionId")
                .fetch()
                .all());
    }

    private String buildGraphMessage(List<MapResourceMarkerVO> markers,
                                     List<HeroSummaryVO> heroes,
                                     List<StorySummaryVO> stories,
                                     List<EventSummaryVO> events) {
        return "graph data loaded: "
                + markers.size() + " markers, "
                + heroes.size() + " heroes, "
                + stories.size() + " stories, "
                + events.size() + " events";
    }

    private List<MapResourceMarkerVO> deduplicateMarkers(List<MapResourceMarkerVO> markers) {
        Map<String, MapResourceMarkerVO> markerMap = new LinkedHashMap<>();
        for (MapResourceMarkerVO marker : markers) {
            String key = marker.getType() + ":" + marker.getId();
            MapResourceMarkerVO existing = markerMap.get(key);
            if (existing == null) {
                markerMap.put(key, marker);
                continue;
            }
            existing.setSummary(firstNonBlank(existing.getSummary(), marker.getSummary()));
            existing.setAddress(firstNonBlank(existing.getAddress(), marker.getAddress()));
            existing.setRelationHint(mergeText(existing.getRelationHint(), marker.getRelationHint()));
        }
        return new ArrayList<>(markerMap.values());
    }

    private List<EventSummaryVO> deduplicateEvents(List<EventSummaryVO> events) {
        Map<Long, EventSummaryVO> eventMap = new LinkedHashMap<>();
        for (EventSummaryVO event : events) {
            EventSummaryVO existing = eventMap.get(event.getEventId());
            if (existing == null) {
                eventMap.put(event.getEventId(), event);
                continue;
            }
            existing.setSummary(firstNonBlank(existing.getSummary(), event.getSummary()));
            existing.setEventTimeText(firstNonBlank(existing.getEventTimeText(), event.getEventTimeText()));
        }
        return new ArrayList<>(eventMap.values());
    }

    private List<HeroSummaryVO> deduplicateHeroes(List<HeroSummaryVO> heroes) {
        Map<Long, HeroSummaryVO> heroMap = new LinkedHashMap<>();
        for (HeroSummaryVO hero : heroes) {
            HeroSummaryVO existing = heroMap.get(hero.getHeroId());
            if (existing == null) {
                hero.setRelatedResourceNames(deduplicateStrings(hero.getRelatedResourceNames()));
                heroMap.put(hero.getHeroId(), hero);
                continue;
            }
            existing.setProfileSummary(firstNonBlank(existing.getProfileSummary(), hero.getProfileSummary()));
            existing.setMainDeeds(firstNonBlank(existing.getMainDeeds(), hero.getMainDeeds()));
            existing.setNativePlaceText(firstNonBlank(existing.getNativePlaceText(), hero.getNativePlaceText()));
            existing.setRelatedResourceNames(mergeLists(existing.getRelatedResourceNames(), hero.getRelatedResourceNames()));
        }
        return new ArrayList<>(heroMap.values());
    }

    private List<String> deduplicateStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                result.add(value);
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> mergeLists(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(deduplicateStrings(first));
        }
        if (second != null) {
            merged.addAll(deduplicateStrings(second));
        }
        return new ArrayList<>(merged);
    }

    private String mergeText(String first, String second) {
        if (first == null || first.trim().isEmpty()) {
            return second;
        }
        if (second == null || second.trim().isEmpty() || first.contains(second)) {
            return first;
        }
        return first + "；" + second;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private String joinNames(List<String> names, String prefix) {
        List<String> deduplicated = deduplicateStrings(names);
        if (deduplicated.isEmpty()) {
            return null;
        }
        return prefix + String.join("、", deduplicated);
    }

    private Long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return value == null ? null : Long.parseLong(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private List<String> stringListValue(Object value) {
        if (!(value instanceof List<?>)) {
            return Collections.emptyList();
        }
        List<?> list = (List<?>) value;
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
