package com.redculture.platform.service.impl;

import com.redculture.platform.service.RedCultureGraphMapService;
import com.redculture.platform.vo.RedCultureSiteDetailVO;
import com.redculture.platform.vo.RedCultureSiteMarkerVO;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class RedCultureGraphMapServiceImpl implements RedCultureGraphMapService {
    private final Neo4jClient neo4jClient;

    public RedCultureGraphMapServiceImpl(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    public List<RedCultureSiteMarkerVO> listPublishedSites(String district) {
        String cypher = "MATCH (site:Location) "
                + "WHERE site.published = true AND site.longitude IS NOT NULL AND site.latitude IS NOT NULL "
                + "AND ($district = '' OR site.district = $district) "
                + "RETURN site.id AS id, site.name AS name, site.category AS category, site.address AS address, "
                + "site.district AS district, site.longitude AS longitude, site.latitude AS latitude, site.intro AS summary "
                + "ORDER BY site.name";
        return neo4jClient.query(cypher).bind(StringUtils.hasText(district) ? district.trim() : "").to("district")
                .fetch().all().stream().map(this::toMarker).toList();
    }

    @Override
    public RedCultureSiteDetailVO getPublishedSite(String siteId) {
        String cypher = "MATCH (site:Location {id:$siteId, published:true}) "
                + "RETURN site.id AS id, site.name AS name, site.category AS category, site.address AS address, "
                + "site.district AS district, site.historicalPeriod AS historicalPeriod, site.intro AS intro, "
                + "site.teachingTags AS teachingTags, site.longitude AS longitude, site.latitude AS latitude";
        Map<String, Object> row = neo4jClient.query(cypher).bind(siteId).to("siteId")
                .fetch().one().orElse(null);
        if (row == null) return null;
        RedCultureSiteDetailVO detail = new RedCultureSiteDetailVO();
        detail.setId(text(row.get("id"))); detail.setName(text(row.get("name")));
        detail.setCategory(text(row.get("category"))); detail.setAddress(text(row.get("address")));
        detail.setDistrict(text(row.get("district"))); detail.setHistoricalPeriod(text(row.get("historicalPeriod")));
        detail.setIntro(text(row.get("intro"))); detail.setTeachingTags(text(row.get("teachingTags")));
        detail.setLongitude(decimal(row.get("longitude"))); detail.setLatitude(decimal(row.get("latitude")));
        detail.setEvents(loadRelated("MATCH (e:Event)-[:OCCURRED_AT]->(:Location {id:$siteId}) RETURN DISTINCT e.id AS id, e.name AS name, e.intro AS summary, e.startTime AS extra ORDER BY name", siteId));
        detail.setPeople(loadRelated("MATCH (p:Person)-[:PARTICIPATED_IN]->(e:Event)-[:OCCURRED_AT]->(:Location {id:$siteId}) RETURN DISTINCT p.id AS id, p.name AS name, p.intro AS summary, p.identity AS extra ORDER BY name", siteId));
        detail.setThemes(loadRelated("MATCH (e:Event)-[:OCCURRED_AT]->(:Location {id:$siteId}) MATCH (e)-[:EMBODIES]->(t:IdeologyTheme) RETURN DISTINCT t.id AS id, t.name AS name, t.summary AS summary, t.category AS extra ORDER BY name", siteId));
        detail.setTeachingResources(loadRelated("MATCH (r:TeachingResource) WHERE EXISTS { MATCH (r)-[:USES]->(:Location {id:$siteId}) } OR EXISTS { MATCH (r)-[:REFERENCES]->(:Event)-[:OCCURRED_AT]->(:Location {id:$siteId}) } RETURN DISTINCT r.id AS id, r.title AS name, r.objectives AS summary, r.resourceType AS extra ORDER BY name", siteId));
        detail.setSources(loadSources(siteId));
        return detail;
    }

    private List<RedCultureSiteDetailVO.RelatedItem> loadRelated(String cypher, String siteId) {
        return neo4jClient.query(cypher).bind(siteId).to("siteId").fetch().all().stream().map(row -> {
            RedCultureSiteDetailVO.RelatedItem item = new RedCultureSiteDetailVO.RelatedItem();
            item.setId(text(row.get("id"))); item.setName(text(row.get("name")));
            item.setSummary(text(row.get("summary"))); item.setExtra(text(row.get("extra")));
            return item;
        }).toList();
    }

    private List<RedCultureSiteDetailVO.SourceItem> loadSources(String siteId) {
        String cypher = "MATCH (:Location {id:$siteId})-[:SUPPORTED_BY]->(s:Source) "
                + "RETURN DISTINCT s.id AS id, s.title AS title, s.publisher AS publisher, s.url AS url, s.trustLevel AS trustLevel ORDER BY title";
        return neo4jClient.query(cypher).bind(siteId).to("siteId").fetch().all().stream().map(row -> {
            RedCultureSiteDetailVO.SourceItem item = new RedCultureSiteDetailVO.SourceItem();
            item.setId(text(row.get("id"))); item.setTitle(text(row.get("title")));
            item.setPublisher(text(row.get("publisher"))); item.setUrl(text(row.get("url")));
            item.setTrustLevel(text(row.get("trustLevel"))); return item;
        }).toList();
    }

    private RedCultureSiteMarkerVO toMarker(Map<String, Object> row) {
        RedCultureSiteMarkerVO item = new RedCultureSiteMarkerVO();
        item.setId(text(row.get("id"))); item.setName(text(row.get("name")));
        item.setCategory(text(row.get("category"))); item.setAddress(text(row.get("address")));
        item.setDistrict(text(row.get("district"))); item.setLongitude(decimal(row.get("longitude")));
        item.setLatitude(decimal(row.get("latitude"))); item.setSummary(text(row.get("summary"))); return item;
    }

    private String text(Object value) { return value == null ? null : value.toString(); }
    private BigDecimal decimal(Object value) { return value == null ? null : new BigDecimal(value.toString()); }
}
