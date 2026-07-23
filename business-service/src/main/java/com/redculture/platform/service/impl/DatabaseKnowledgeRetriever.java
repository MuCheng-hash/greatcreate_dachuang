package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.entity.DataSource;
import com.redculture.platform.entity.EntitySourceRel;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.mapper.DataSourceMapper;
import com.redculture.platform.mapper.EntitySourceRelMapper;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TownMapService;
import com.redculture.platform.vo.EventSummaryVO;
import com.redculture.platform.vo.HeroSummaryVO;
import com.redculture.platform.vo.MapResourceMarkerVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.StorySummaryVO;
import com.redculture.platform.vo.TownMapDetailVO;
import com.redculture.platform.vo.ai.KnowledgeChunkVO;
import com.redculture.platform.vo.ai.KnowledgeCitationCandidateVO;
import com.redculture.platform.vo.ai.KnowledgeGraphFactVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于已审核业务数据的首版 RAG 检索实现。
 *
 * <p>Agent 只依赖 KnowledgeRetriever 接口，本类负责把学校/区域/资源范围
 * 转换成 content_chunk、entity_source_rel 和 Neo4j 图谱证据。首版采用结构化
 * 过滤加关键词排序，不引入新的向量库或数据库表。</p>
 */
@Component
@Profile("!mock-rag")
public class DatabaseKnowledgeRetriever implements KnowledgeRetriever {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 8;
    private static final int MAX_CHUNKS = 8;
    private static final int MAX_CITATIONS = 8;
    private static final int MAX_GRAPH_FACTS = 8;
    private static final int MAX_TEXT_LENGTH = 700;
    private static final int MAX_EXCERPT_LENGTH = 180;
    private static final Pattern TERM_SPLITTER = Pattern.compile("[\\s,，。！？、；：:()（）\\[\\]{}<>《》【】\"'‘’“”]+" );

    private final ContentChunkMapper contentChunkMapper;
    private final EntitySourceRelMapper entitySourceRelMapper;
    private final DataSourceMapper dataSourceMapper;
    private final SchoolMapService schoolMapService;
    private final TownMapService townMapService;
    private final Neo4jClient neo4jClient;

    public DatabaseKnowledgeRetriever(ContentChunkMapper contentChunkMapper,
                                     EntitySourceRelMapper entitySourceRelMapper,
                                     DataSourceMapper dataSourceMapper,
                                     SchoolMapService schoolMapService,
                                     TownMapService townMapService,
                                     Neo4jClient neo4jClient) {
        this.contentChunkMapper = contentChunkMapper;
        this.entitySourceRelMapper = entitySourceRelMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.schoolMapService = schoolMapService;
        this.townMapService = townMapService;
        this.neo4jClient = neo4jClient;
    }

    @Override
    public KnowledgeRetrieveResult retrieve(KnowledgeRetrieveRequest request) {
        if (!validRequest(request)) {
            return KnowledgeRetrieveResult.empty();
        }

        try {
            RetrievalContext context = loadContext(request);
            if (context.entityIds().isEmpty() && context.graphFacts().isEmpty()) {
                return KnowledgeRetrieveResult.empty();
            }

            List<ScoredChunk> scoredChunks = loadChunks(request, context.entityIds());
            List<KnowledgeChunkVO> chunks = scoredChunks.stream()
                    .limit(normalizeTopK(request.getTopK()))
                    .map(ScoredChunk::chunk)
                    .collect(Collectors.toList());

            Map<Long, DataSource> sources = loadSources(
                    scoredChunks.stream()
                            .map(item -> item.chunk().getSourceId())
                            .filter(Objects::nonNull)
                            .collect(Collectors.toCollection(LinkedHashSet::new))
            );
            List<KnowledgeCitationCandidateVO> candidates = buildChunkCandidates(chunks, sources);
            candidates.addAll(buildSourceCandidates(context.entityIds(), sources));
            candidates.addAll(buildGraphCandidates(context.graphFacts()));
            candidates = deduplicateCandidates(candidates);

            KnowledgeRetrieveResult result = new KnowledgeRetrieveResult();
            result.setChunks(chunks);
            result.setGraphFacts(limitList(context.graphFacts(), MAX_GRAPH_FACTS));
            result.setCitationCandidates(limitList(candidates, MAX_CITATIONS));
            result.setRetrievalStatus(resolveStatus(
                    !chunks.isEmpty() || !context.graphFacts().isEmpty() || !candidates.isEmpty(),
                    context.graphUnavailable()
            ));
            return result;
        } catch (RuntimeException exception) {
            return KnowledgeRetrieveResult.degraded();
        }
    }

    private boolean validRequest(KnowledgeRetrieveRequest request) {
        return request != null
                && StringUtils.hasText(request.getQuery())
                && request.getScopeType() != null
                && request.getScopeId() != null
                && request.getScopeId() > 0;
    }

    private RetrievalContext loadContext(KnowledgeRetrieveRequest request) {
        Map<EntityType, Set<Long>> entityIds = new LinkedHashMap<>();
        List<KnowledgeGraphFactVO> graphFacts = new ArrayList<>();
        boolean graphUnavailable = false;

        switch (request.getScopeType()) {
            case SCHOOL -> {
                SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(request.getScopeId());
                if (detail == null) {
                    return new RetrievalContext(Collections.emptyMap(), Collections.emptyList(), false);
                }
                addEntity(entityIds, EntityType.SCHOOL, request.getScopeId());
                if (detail.getResources() != null) {
                    detail.getResources().stream()
                            .filter(item -> item != null && item.getResourceId() != null)
                            .forEach(item -> addEntity(entityIds, EntityType.RESOURCE, item.getResourceId()));
                }
                if (detail.getActivityPlans() != null) {
                    detail.getActivityPlans().stream()
                            .filter(plan -> plan != null && plan.getPlanId() != null)
                            .forEach(plan -> addEntity(entityIds, EntityType.ACTIVITY_PLAN, plan.getPlanId()));
                }
                GraphLoad graphLoad = loadSchoolGraphFacts(request);
                graphFacts.addAll(graphLoad.facts());
                graphUnavailable = graphLoad.unavailable();
            }
            case REGION -> {
                TownMapDetailVO detail = townMapService.getTownMapDetail(request.getScopeId());
                if (detail == null) {
                    return new RetrievalContext(Collections.emptyMap(), Collections.emptyList(), false);
                }
                addRegionEntities(entityIds, detail);
                GraphLoad graphLoad = loadRegionGraphFacts(request.getScopeId(), detail);
                graphFacts.addAll(graphLoad.facts());
                graphUnavailable = graphLoad.unavailable();
            }
            case RESOURCE -> {
                addEntity(entityIds, EntityType.RESOURCE, request.getScopeId());
                if (isRelationQuery(request.getQuery())) {
                    GraphLoad graphLoad = loadResourceGraphFacts(request.getScopeId());
                    graphFacts.addAll(graphLoad.facts());
                    graphUnavailable = graphLoad.unavailable();
                }
            }
        }
        return new RetrievalContext(entityIds, graphFacts, graphUnavailable);
    }

    private void addRegionEntities(Map<EntityType, Set<Long>> entityIds, TownMapDetailVO detail) {
        if (detail.getMarkers() != null) {
            for (MapResourceMarkerVO marker : detail.getMarkers()) {
                if (marker == null || marker.getId() == null) {
                    continue;
                }
                addEntity(entityIds, entityType(marker.getType()), marker.getId());
            }
        }
        if (detail.getHeroes() != null) {
            detail.getHeroes().stream()
                    .filter(item -> item != null && item.getHeroId() != null)
                    .forEach(item -> addEntity(entityIds, EntityType.HERO, item.getHeroId()));
        }
        if (detail.getStories() != null) {
            detail.getStories().stream()
                    .filter(item -> item != null && item.getStoryId() != null)
                    .forEach(item -> addEntity(entityIds, EntityType.STORY, item.getStoryId()));
        }
        if (detail.getEvents() != null) {
            detail.getEvents().stream()
                    .filter(item -> item != null && item.getEventId() != null)
                    .forEach(item -> addEntity(entityIds, EntityType.EVENT, item.getEventId()));
        }
    }

    private EntityType entityType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        for (EntityType type : EntityType.values()) {
            if (type.getValue().equalsIgnoreCase(value.trim())
                    || type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }

    private void addEntity(Map<EntityType, Set<Long>> entityIds, EntityType type, Long id) {
        if (type != null && id != null && id > 0) {
            entityIds.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add(id);
        }
    }

    private List<ScoredChunk> loadChunks(KnowledgeRetrieveRequest request,
                                         Map<EntityType, Set<Long>> entityIds) {
        if (entityIds.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = entityIds.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContentChunk> chunks = contentChunkMapper.selectList(new LambdaQueryWrapper<ContentChunk>()
                .in(ContentChunk::getEntityType, entityIds.keySet())
                .in(ContentChunk::getEntityId, ids)
                .orderByAsc(ContentChunk::getEntityType)
                .orderByAsc(ContentChunk::getEntityId)
                .orderByAsc(ContentChunk::getChunkIndex));

        Set<String> terms = retrievalTerms(request);
        return chunks.stream()
                .filter(chunk -> chunk != null
                        && entityIds.getOrDefault(chunk.getEntityType(), Collections.emptySet())
                        .contains(chunk.getEntityId()))
                .filter(chunk -> StringUtils.hasText(chunk.getChunkText()) || StringUtils.hasText(chunk.getChunkTitle()))
                .map(chunk -> new ScoredChunk(toChunk(chunk, score(chunk, terms), terms), score(chunk, terms)))
                .sorted(Comparator.comparing(ScoredChunk::score).reversed()
                        .thenComparing(item -> item.chunk().getChunkId(), Comparator.nullsLast(Long::compareTo)))
                .limit(MAX_CHUNKS)
                .collect(Collectors.toList());
    }

    private KnowledgeChunkVO toChunk(ContentChunk chunk, double score, Set<String> terms) {
        KnowledgeChunkVO vo = new KnowledgeChunkVO();
        vo.setCitationId("chunk:" + chunk.getChunkId());
        vo.setChunkId(chunk.getChunkId());
        vo.setTitle(cleanOrDefault(chunk.getChunkTitle(), "内容分块 " + chunk.getChunkId()));
        vo.setText(truncate(chunk.getChunkText(), MAX_TEXT_LENGTH));
        vo.setScore(score);
        vo.setRetrievalMethod(terms.isEmpty() ? "structured" : "keyword");
        vo.setEntityType(enumValue(chunk.getEntityType()));
        vo.setEntityId(chunk.getEntityId());
        vo.setSourceId(chunk.getSourceId());
        return vo;
    }

    private double score(ContentChunk chunk, Set<String> terms) {
        String title = normalize(chunk.getChunkTitle());
        String text = normalize(chunk.getChunkText());
        if (terms.isEmpty()) {
            return 0.2D;
        }
        double value = 0.15D;
        for (String term : terms) {
            if (title.contains(term)) {
                value += 0.18D;
            }
            if (text.contains(term)) {
                value += 0.1D;
            }
        }
        return Math.min(0.99D, value);
    }

    private Set<String> retrievalTerms(KnowledgeRetrieveRequest request) {
        Set<String> terms = new LinkedHashSet<>();
        addTerms(terms, request.getQuery());
        addTerms(terms, request.getGrade());
        addTerms(terms, request.getTheme());
        return terms;
    }

    private void addTerms(Set<String> terms, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        for (String item : TERM_SPLITTER.split(value.trim())) {
            String normalized = normalize(item);
            if (normalized.length() >= 2 && normalized.length() <= 20) {
                terms.add(normalized);
            }
        }
    }

    private Map<Long, DataSource> loadSources(Collection<Long> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return dataSourceMapper.selectBatchIds(sourceIds).stream()
                .filter(Objects::nonNull)
                .filter(source -> source.getSourceId() != null)
                .collect(Collectors.toMap(
                        DataSource::getSourceId,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
    }

    private List<KnowledgeCitationCandidateVO> buildChunkCandidates(List<KnowledgeChunkVO> chunks,
                                                                     Map<Long, DataSource> sources) {
        List<KnowledgeCitationCandidateVO> result = new ArrayList<>();
        for (KnowledgeChunkVO chunk : chunks) {
            KnowledgeCitationCandidateVO candidate = new KnowledgeCitationCandidateVO();
            candidate.setCitationId(chunk.getCitationId());
            candidate.setTitle(chunk.getTitle());
            candidate.setSourceType("content_chunk");
            candidate.setRelatedEntityType(chunk.getEntityType());
            candidate.setRelatedEntityId(chunk.getEntityId());
            candidate.setExcerpt(truncate(chunk.getText(), MAX_EXCERPT_LENGTH));
            DataSource source = sources.get(chunk.getSourceId());
            candidate.setUrl(source == null ? null : source.getBaseUrl());
            result.add(candidate);
        }
        return result;
    }

    private List<KnowledgeCitationCandidateVO> buildSourceCandidates(Map<EntityType, Set<Long>> entityIds,
                                                                      Map<Long, DataSource> sources) {
        Set<Long> ids = entityIds.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<EntitySourceRel> relations = entitySourceRelMapper.selectList(new LambdaQueryWrapper<EntitySourceRel>()
                .in(EntitySourceRel::getEntityType, entityIds.keySet())
                .in(EntitySourceRel::getEntityId, ids)
                .orderByDesc(EntitySourceRel::getCredibilityScore)
                .orderByAsc(EntitySourceRel::getRelId));
        Set<Long> relationSourceIds = relations.stream()
                .map(EntitySourceRel::getSourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, DataSource> sourceById = new LinkedHashMap<>(sources);
        loadSources(relationSourceIds).forEach(sourceById::putIfAbsent);
        List<KnowledgeCitationCandidateVO> result = new ArrayList<>();
        for (EntitySourceRel relation : relations) {
            if (relation == null || relation.getRelId() == null) {
                continue;
            }
            KnowledgeCitationCandidateVO candidate = new KnowledgeCitationCandidateVO();
            candidate.setCitationId("source-rel:" + relation.getRelId());
            DataSource source = sourceById.get(relation.getSourceId());
            candidate.setTitle(source == null
                    ? "来源记录 " + relation.getRelId()
                    : cleanOrDefault(source.getSourceName(), "来源记录 " + relation.getRelId()));
            candidate.setSourceType("entity_source");
            candidate.setRelatedEntityType(enumValue(relation.getEntityType()));
            candidate.setRelatedEntityId(relation.getEntityId());
            candidate.setExcerpt(truncate(relation.getSourceExcerpt(), MAX_EXCERPT_LENGTH));
            candidate.setUrl(StringUtils.hasText(relation.getSourceUrl())
                    ? relation.getSourceUrl()
                    : source == null ? null : source.getBaseUrl());
            result.add(candidate);
            if (result.size() >= MAX_CITATIONS) {
                break;
            }
        }
        return result;
    }

    private List<KnowledgeCitationCandidateVO> buildGraphCandidates(List<KnowledgeGraphFactVO> facts) {
        if (facts == null) {
            return Collections.emptyList();
        }
        return facts.stream()
                .filter(Objects::nonNull)
                .map(fact -> {
                    KnowledgeCitationCandidateVO candidate = new KnowledgeCitationCandidateVO();
                    candidate.setCitationId(fact.getCitationId());
                    candidate.setTitle("图谱关系事实");
                    candidate.setSourceType("graph_fact");
                    candidate.setRelatedEntityType(fact.getPredicate());
                    candidate.setRelatedEntityId(fact.getObjectId());
                    candidate.setExcerpt(truncate(fact.getText(), MAX_EXCERPT_LENGTH));
                    return candidate;
                })
                .collect(Collectors.toList());
    }

    private List<KnowledgeCitationCandidateVO> deduplicateCandidates(List<KnowledgeCitationCandidateVO> candidates) {
        Map<String, KnowledgeCitationCandidateVO> unique = new LinkedHashMap<>();
        for (KnowledgeCitationCandidateVO candidate : candidates) {
            if (candidate != null && StringUtils.hasText(candidate.getCitationId())) {
                unique.putIfAbsent(candidate.getCitationId(), candidate);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private GraphLoad loadSchoolGraphFacts(KnowledgeRetrieveRequest request) {
        List<KnowledgeGraphFactVO> facts = new ArrayList<>();
        boolean unavailable = false;
        try {
            String cypher = ""
                    + "MATCH (s:School {id: $schoolId})-[rel:SCHOOL_NEAR_RESOURCE]->(r:LocalEduResource) "
                    + "RETURN r.id AS resourceId, r.name AS resourceName, "
                    + "rel.educationThemeSummary AS theme, rel.distanceMeters AS distanceMeters "
                    + "ORDER BY coalesce(rel.priorityLevel, 999999), coalesce(rel.distanceMeters, 999999) "
                    + "LIMIT 8";
            Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
                    .bind(request.getScopeId()).to("schoolId")
                    .fetch()
                    .all();
            for (Map<String, Object> row : rows) {
                Long resourceId = longValue(row.get("resourceId"));
                if (resourceId == null) {
                    continue;
                }
                KnowledgeGraphFactVO fact = new KnowledgeGraphFactVO();
                fact.setCitationId("graph:school:" + request.getScopeId() + ":resource:" + resourceId);
                fact.setText(buildSchoolGraphText(row));
                fact.setSubjectId(request.getScopeId());
                fact.setPredicate("SCHOOL_NEAR_RESOURCE");
                fact.setObjectId(resourceId);
                facts.add(fact);
            }

            if (isRelationQuery(request.getQuery())) {
                facts.addAll(loadSchoolPathFacts(request.getScopeId()));
            }
        } catch (RuntimeException exception) {
            unavailable = true;
        }
        return new GraphLoad(deduplicateFacts(facts), unavailable);
    }

    private List<KnowledgeGraphFactVO> loadSchoolPathFacts(Long schoolId) {
        String cypher = ""
                + "MATCH p=(s:School {id: $schoolId})-[*1..3]-(target) "
                + "WHERE target:Hero OR target:Event OR target:Site OR target:Memorial "
                + "OR target:Story OR target:LocalEduResource "
                + "RETURN DISTINCT labels(target) AS targetLabels, target.id AS targetId, "
                + "coalesce(target.name, target.title) AS targetName "
                + "LIMIT 8";
        Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
                .bind(schoolId).to("schoolId")
                .fetch()
                .all();
        List<KnowledgeGraphFactVO> facts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long targetId = longValue(row.get("targetId"));
            String targetName = stringValue(row.get("targetName"));
            if (targetId == null || !StringUtils.hasText(targetName)) {
                continue;
            }
            String label = firstLabel(row.get("targetLabels"));
            KnowledgeGraphFactVO fact = new KnowledgeGraphFactVO();
            fact.setCitationId("graph:school:" + schoolId + ":path:" + label + ":" + targetId);
            fact.setText("学校与" + label + "“" + targetName + "”存在一至三跳图谱关联。");
            fact.setSubjectId(schoolId);
            fact.setPredicate("SCHOOL_GRAPH_PATH");
            fact.setObjectId(targetId);
            facts.add(fact);
        }
        return facts;
    }

    private GraphLoad loadRegionGraphFacts(Long regionId, TownMapDetailVO detail) {
        if (!Boolean.TRUE.equals(detail.getGraphAvailable())) {
            return new GraphLoad(Collections.emptyList(), true);
        }
        List<KnowledgeGraphFactVO> facts = new ArrayList<>();
        if (detail.getMarkers() != null) {
            for (MapResourceMarkerVO marker : detail.getMarkers()) {
                if (marker == null || marker.getId() == null || !StringUtils.hasText(marker.getName())) {
                    continue;
                }
                facts.add(regionFact(regionId, marker.getType(), marker.getId(), marker.getName(), "REGION_CONTAINS"));
            }
        }
        if (detail.getHeroes() != null) {
            for (HeroSummaryVO hero : detail.getHeroes()) {
                if (hero != null && hero.getHeroId() != null && StringUtils.hasText(hero.getHeroName())) {
                    facts.add(regionFact(regionId, "hero", hero.getHeroId(), hero.getHeroName(), "REGION_HAS_HERO"));
                }
            }
        }
        if (detail.getStories() != null) {
            for (StorySummaryVO story : detail.getStories()) {
                if (story != null && story.getStoryId() != null && StringUtils.hasText(story.getStoryTitle())) {
                    facts.add(regionFact(regionId, "story", story.getStoryId(), story.getStoryTitle(), "REGION_HAS_STORY"));
                }
            }
        }
        if (detail.getEvents() != null) {
            for (EventSummaryVO event : detail.getEvents()) {
                if (event != null && event.getEventId() != null && StringUtils.hasText(event.getEventName())) {
                    facts.add(regionFact(regionId, "event", event.getEventId(), event.getEventName(), "REGION_HAS_EVENT"));
                }
            }
        }
        return new GraphLoad(limitList(deduplicateFacts(facts), MAX_GRAPH_FACTS), false);
    }

    private KnowledgeGraphFactVO regionFact(Long regionId,
                                             String type,
                                             Long objectId,
                                             String name,
                                             String predicate) {
        String normalizedType = StringUtils.hasText(type) ? type.toLowerCase(Locale.ROOT) : "entity";
        KnowledgeGraphFactVO fact = new KnowledgeGraphFactVO();
        fact.setCitationId("graph:region:" + regionId + ":" + normalizedType + ":" + objectId);
        fact.setText("区域“" + regionId + "”关联" + normalizedType + "“" + name + "”。");
        fact.setSubjectId(regionId);
        fact.setPredicate(predicate);
        fact.setObjectId(objectId);
        return fact;
    }

    private GraphLoad loadResourceGraphFacts(Long resourceId) {
        try {
            String cypher = ""
                    + "MATCH p=(r:LocalEduResource {id: $resourceId})-[*1..2]-(target) "
                    + "WHERE target:School OR target:ActivityPlan OR target:Region OR target:Tag "
                    + "RETURN DISTINCT labels(target) AS targetLabels, target.id AS targetId, "
                    + "coalesce(target.name, target.title) AS targetName LIMIT 8";
            Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
                    .bind(resourceId).to("resourceId")
                    .fetch()
                    .all();
            List<KnowledgeGraphFactVO> facts = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Long targetId = longValue(row.get("targetId"));
                String targetName = stringValue(row.get("targetName"));
                if (targetId == null || !StringUtils.hasText(targetName)) {
                    continue;
                }
                String label = firstLabel(row.get("targetLabels"));
                KnowledgeGraphFactVO fact = new KnowledgeGraphFactVO();
                fact.setCitationId("graph:resource:" + resourceId + ":path:" + label + ":" + targetId);
                fact.setText("资源与" + label + "“" + targetName + "”存在一至两跳图谱关联。");
                fact.setSubjectId(resourceId);
                fact.setPredicate("RESOURCE_GRAPH_PATH");
                fact.setObjectId(targetId);
                facts.add(fact);
            }
            return new GraphLoad(deduplicateFacts(facts), false);
        } catch (RuntimeException exception) {
            return new GraphLoad(Collections.emptyList(), true);
        }
    }

    private boolean isRelationQuery(String query) {
        String normalized = normalize(query);
        return normalized.contains("关系") || normalized.contains("关联") || normalized.contains("联系");
    }

    private List<KnowledgeGraphFactVO> deduplicateFacts(List<KnowledgeGraphFactVO> facts) {
        Map<String, KnowledgeGraphFactVO> unique = new LinkedHashMap<>();
        for (KnowledgeGraphFactVO fact : facts) {
            if (fact != null && StringUtils.hasText(fact.getCitationId())) {
                unique.putIfAbsent(fact.getCitationId(), fact);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private KnowledgeRetrievalStatus resolveStatus(boolean hasEvidence, boolean graphUnavailable) {
        if (graphUnavailable) {
            return KnowledgeRetrievalStatus.DEGRADED;
        }
        return hasEvidence ? KnowledgeRetrievalStatus.OK : KnowledgeRetrievalStatus.EMPTY;
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private <T> List<T> limitList(List<T> values, int limit) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        return values.stream().limit(limit).collect(Collectors.toCollection(ArrayList::new));
    }

    private String buildSchoolGraphText(Map<String, Object> row) {
        List<String> parts = new ArrayList<>();
        String resourceName = stringValue(row.get("resourceName"));
        String theme = stringValue(row.get("theme"));
        String distance = stringValue(row.get("distanceMeters"));
        if (StringUtils.hasText(resourceName)) {
            parts.add("周边资源：" + resourceName);
        }
        if (StringUtils.hasText(theme)) {
            parts.add("教育主题：" + theme);
        }
        if (StringUtils.hasText(distance)) {
            parts.add("距离：" + distance + "米");
        }
        return parts.isEmpty() ? "学校与本地教育资源存在图谱关联。" : String.join("；", parts);
    }

    private String firstLabel(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return "Entity";
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (!StringUtils.hasText(value == null ? null : String.valueOf(value))) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String enumValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof EntityType entityType) {
            return entityType.getValue();
        }
        return String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null ? "" : value
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String cleanOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private record ScoredChunk(KnowledgeChunkVO chunk, double score) {
    }

    private record GraphLoad(List<KnowledgeGraphFactVO> facts, boolean unavailable) {
    }

    private record RetrievalContext(Map<EntityType, Set<Long>> entityIds,
                                    List<KnowledgeGraphFactVO> graphFacts,
                                    boolean graphUnavailable) {
    }
}
