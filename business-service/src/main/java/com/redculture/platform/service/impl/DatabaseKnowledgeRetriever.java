package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.config.RagProperties;
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
import com.redculture.platform.service.rag.ChunkVectorStore;
import com.redculture.platform.service.rag.EmbeddingClient;
import com.redculture.platform.service.rag.RagEntityMetadata;
import com.redculture.platform.service.rag.RagEntityMetadataService;
import com.redculture.platform.service.rag.VectorSearchCandidate;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.EventSummaryVO;
import com.redculture.platform.vo.HeroSummaryVO;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.MapResourceMarkerVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolResourceItemVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.StorySummaryVO;
import com.redculture.platform.vo.TeachingActivityPlanVO;
import com.redculture.platform.vo.TownMapDetailVO;
import com.redculture.platform.vo.ai.KnowledgeChunkVO;
import com.redculture.platform.vo.ai.KnowledgeCitationCandidateVO;
import com.redculture.platform.vo.ai.KnowledgeGraphFactVO;
import com.redculture.platform.vo.ai.KnowledgeGraphPathEdgeVO;
import com.redculture.platform.vo.ai.KnowledgeRetrievalCandidateTraceVO;
import com.redculture.platform.vo.ai.KnowledgeRetrievalTraceVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.stream.Stream;

/**
 * Retrieves approved evidence with deterministic planning and scoped hybrid recall.
 * Agent and teaching-plan generation both consume this implementation through
 * {@link KnowledgeRetriever}; Dense and MySQL Lexical candidates are fused by RRF.
 */
@Component
@Profile("!mock-rag")
public class DatabaseKnowledgeRetriever implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(DatabaseKnowledgeRetriever.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 8;
    private static final int MAX_CHUNKS = 8;
    private static final int MAX_CANDIDATES = 32;
    private static final int MAX_CITATIONS = 16;
    private static final int MAX_GRAPH_FACTS = 8;
    private static final int MAX_TEXT_LENGTH = 700;
    private static final int MAX_EXCERPT_LENGTH = 180;
    private static final Pattern FULLTEXT_SPECIAL_CHARACTERS =
            Pattern.compile("[+\\-~*<>()\\[\\]@\"']+");
    private static final String RETRIEVAL_METHOD_DENSE = "dense";
    private static final String RETRIEVAL_METHOD_LEXICAL = "lexical";
    private static final String RETRIEVAL_METHOD_RRF = "rrf";
    private static final String RETRIEVAL_METHOD_HYBRID_RRF = "hybrid-rrf";
    private static final String RETRIEVAL_METHOD_HYBRID_RERANK =
            "hybrid-rrf+heuristic-rerank";
    private static final String RETRIEVAL_METHOD_DENSE_RERANK =
            "dense+heuristic-rerank";
    private static final String RETRIEVAL_METHOD_LEXICAL_RERANK =
            "lexical+heuristic-rerank";
    private static final String RETRIEVAL_METHOD_GRAPH_RERANK =
            "knowledge-graph+heuristic-rerank";
    private static final String METHOD_HEURISTIC_RERANK = "heuristic-rerank";
    private static final String EVIDENCE_TYPE_CHUNK = "chunk";
    private static final String EVIDENCE_TYPE_GRAPH = "graph_fact";
    private static final String GRAPH_STATUS_SKIPPED = "skipped";
    private static final String GRAPH_STATUS_OK = "ok";
    private static final String GRAPH_STATUS_EMPTY = "empty";
    private static final String GRAPH_STATUS_FAILED = "failed";
    private static final Set<String> GRAPH_EXPANSION_TYPES = Set.of(
            "resource", "site", "hero", "event", "memorial", "story"
    );
    private static final String GRAPH_RELATIONSHIP_WHITELIST = "["
            + "'SCHOOL_NEAR_RESOURCE','LOCATED_IN','RELATED_TO_REGION',"
            + "'OCCURRED_AT','MEMORIALIZED_AT','RELATED_TO','BORN_IN','FOUGHT_IN','VISITED',"
            + "'PARTICIPATED_IN','LED','WITNESSED','MARTYR_IN','COMMEMORATES','EXHIBITS',"
            + "'DISPLAYS','LOCATED_AT']";
    private static final List<String> DOMAIN_THEME_KEYWORDS = List.of(
            "理想信念", "革命传统", "爱国主义", "红色文化", "红色教育", "党史",
            "国防教育", "志愿服务", "敬老", "劳动教育", "社会责任", "乡土文化",
            "廉洁教育", "生态文明", "法治教育", "生命安全", "团结协作"
    );

    private final ContentChunkMapper contentChunkMapper;
    private final EntitySourceRelMapper entitySourceRelMapper;
    private final DataSourceMapper dataSourceMapper;
    private final SchoolMapService schoolMapService;
    private final TownMapService townMapService;
    private final Neo4jClient neo4jClient;
    private final RagProperties ragProperties;
    private final EmbeddingClient embeddingClient;
    private final ChunkVectorStore vectorStore;
    private final RagEntityMetadataService entityMetadataService;

    @Autowired
    public DatabaseKnowledgeRetriever(ContentChunkMapper contentChunkMapper,
                                     EntitySourceRelMapper entitySourceRelMapper,
                                     DataSourceMapper dataSourceMapper,
                                     SchoolMapService schoolMapService,
                                     TownMapService townMapService,
                                     Neo4jClient neo4jClient,
                                     RagProperties ragProperties,
                                     EmbeddingClient embeddingClient,
                                     ChunkVectorStore vectorStore,
                                     RagEntityMetadataService entityMetadataService) {
        this.contentChunkMapper = contentChunkMapper;
        this.entitySourceRelMapper = entitySourceRelMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.schoolMapService = schoolMapService;
        this.townMapService = townMapService;
        this.neo4jClient = neo4jClient;
        this.ragProperties = ragProperties;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.entityMetadataService = entityMetadataService;
    }

    public DatabaseKnowledgeRetriever(ContentChunkMapper contentChunkMapper,
                                     EntitySourceRelMapper entitySourceRelMapper,
                                     DataSourceMapper dataSourceMapper,
                                     SchoolMapService schoolMapService,
                                     TownMapService townMapService,
                                     Neo4jClient neo4jClient,
                                     RagProperties ragProperties,
                                     EmbeddingClient embeddingClient,
                                     ChunkVectorStore vectorStore) {
        this(contentChunkMapper, entitySourceRelMapper, dataSourceMapper, schoolMapService, townMapService,
                neo4jClient, ragProperties, embeddingClient, vectorStore, null);
    }

    @Override
    public KnowledgeRetrieveResult retrieve(KnowledgeRetrieveRequest request) {
        if (!validRequest(request)) {
            return KnowledgeRetrieveResult.empty();
        }

        try {
            RetrievalContext context = loadContext(request);
            RetrievalPlan plan = buildPlan(request, context);
            ChunkLoad chunkLoad = loadChunks(plan);
            SourceContext sourceContext = loadSourceContext(plan, context, chunkLoad.chunks());
            RerankLoad rerankLoad = rerank(plan, context, chunkLoad, sourceContext);
            List<KnowledgeCitationCandidateVO> candidates = new ArrayList<>(rerankLoad.jointCandidates());
            candidates.addAll(buildSourceCandidates(sourceContext));
            candidates = limitList(deduplicateCandidates(candidates), MAX_CITATIONS);
            List<String> retrievalMethods = new ArrayList<>(chunkLoad.retrievalMethods());
            if (!rerankLoad.evidences().isEmpty()) {
                retrievalMethods.add(METHOD_HEURISTIC_RERANK);
            }
            if (!rerankLoad.graphFacts().isEmpty()) {
                retrievalMethods.add("knowledge-graph");
            }

            KnowledgeRetrieveResult result = new KnowledgeRetrieveResult();
            result.setChunks(rerankLoad.chunks());
            result.setGraphFacts(rerankLoad.graphFacts());
            result.setCitationCandidates(candidates);
            result.setRetrievalMethods(retrievalMethods);
            result.setRetrievalStatus(resolveStatus(
                    !rerankLoad.evidences().isEmpty() || !candidates.isEmpty(),
                    GRAPH_STATUS_FAILED.equals(context.graphStatus()) || chunkLoad.degraded()
            ));
            result.refreshRetrievalMethods();
            result.setRetrievalTrace(buildRetrievalTrace(plan, context, chunkLoad, rerankLoad,
                    result.getRetrievalMethods()));
            return result;
        } catch (RuntimeException exception) {
            log.warn("Knowledge retrieval failed", exception);
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
        List<EntityHint> entityHints = new ArrayList<>();
        List<KnowledgeGraphFactVO> graphFacts = new ArrayList<>();
        Set<Long> approvedSchoolResourceIds = new LinkedHashSet<>();
        AgentIntent intent = resolveIntent(request);
        boolean needGraph = needsGraph(intent);
        String graphStatus = needGraph ? GRAPH_STATUS_EMPTY : GRAPH_STATUS_SKIPPED;

        switch (request.getScopeType()) {
            case SCHOOL -> {
                SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(request.getScopeId());
                if (detail == null) {
                    return new RetrievalContext(Collections.emptyMap(), Collections.emptyList(),
                            Collections.emptyList(), graphStatus, Collections.emptyMap());
                }
                addEntity(entityIds, EntityType.SCHOOL, request.getScopeId());
                SchoolSummaryVO school = detail.getSchool();
                addEntityHint(entityHints, EntityType.SCHOOL, request.getScopeId(),
                        school == null ? null : school.getSchoolName(),
                        school == null ? null : school.getSchoolName());
                if (detail.getResources() != null) {
                    for (SchoolResourceItemVO item : detail.getResources()) {
                        if (item == null || item.getResourceId() == null) {
                            continue;
                        }
                        addEntity(entityIds, EntityType.RESOURCE, item.getResourceId());
                        approvedSchoolResourceIds.add(item.getResourceId());
                        LocalEduResourceSummaryVO resource = item.getResource();
                        addEntityHint(entityHints, EntityType.RESOURCE, item.getResourceId(),
                                resource == null ? null : resource.getResourceName(),
                                resource == null ? null : resource.getResourceCode());
                    }
                }
                if (detail.getActivityPlans() != null) {
                    for (TeachingActivityPlanVO plan : detail.getActivityPlans()) {
                        if (plan == null || plan.getPlanId() == null) {
                            continue;
                        }
                        addEntity(entityIds, EntityType.ACTIVITY_PLAN, plan.getPlanId());
                        addEntityHint(entityHints, EntityType.ACTIVITY_PLAN, plan.getPlanId(),
                                plan.getPlanCode(), plan.getTheme());
                    }
                }
                if (needGraph) {
                    GraphLoad graphLoad = loadSchoolGraphFacts(request);
                    graphFacts.addAll(graphLoad.facts());
                    graphStatus = graphStatus(graphLoad);
                }
            }
            case REGION -> {
                TownMapDetailVO detail = townMapService.getTownMapDetail(request.getScopeId());
                if (detail == null) {
                    return new RetrievalContext(Collections.emptyMap(), Collections.emptyList(),
                            Collections.emptyList(), graphStatus, Collections.emptyMap());
                }
                addEntityHint(entityHints, null, request.getScopeId(), detail.getRegionName());
                addRegionEntities(entityIds, entityHints, detail);
                if (needGraph) {
                    GraphLoad graphLoad = loadRegionGraphFacts(request.getScopeId(), detail);
                    graphFacts.addAll(graphLoad.facts());
                    graphStatus = graphStatus(graphLoad);
                }
            }
            case RESOURCE -> {
                addEntity(entityIds, EntityType.RESOURCE, request.getScopeId());
                addEntityHint(entityHints, EntityType.RESOURCE, request.getScopeId());
                if (needGraph) {
                    GraphLoad graphLoad = loadResourceGraphFacts(request.getScopeId());
                    graphFacts.addAll(graphLoad.facts());
                    graphStatus = graphStatus(graphLoad);
                }
            }
        }
        RetrievalContext context = validateAndExpandContext(intent, entityIds, entityHints, graphFacts,
                approvedSchoolResourceIds, graphStatus);
        context = constrainSchoolRelationContext(request, intent, context);
        return constrainExplicitUnknownLookup(request, context);
    }

    private RetrievalContext validateAndExpandContext(AgentIntent intent,
                                                       Map<EntityType, Set<Long>> entityIds,
                                                       List<EntityHint> entityHints,
                                                       List<KnowledgeGraphFactVO> graphFacts,
                                                       Set<Long> approvedSchoolResourceIds,
                                                       String graphStatus) {
        Map<EntityType, Set<Long>> expanded = copyEntityIds(entityIds);
        int expansionLimit = intent == AgentIntent.RELATION_QUERY
                ? Math.max(1, ragProperties.getRelationExpansionLimit())
                : Math.max(1, ragProperties.getGraphCandidateLimit());
        int expandedCount = 0;
        for (KnowledgeGraphFactVO fact : graphFacts) {
            if (fact == null || fact.getObjectId() == null || expandedCount >= expansionLimit) {
                continue;
            }
            EntityType type = graphEntityType(fact.getObjectType());
            boolean nearbyResource = intent == AgentIntent.NEARBY_RESOURCE
                    && type == EntityType.RESOURCE
                    && "SCHOOL_NEAR_RESOURCE".equals(fact.getPredicate());
            boolean relationTarget = intent == AgentIntent.RELATION_QUERY
                    && type != null && GRAPH_EXPANSION_TYPES.contains(type.getValue());
            if (nearbyResource && entityMetadataService != null
                    && !approvedSchoolResourceIds.contains(fact.getObjectId())) {
                continue;
            }
            if (nearbyResource || relationTarget) {
                addEntity(expanded, type, fact.getObjectId());
                expandedCount++;
            }
        }

        Map<EntityType, Set<Long>> validationIds = copyEntityIds(expanded);
        graphFacts.forEach(fact -> addGraphFactEntityIds(validationIds, fact));
        Map<String, RagEntityMetadata> metadata = entityMetadataService == null
                ? Collections.emptyMap()
                : entityMetadataService.loadApproved(validationIds);
        if (entityMetadataService != null) {
            expanded = filterApprovedEntityIds(expanded, metadata);
            metadata.values().forEach(item -> addEntityHint(entityHints, item.entityType(), item.entityId(),
                    Stream.concat(Stream.of(item.canonicalName()), item.aliases().stream())
                            .filter(StringUtils::hasText).toArray(String[]::new)));
        }

        List<KnowledgeGraphFactVO> approvedFacts = graphFacts.stream()
                .filter(Objects::nonNull)
                .filter(fact -> approvedGraphFact(intent, fact, approvedSchoolResourceIds, metadata))
                .peek(fact -> enrichGraphFactNames(fact, metadata))
                .limit(Math.max(1, ragProperties.getGraphCandidateLimit()))
                .collect(Collectors.toCollection(ArrayList::new));
        String resolvedGraphStatus = GRAPH_STATUS_FAILED.equals(graphStatus)
                ? GRAPH_STATUS_FAILED
                : GRAPH_STATUS_SKIPPED.equals(graphStatus)
                ? GRAPH_STATUS_SKIPPED
                : approvedFacts.isEmpty() ? GRAPH_STATUS_EMPTY : GRAPH_STATUS_OK;
        return new RetrievalContext(freezeEntityIds(expanded), List.copyOf(entityHints),
                List.copyOf(approvedFacts), resolvedGraphStatus, metadata);
    }

    private RetrievalContext constrainSchoolRelationContext(KnowledgeRetrieveRequest request,
                                                             AgentIntent intent,
                                                             RetrievalContext context) {
        if (intent != AgentIntent.RELATION_QUERY
                || request.getScopeType() != KnowledgeScopeType.SCHOOL) {
            return context;
        }

        Map<EntityType, Set<Long>> matchedTargets = new LinkedHashMap<>();
        context.entityHints().stream()
                .filter(hint -> hint.entityType() != null && hint.entityType() != EntityType.SCHOOL)
                .filter(hint -> matchesEntity(request.getQuery(), hint))
                .forEach(hint -> addEntity(matchedTargets, hint.entityType(), hint.entityId()));
        Set<String> targetKeys = matchedTargets.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(id -> entityKey(entry.getKey(), id)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<KnowledgeGraphFactVO> facts = context.graphFacts().stream()
                .filter(fact -> graphFactReferencesAny(fact, targetKeys))
                .toList();
        String graphStatus = GRAPH_STATUS_FAILED.equals(context.graphStatus())
                ? GRAPH_STATUS_FAILED
                : facts.isEmpty() ? GRAPH_STATUS_EMPTY : GRAPH_STATUS_OK;
        return new RetrievalContext(freezeEntityIds(matchedTargets), context.entityHints(),
                List.copyOf(facts), graphStatus, context.entityMetadata());
    }

    private RetrievalContext constrainExplicitUnknownLookup(KnowledgeRetrieveRequest request,
                                                             RetrievalContext context) {
        String query = normalize(request.getQuery());
        if (resolveIntent(request) != AgentIntent.NEARBY_RESOURCE
                || request.getScopeType() != KnowledgeScopeType.SCHOOL
                || !containsAny(query, "不存在", "虚构", "杜撰")
                || context.entityHints().stream().anyMatch(hint -> matchesEntity(request.getQuery(), hint))) {
            return context;
        }
        String graphStatus = GRAPH_STATUS_FAILED.equals(context.graphStatus())
                ? GRAPH_STATUS_FAILED : GRAPH_STATUS_EMPTY;
        return new RetrievalContext(Collections.emptyMap(), context.entityHints(),
                Collections.emptyList(), graphStatus, context.entityMetadata());
    }

    private void addGraphFactEntityIds(Map<EntityType, Set<Long>> ids, KnowledgeGraphFactVO fact) {
        if (fact == null) {
            return;
        }
        addEntity(ids, graphEntityType(fact.getSubjectType()), fact.getSubjectId());
        addEntity(ids, graphEntityType(fact.getObjectType()), fact.getObjectId());
        if (fact.getPathEdges() == null) {
            return;
        }
        fact.getPathEdges().stream().filter(Objects::nonNull).forEach(edge -> {
            addEntity(ids, graphEntityType(edge.getFromType()), edge.getFromId());
            addEntity(ids, graphEntityType(edge.getToType()), edge.getToId());
        });
    }

    private boolean graphFactReferencesAny(KnowledgeGraphFactVO fact, Set<String> targetKeys) {
        if (fact == null || targetKeys == null || targetKeys.isEmpty()) {
            return false;
        }
        if (targetKeys.contains(graphEntityKey(fact.getSubjectType(), fact.getSubjectId()))
                || targetKeys.contains(graphEntityKey(fact.getObjectType(), fact.getObjectId()))) {
            return true;
        }
        return fact.getPathEdges() != null && fact.getPathEdges().stream()
                .filter(Objects::nonNull)
                .anyMatch(edge -> targetKeys.contains(graphEntityKey(edge.getFromType(), edge.getFromId()))
                        || targetKeys.contains(graphEntityKey(edge.getToType(), edge.getToId())));
    }

    private String graphEntityKey(String typeValue, Long id) {
        EntityType type = graphEntityType(typeValue);
        return type == null || id == null ? "" : entityKey(type, id);
    }

    private boolean approvedGraphFact(AgentIntent intent,
                                      KnowledgeGraphFactVO fact,
                                      Set<Long> approvedSchoolResourceIds,
                                      Map<String, RagEntityMetadata> metadata) {
        if (intent == AgentIntent.NEARBY_RESOURCE) {
            if (!"SCHOOL_NEAR_RESOURCE".equals(fact.getPredicate())
                    || graphEntityType(fact.getObjectType()) != EntityType.RESOURCE) {
                return false;
            }
            if (entityMetadataService != null && !approvedSchoolResourceIds.contains(fact.getObjectId())) {
                return false;
            }
        }
        if (entityMetadataService == null) {
            return true;
        }
        Map<EntityType, Set<Long>> factIds = new LinkedHashMap<>();
        addGraphFactEntityIds(factIds, fact);
        return factIds.entrySet().stream().allMatch(entry -> entry.getValue().stream()
                .allMatch(id -> metadata.containsKey(entityKey(entry.getKey(), id))));
    }

    private Map<EntityType, Set<Long>> filterApprovedEntityIds(Map<EntityType, Set<Long>> entityIds,
                                                               Map<String, RagEntityMetadata> metadata) {
        Map<EntityType, Set<Long>> result = new LinkedHashMap<>();
        entityIds.forEach((type, ids) -> ids.stream()
                .filter(id -> metadata.containsKey(entityKey(type, id)))
                .forEach(id -> addEntity(result, type, id)));
        return result;
    }

    private void enrichGraphFactNames(KnowledgeGraphFactVO fact,
                                      Map<String, RagEntityMetadata> metadata) {
        EntityType subjectType = graphEntityType(fact.getSubjectType());
        EntityType objectType = graphEntityType(fact.getObjectType());
        RagEntityMetadata subject = metadata.get(entityKey(subjectType, fact.getSubjectId()));
        RagEntityMetadata object = metadata.get(entityKey(objectType, fact.getObjectId()));
        if (subject != null && !StringUtils.hasText(fact.getSubjectName())) {
            fact.setSubjectName(subject.canonicalName());
        }
        if (object != null && !StringUtils.hasText(fact.getObjectName())) {
            fact.setObjectName(object.canonicalName());
        }
    }

    private String graphStatus(GraphLoad graphLoad) {
        if (graphLoad.unavailable()) {
            return GRAPH_STATUS_FAILED;
        }
        return graphLoad.facts().isEmpty() ? GRAPH_STATUS_EMPTY : GRAPH_STATUS_OK;
    }

    private void addRegionEntities(Map<EntityType, Set<Long>> entityIds,
                                   List<EntityHint> entityHints,
                                   TownMapDetailVO detail) {
        if (detail.getMarkers() != null) {
            for (MapResourceMarkerVO marker : detail.getMarkers()) {
                if (marker == null || marker.getId() == null) {
                    continue;
                }
                EntityType type = entityType(marker.getType());
                addEntity(entityIds, type, marker.getId());
                addEntityHint(entityHints, type, marker.getId(), marker.getName());
            }
        }
        if (detail.getHeroes() != null) {
            detail.getHeroes().stream()
                    .filter(item -> item != null && item.getHeroId() != null)
                    .forEach(item -> {
                        addEntity(entityIds, EntityType.HERO, item.getHeroId());
                        addEntityHint(entityHints, EntityType.HERO, item.getHeroId(), item.getHeroName());
                    });
        }
        if (detail.getStories() != null) {
            detail.getStories().stream()
                    .filter(item -> item != null && item.getStoryId() != null)
                    .forEach(item -> {
                        addEntity(entityIds, EntityType.STORY, item.getStoryId());
                        addEntityHint(entityHints, EntityType.STORY, item.getStoryId(), item.getStoryTitle());
                    });
        }
        if (detail.getEvents() != null) {
            detail.getEvents().stream()
                    .filter(item -> item != null && item.getEventId() != null)
                    .forEach(item -> {
                        addEntity(entityIds, EntityType.EVENT, item.getEventId());
                        addEntityHint(entityHints, EntityType.EVENT, item.getEventId(), item.getEventName());
                    });
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

    private void addGraphResourceEntities(Map<EntityType, Set<Long>> entityIds,
                                          List<KnowledgeGraphFactVO> graphFacts) {
        if (graphFacts == null) {
            return;
        }
        graphFacts.stream()
                .filter(Objects::nonNull)
                .filter(fact -> "SCHOOL_NEAR_RESOURCE".equals(fact.getPredicate()))
                .map(KnowledgeGraphFactVO::getObjectId)
                .filter(Objects::nonNull)
                .forEach(resourceId -> addEntity(entityIds, EntityType.RESOURCE, resourceId));
    }

    private RetrievalPlan buildPlan(KnowledgeRetrieveRequest request, RetrievalContext context) {
        AgentIntent intent = resolveIntent(request);
        List<EntityHint> matchedHints = context.entityHints().stream()
                .filter(hint -> matchesEntity(request.getQuery(), hint))
                .collect(Collectors.toCollection(ArrayList::new));

        Map<EntityType, Set<Long>> scopedEntityIds = copyEntityIds(context.entityIds());
        Set<Long> matchedResourceIds = matchedHints.stream()
                .filter(hint -> hint.entityType() == EntityType.RESOURCE)
                .map(EntityHint::entityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (matchedResourceIds.size() == 1) {
            scopedEntityIds = new LinkedHashMap<>();
            scopedEntityIds.put(EntityType.RESOURCE, new LinkedHashSet<>(matchedResourceIds));
        }

        List<String> entityNames = matchedHints.stream()
                .map(EntityHint::canonicalName)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        if (entityNames.isEmpty()) {
            context.entityHints().stream()
                    .filter(hint -> hint.entityType() == null
                            || hint.entityType() == scopeEntityType(request.getScopeType()))
                    .map(EntityHint::canonicalName)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .limit(3)
                    .forEach(entityNames::add);
        }

        String searchQuery = Stream.concat(
                        Stream.of(request.getQuery()),
                        Stream.concat(entityNames.stream(), Stream.of(request.getGrade(), request.getTheme()))
                )
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(" "));
        int topK = normalizeTopK(request.getTopK());
        int multiplier = Math.max(1, ragProperties.getCandidateMultiplier());
        int candidateLimit = Math.min(MAX_CANDIDATES, topK * multiplier);
        Set<String> entityKeys = scopedEntityIds.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(id -> entry.getKey().getValue() + ":" + id))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new RetrievalPlan(
                request.getQuery().trim(),
                searchQuery,
                intent,
                request.getScopeType(),
                request.getScopeId(),
                request.getGrade(),
                request.getTheme(),
                Set.copyOf(entityKeys),
                freezeEntityIds(scopedEntityIds),
                topK,
                candidateLimit,
                ragProperties.isEnabled(),
                true,
                needsGraph(intent)
        );
    }

    private boolean matchesEntity(String query, EntityHint hint) {
        if (!StringUtils.hasText(query) || hint == null || hint.aliases().isEmpty()) {
            return false;
        }
        String normalizedQuery = normalize(query);
        return hint.aliases().stream()
                .map(this::normalize)
                .filter(alias -> !alias.isEmpty())
                .anyMatch(normalizedQuery::contains);
    }

    private EntityType scopeEntityType(KnowledgeScopeType scopeType) {
        if (scopeType == KnowledgeScopeType.SCHOOL) {
            return EntityType.SCHOOL;
        }
        if (scopeType == KnowledgeScopeType.RESOURCE) {
            return EntityType.RESOURCE;
        }
        return null;
    }

    private AgentIntent resolveIntent(KnowledgeRetrieveRequest request) {
        if (request != null && StringUtils.hasText(request.getIntent())) {
            try {
                AgentIntent provided = AgentIntent.valueOf(request.getIntent().trim().toUpperCase(Locale.ROOT));
                return provided;
            } catch (IllegalArgumentException ignored) {
                // Fall through to the deterministic keyword recognizer.
            }
        }
        String normalized = normalize(request == null ? null : request.getQuery());
        if (containsAny(normalized, "关系", "关联", "联系", "教学关联")) {
            return AgentIntent.RELATION_QUERY;
        }
        if (containsAny(normalized, "附近", "周边", "有哪些资源", "资源推荐", "可用资源")) {
            return AgentIntent.NEARBY_RESOURCE;
        }
        if (containsAny(normalized, "怎样", "如何", "怎么开展", "怎么设计", "怎么利用",
                "开展", "设计", "课堂", "课程", "活动", "实践", "志愿", "思政课", "教学建议")) {
            return AgentIntent.TEACHING_SUGGESTION;
        }
        if (containsAny(normalized, "介绍", "是什么", "详情", "教育价值", "教育意义", "解释", "适合什么年级")) {
            return AgentIntent.RESOURCE_EXPLANATION;
        }
        return AgentIntent.UNKNOWN;
    }

    private boolean containsAny(String value, String... candidates) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean needsGraph(AgentIntent intent) {
        return intent == AgentIntent.NEARBY_RESOURCE || intent == AgentIntent.RELATION_QUERY;
    }

    private void addEntityHint(List<EntityHint> entityHints,
                               EntityType type,
                               Long id,
                               String... names) {
        if (entityHints == null || id == null || id <= 0) {
            return;
        }
        List<String> aliases = Stream.of(names)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        if (aliases.isEmpty()) {
            return;
        }
        entityHints.add(new EntityHint(type, id, aliases.get(0), List.copyOf(aliases)));
    }

    private Map<EntityType, Set<Long>> copyEntityIds(Map<EntityType, Set<Long>> entityIds) {
        Map<EntityType, Set<Long>> copy = new LinkedHashMap<>();
        if (entityIds == null || entityIds.isEmpty()) {
            return copy;
        }
        entityIds.forEach((type, ids) -> {
            if (type != null && ids != null && !ids.isEmpty()) {
                copy.put(type, new LinkedHashSet<>(ids));
            }
        });
        return copy;
    }

    private Map<EntityType, Set<Long>> freezeEntityIds(Map<EntityType, Set<Long>> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<EntityType, Set<Long>> frozen = new LinkedHashMap<>();
        entityIds.forEach((type, ids) -> {
            if (type != null && ids != null && !ids.isEmpty()) {
                frozen.put(type, Set.copyOf(ids));
            }
        });
        return Collections.unmodifiableMap(frozen);
    }

    private ChunkLoad loadChunks(RetrievalPlan plan) {
        if (plan.entityIds().isEmpty() || plan.entityKeys().isEmpty()) {
            return new ChunkLoad(Collections.emptyList(), false, Collections.emptyList(), 0, 0, 0);
        }

        ChannelLoad dense = plan.needDense()
                ? loadDenseCandidates(plan)
                : ChannelLoad.notRequested();
        ChannelLoad lexical = plan.needLexical()
                ? loadLexicalCandidates(plan)
                : ChannelLoad.notRequested();

        Map<Long, Integer> denseRanks = ranksByChunkId(dense.candidates());
        Map<Long, Integer> lexicalRanks = ranksByChunkId(lexical.candidates());
        Set<Long> candidateIds = new LinkedHashSet<>();
        candidateIds.addAll(denseRanks.keySet());
        candidateIds.addAll(lexicalRanks.keySet());
        if (candidateIds.isEmpty()) {
            List<String> methods = successfulMethods(plan, dense, lexical);
            return new ChunkLoad(Collections.emptyList(), !dense.successful() || !lexical.successful(), methods,
                    dense.candidates().size(), lexical.candidates().size(), 0);
        }

        Map<Long, Double> rrfScores = new LinkedHashMap<>();
        denseRanks.forEach((chunkId, rank) -> rrfScores.merge(
                chunkId,
                ragProperties.getDenseRrfWeight() / (Math.max(1, ragProperties.getRrfK()) + rank),
                Double::sum
        ));
        lexicalRanks.forEach((chunkId, rank) -> rrfScores.merge(
                chunkId,
                ragProperties.getLexicalRrfWeight() / (Math.max(1, ragProperties.getRrfK()) + rank),
                Double::sum
        ));

        List<Long> orderedIds = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(plan.candidateLimit())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayList::new));
        Map<Long, ContentChunk> chunksById = contentChunkMapper.selectBatchIds(orderedIds).stream()
                .filter(Objects::nonNull)
                .filter(chunk -> chunk.getChunkId() != null)
                .filter(chunk -> plan.entityIds().getOrDefault(chunk.getEntityType(), Collections.emptySet())
                        .contains(chunk.getEntityId()))
                .collect(Collectors.toMap(ContentChunk::getChunkId, Function.identity(), (first, second) -> first));

        double maxScore = orderedIds.stream()
                .map(rrfScores::get)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(1D);
        List<ScoredChunk> chunks = orderedIds.stream()
                .map(chunkId -> {
                    ContentChunk chunk = chunksById.get(chunkId);
                    Double score = rrfScores.get(chunkId);
                    if (chunk == null || score == null) {
                        return null;
                    }
                    boolean fromDense = denseRanks.containsKey(chunkId);
                    boolean fromLexical = lexicalRanks.containsKey(chunkId);
                    String method = fromDense && fromLexical
                            ? RETRIEVAL_METHOD_HYBRID_RRF
                            : fromDense ? RETRIEVAL_METHOD_DENSE : RETRIEVAL_METHOD_LEXICAL;
                    double normalizedScore = maxScore <= 0D ? 0D : score / maxScore;
                    return new ScoredChunk(toChunk(chunk, normalizedScore, method), normalizedScore);
                })
                .filter(Objects::nonNull)
                .limit(Math.min(MAX_CANDIDATES, Math.max(1, ragProperties.getRerankCandidateLimit())))
                .collect(Collectors.toCollection(ArrayList::new));

        boolean degraded = !dense.successful() || !lexical.successful();
        return new ChunkLoad(chunks, degraded, successfulMethods(plan, dense, lexical),
                dense.candidates().size(), lexical.candidates().size(), candidateIds.size());
    }

    private SourceContext loadSourceContext(RetrievalPlan plan,
                                            RetrievalContext context,
                                            List<ScoredChunk> chunks) {
        Map<EntityType, Set<Long>> entityIds = copyEntityIds(plan.entityIds());
        chunks.stream().map(ScoredChunk::chunk).filter(Objects::nonNull).forEach(chunk ->
                addEntity(entityIds, entityType(chunk.getEntityType()), chunk.getEntityId()));
        context.graphFacts().forEach(fact -> {
            addEntity(entityIds, graphEntityType(fact.getSubjectType()), fact.getSubjectId());
            addEntity(entityIds, graphEntityType(fact.getObjectType()), fact.getObjectId());
        });

        List<EntitySourceRel> relations = Collections.emptyList();
        Set<Long> allEntityIds = entityIds.values().stream().flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!entityIds.isEmpty() && !allEntityIds.isEmpty()) {
            List<EntitySourceRel> loaded = entitySourceRelMapper.selectList(
                    new LambdaQueryWrapper<EntitySourceRel>()
                            .in(EntitySourceRel::getEntityType, entityIds.keySet())
                            .in(EntitySourceRel::getEntityId, allEntityIds)
                            .orderByDesc(EntitySourceRel::getCredibilityScore)
                            .orderByAsc(EntitySourceRel::getRelId));
            relations = loaded == null ? Collections.emptyList() : loaded;
        }

        Set<Long> sourceIds = relations.stream().map(EntitySourceRel::getSourceId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        chunks.stream().map(ScoredChunk::chunk).map(KnowledgeChunkVO::getSourceId)
                .filter(Objects::nonNull).forEach(sourceIds::add);
        context.graphFacts().stream().map(KnowledgeGraphFactVO::getSourceId)
                .filter(Objects::nonNull).forEach(sourceIds::add);
        context.entityMetadata().values().stream().map(RagEntityMetadata::sourceId)
                .filter(Objects::nonNull).forEach(sourceIds::add);
        Map<Long, DataSource> sources = loadSources(sourceIds);
        Map<String, Integer> credibility = new LinkedHashMap<>();
        for (EntitySourceRel relation : relations) {
            if (relation == null || relation.getEntityType() == null || relation.getEntityId() == null
                    || relation.getSourceId() == null || relation.getCredibilityScore() == null) {
                continue;
            }
            String key = credibilityKey(relation.getEntityType(), relation.getEntityId(), relation.getSourceId());
            credibility.merge(key, relation.getCredibilityScore(), Math::max);
        }
        return new SourceContext(List.copyOf(relations), sources, Collections.unmodifiableMap(credibility));
    }

    private RerankLoad rerank(RetrievalPlan plan,
                              RetrievalContext context,
                              ChunkLoad chunkLoad,
                              SourceContext sourceContext) {
        List<RankedEvidence> evidences = new ArrayList<>();
        String requestedTheme = requestedTheme(plan);
        boolean graphFeatureEnabled = plan.needGraph() && !context.graphFacts().isEmpty();

        for (ScoredChunk scoredChunk : chunkLoad.chunks()) {
            KnowledgeChunkVO chunk = scoredChunk.chunk();
            EntityType type = entityType(chunk.getEntityType());
            RagEntityMetadata metadata = metadataFor(context, type, chunk.getEntityId());
            double graphRelevance = graphRelevanceForEntity(
                    context.graphFacts(), type, chunk.getEntityId(), plan.intent());
            FeatureScore featureScore = scoreFeatures(plan, metadata, chunk.getTitle() + "\n" + chunk.getText(),
                    scoredChunk.score(), sourceCredibility(type, chunk.getEntityId(), chunk.getSourceId(),
                            metadata, sourceContext), graphRelevance, requestedTheme, graphFeatureEnabled);
            String retrievalMethod = rerankedMethod(chunk.getRetrievalMethod());
            chunk.setScore(featureScore.score());
            chunk.setRetrievalMethod(retrievalMethod);
            evidences.add(new RankedEvidence(EVIDENCE_TYPE_CHUNK, chunk.getCitationId(), chunk, null,
                    featureScore.score(), scoredChunk.score(), retrievalMethod, featureScore.contributions(), 0));
        }

        int graphSize = context.graphFacts().size();
        for (int index = 0; index < graphSize; index++) {
            KnowledgeGraphFactVO fact = context.graphFacts().get(index);
            EntityType type = graphEntityType(fact.getObjectType());
            RagEntityMetadata metadata = metadataFor(context, type, fact.getObjectId());
            double base = graphSize <= 1 ? 1D : (double) (graphSize - index) / graphSize;
            double graphRelevance = graphRelevance(fact, plan.intent());
            FeatureScore featureScore = scoreFeatures(plan, metadata, fact.getText(), base,
                    sourceCredibility(type, fact.getObjectId(), fact.getSourceId(), metadata, sourceContext),
                    graphRelevance, requestedTheme, true);
            evidences.add(new RankedEvidence(EVIDENCE_TYPE_GRAPH, fact.getCitationId(), null, fact,
                    featureScore.score(), base, RETRIEVAL_METHOD_GRAPH_RERANK,
                    featureScore.contributions(), 0));
        }

        List<RankedEvidence> ranked = evidences.stream()
                .sorted(Comparator.comparingDouble(RankedEvidence::score).reversed()
                        .thenComparing(Comparator.comparingDouble(RankedEvidence::baseScore).reversed())
                        .thenComparing(RankedEvidence::citationId))
                .limit(Math.max(1, ragProperties.getRerankCandidateLimit())
                        + Math.max(1, ragProperties.getGraphCandidateLimit()))
                .collect(Collectors.toCollection(ArrayList::new));
        for (int index = 0; index < ranked.size(); index++) {
            ranked.set(index, ranked.get(index).withRank(index + 1));
        }

        List<KnowledgeChunkVO> chunks = selectRankedChunks(ranked, plan);
        List<KnowledgeGraphFactVO> graphFacts = ranked.stream()
                .filter(item -> EVIDENCE_TYPE_GRAPH.equals(item.evidenceType()))
                .map(RankedEvidence::graphFact)
                .limit(Math.min(MAX_GRAPH_FACTS, Math.max(1, ragProperties.getGraphEvidenceLimit())))
                .collect(Collectors.toCollection(ArrayList::new));
        List<KnowledgeCitationCandidateVO> jointCandidates = buildJointCandidates(ranked, sourceContext);
        return new RerankLoad(List.copyOf(ranked), chunks, graphFacts, jointCandidates);
    }

    private List<KnowledgeChunkVO> selectRankedChunks(List<RankedEvidence> ranked, RetrievalPlan plan) {
        List<RankedEvidence> chunkEvidence = ranked.stream()
                .filter(item -> EVIDENCE_TYPE_CHUNK.equals(item.evidenceType()))
                .toList();
        if (plan.intent() != AgentIntent.NEARBY_RESOURCE) {
            return chunkEvidence.stream().map(RankedEvidence::chunk).limit(plan.topK())
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        List<KnowledgeChunkVO> selected = new ArrayList<>();
        List<KnowledgeChunkVO> deferred = new ArrayList<>();
        Map<Long, Integer> resourceCounts = new LinkedHashMap<>();
        for (RankedEvidence evidence : chunkEvidence) {
            KnowledgeChunkVO chunk = evidence.chunk();
            if (EntityType.RESOURCE.getValue().equals(chunk.getEntityType())) {
                int count = resourceCounts.getOrDefault(chunk.getEntityId(), 0);
                if (count >= 2) {
                    deferred.add(chunk);
                    continue;
                }
                resourceCounts.put(chunk.getEntityId(), count + 1);
            }
            selected.add(chunk);
            if (selected.size() >= plan.topK()) {
                return selected;
            }
        }
        for (KnowledgeChunkVO chunk : deferred) {
            selected.add(chunk);
            if (selected.size() >= plan.topK()) {
                break;
            }
        }
        return selected;
    }

    private List<KnowledgeCitationCandidateVO> buildJointCandidates(List<RankedEvidence> ranked,
                                                                    SourceContext sourceContext) {
        List<KnowledgeCitationCandidateVO> result = new ArrayList<>();
        int graphCount = 0;
        int graphLimit = Math.max(0, ragProperties.getGraphContextLimit());
        int limit = Math.max(1, ragProperties.getJointEvidenceLimit());
        for (RankedEvidence evidence : ranked) {
            if (EVIDENCE_TYPE_GRAPH.equals(evidence.evidenceType()) && graphCount >= graphLimit) {
                continue;
            }
            KnowledgeCitationCandidateVO candidate = evidenceCandidate(evidence, sourceContext.sources());
            result.add(candidate);
            if (EVIDENCE_TYPE_GRAPH.equals(evidence.evidenceType())) {
                graphCount++;
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private KnowledgeCitationCandidateVO evidenceCandidate(RankedEvidence evidence,
                                                           Map<Long, DataSource> sources) {
        KnowledgeCitationCandidateVO candidate = new KnowledgeCitationCandidateVO();
        candidate.setCitationId(evidence.citationId());
        candidate.setEvidenceType(evidence.evidenceType());
        candidate.setScore(evidence.score());
        candidate.setRank(evidence.rank());
        candidate.setRetrievalMethod(evidence.retrievalMethod());
        if (EVIDENCE_TYPE_CHUNK.equals(evidence.evidenceType())) {
            KnowledgeChunkVO chunk = evidence.chunk();
            candidate.setTitle(chunk.getTitle());
            candidate.setSourceType("content_chunk");
            candidate.setRelatedEntityType(chunk.getEntityType());
            candidate.setRelatedEntityId(chunk.getEntityId());
            candidate.setExcerpt(truncate(chunk.getText(), MAX_EXCERPT_LENGTH));
            DataSource source = sources.get(chunk.getSourceId());
            candidate.setUrl(source == null ? null : source.getBaseUrl());
        } else {
            KnowledgeGraphFactVO fact = evidence.graphFact();
            candidate.setTitle("图谱关系：" + cleanOrDefault(fact.getPredicate(), "GRAPH_PATH"));
            candidate.setSourceType("graph_fact");
            candidate.setRelatedEntityType(fact.getObjectType());
            candidate.setRelatedEntityId(fact.getObjectId());
            candidate.setExcerpt(truncate(fact.getText(), MAX_EXCERPT_LENGTH));
            DataSource source = sources.get(fact.getSourceId());
            candidate.setUrl(source == null ? null : source.getBaseUrl());
        }
        return candidate;
    }

    private FeatureScore scoreFeatures(RetrievalPlan plan,
                                       RagEntityMetadata metadata,
                                       String evidenceText,
                                       double base,
                                       double sourceCredibility,
                                       double graphRelevance,
                                       String requestedTheme,
                                       boolean graphFeatureEnabled) {
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        values.put("base", clamp01(base));
        weights.put("base", ragProperties.getBaseRetrievalWeight());
        values.put("entityMatch", entityMatch(plan.originalQuery(), metadata));
        weights.put("entityMatch", ragProperties.getEntityMatchWeight());
        if (StringUtils.hasText(plan.grade())) {
            values.put("gradeMatch", gradeMatch(plan.grade(), metadata == null ? null : metadata.grade()));
            weights.put("gradeMatch", ragProperties.getGradeMatchWeight());
        }
        if (StringUtils.hasText(requestedTheme)) {
            String candidateTheme = joinNonBlank(metadata == null ? null : metadata.theme(), evidenceText);
            values.put("themeMatch", themeMatch(requestedTheme, candidateTheme));
            weights.put("themeMatch", ragProperties.getThemeMatchWeight());
        }
        values.put("sourceCredibility", clamp01(sourceCredibility));
        weights.put("sourceCredibility", ragProperties.getSourceCredibilityWeight());
        if (graphFeatureEnabled) {
            values.put("graphRelevance", clamp01(graphRelevance));
            weights.put("graphRelevance", ragProperties.getGraphRelevanceWeight());
        }

        double enabledWeight = weights.values().stream().filter(weight -> weight > 0D)
                .mapToDouble(Double::doubleValue).sum();
        Map<String, Double> contributions = new LinkedHashMap<>();
        double score = 0D;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            double weight = Math.max(0D, weights.getOrDefault(entry.getKey(), 0D));
            double contribution = enabledWeight <= 0D ? 0D : entry.getValue() * weight / enabledWeight;
            contributions.put(entry.getKey(), contribution);
            score += contribution;
        }
        return new FeatureScore(score, Collections.unmodifiableMap(contributions));
    }

    private double entityMatch(String query, RagEntityMetadata metadata) {
        if (!StringUtils.hasText(query) || metadata == null) {
            return 0D;
        }
        String normalizedQuery = normalize(query);
        if (StringUtils.hasText(metadata.canonicalName())
                && normalizedQuery.contains(normalize(metadata.canonicalName()))) {
            return 1D;
        }
        boolean aliasMatch = metadata.aliases().stream().filter(StringUtils::hasText)
                .map(this::normalize).filter(alias -> !alias.isEmpty()).anyMatch(normalizedQuery::contains);
        return aliasMatch ? 0.9D : 0D;
    }

    private double gradeMatch(String requested, String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return 0.5D;
        }
        GradeProfile requestedProfile = gradeProfile(requested);
        GradeProfile candidateProfile = gradeProfile(candidate);
        if (requestedProfile.exactGrade() != null
                && requestedProfile.exactGrade().equals(candidateProfile.exactGrade())) {
            return 1D;
        }
        if (requestedProfile.stage() != null && requestedProfile.stage() == candidateProfile.stage()) {
            return candidateProfile.genericStage() ? 1D : 0.7D;
        }
        if (requestedProfile.stage() != null && candidateProfile.stage() != null
                && Math.abs(requestedProfile.stage().ordinal() - candidateProfile.stage().ordinal()) == 1) {
            return 0.5D;
        }
        return 0D;
    }

    private GradeProfile gradeProfile(String value) {
        String normalized = normalize(value);
        Integer exact = null;
        for (int grade = 1; grade <= 6; grade++) {
            String chinese = switch (grade) {
                case 1 -> "一";
                case 2 -> "二";
                case 3 -> "三";
                case 4 -> "四";
                case 5 -> "五";
                default -> "六";
            };
            if (normalized.contains(grade + "年级") || normalized.contains(chinese + "年级")) {
                exact = grade;
                break;
            }
        }
        GradeStage stage = exact == null ? null : gradeStage(exact);
        boolean generic = false;
        if (normalized.contains("低年级")) {
            stage = GradeStage.LOW;
            generic = true;
        } else if (normalized.contains("中年级")) {
            stage = GradeStage.MIDDLE;
            generic = true;
        } else if (normalized.contains("高年级")) {
            stage = GradeStage.HIGH;
            generic = true;
        }
        return new GradeProfile(exact, stage, generic);
    }

    private GradeStage gradeStage(int grade) {
        if (grade <= 2) {
            return GradeStage.LOW;
        }
        if (grade <= 4) {
            return GradeStage.MIDDLE;
        }
        return GradeStage.HIGH;
    }

    private String requestedTheme(RetrievalPlan plan) {
        if (StringUtils.hasText(plan.theme())) {
            return plan.theme().trim();
        }
        return DOMAIN_THEME_KEYWORDS.stream().filter(plan.originalQuery()::contains)
                .collect(Collectors.joining("、"));
    }

    private double themeMatch(String requested, String candidate) {
        if (!StringUtils.hasText(requested) || !StringUtils.hasText(candidate)) {
            return 0D;
        }
        Set<String> themes = DOMAIN_THEME_KEYWORDS.stream()
                .filter(requested::contains).collect(Collectors.toCollection(LinkedHashSet::new));
        if (themes.isEmpty()) {
            themes.add(normalize(requested));
        }
        long matches = themes.stream().filter(StringUtils::hasText)
                .filter(theme -> normalize(candidate).contains(normalize(theme))).count();
        return themes.isEmpty() ? 0D : (double) matches / themes.size();
    }

    private double sourceCredibility(EntityType type,
                                     Long entityId,
                                     Long sourceId,
                                     RagEntityMetadata metadata,
                                     SourceContext sourceContext) {
        Long effectiveSourceId = sourceId != null ? sourceId : metadata == null ? null : metadata.sourceId();
        if (type != null && entityId != null && effectiveSourceId != null) {
            Integer credibility = sourceContext.credibilityByEntitySource()
                    .get(credibilityKey(type, entityId, effectiveSourceId));
            if (credibility != null) {
                return clampScoreLevel(credibility);
            }
        }
        DataSource source = sourceContext.sources().get(effectiveSourceId);
        return source == null || source.getReliabilityLevel() == null
                ? 0.6D : clampScoreLevel(source.getReliabilityLevel());
    }

    private double graphRelevanceForEntity(List<KnowledgeGraphFactVO> facts,
                                           EntityType type,
                                           Long entityId,
                                           AgentIntent intent) {
        if (type == null || entityId == null || facts == null) {
            return 0D;
        }
        return facts.stream().filter(Objects::nonNull)
                .filter(fact -> sameGraphEntity(type, entityId, fact.getSubjectType(), fact.getSubjectId())
                        || sameGraphEntity(type, entityId, fact.getObjectType(), fact.getObjectId()))
                .mapToDouble(fact -> graphRelevance(fact, intent)).max().orElse(0D);
    }

    private boolean sameGraphEntity(EntityType type, Long id, String graphType, Long graphId) {
        return id.equals(graphId) && type == graphEntityType(graphType);
    }

    private double graphRelevance(KnowledgeGraphFactVO fact, AgentIntent intent) {
        int hop = fact.getHop() == null ? 1 : fact.getHop();
        double factor = hop <= 1 ? 1D : hop == 2 ? 0.65D : 0.4D;
        if (intent == AgentIntent.NEARBY_RESOURCE && fact.getDistanceMeters() != null
                && fact.getDistanceMeters() >= 0D) {
            factor *= Math.exp(-fact.getDistanceMeters() / 10000D);
        }
        return clamp01(factor);
    }

    private RagEntityMetadata metadataFor(RetrievalContext context, EntityType type, Long id) {
        RagEntityMetadata metadata = context.entityMetadata().get(entityKey(type, id));
        if (metadata != null || type == null || id == null) {
            return metadata;
        }
        return context.entityHints().stream()
                .filter(hint -> hint.entityType() == type && id.equals(hint.entityId()))
                .findFirst()
                .map(hint -> new RagEntityMetadata(type, id, hint.canonicalName(), hint.aliases(), null,
                        null, null, null, null, null, ""))
                .orElse(null);
    }

    private String rerankedMethod(String method) {
        if (RETRIEVAL_METHOD_HYBRID_RRF.equals(method)) {
            return RETRIEVAL_METHOD_HYBRID_RERANK;
        }
        if (RETRIEVAL_METHOD_DENSE.equals(method)) {
            return RETRIEVAL_METHOD_DENSE_RERANK;
        }
        return RETRIEVAL_METHOD_LEXICAL_RERANK;
    }

    private double clampScoreLevel(int value) {
        return Math.max(1, Math.min(5, value)) / 5D;
    }

    private double clamp01(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private String joinNonBlank(String... values) {
        return Stream.of(values).filter(StringUtils::hasText).collect(Collectors.joining(" "));
    }

    private String credibilityKey(EntityType type, Long entityId, Long sourceId) {
        return entityKey(type, entityId) + "|" + sourceId;
    }

    private String entityKey(EntityType type, Long id) {
        return type == null || id == null ? "" : type.getValue() + ":" + id;
    }

    private ChannelLoad loadDenseCandidates(RetrievalPlan plan) {
        try {
            float[] queryVector = embeddingClient.embed(plan.searchQuery());
            List<VectorSearchCandidate> candidates = vectorStore.search(
                    queryVector,
                    plan.entityKeys(),
                    plan.candidateLimit()
            );
            return new ChannelLoad(rankCandidates(candidates), true);
        } catch (RuntimeException exception) {
            log.warn("Dense retrieval failed", exception);
            return new ChannelLoad(Collections.emptyList(), false);
        }
    }

    private ChannelLoad loadLexicalCandidates(RetrievalPlan plan) {
        String query = sanitizeFullTextQuery(plan.searchQuery());
        if (!StringUtils.hasText(query)) {
            return new ChannelLoad(Collections.emptyList(), true);
        }
        try {
            Map<String, Collection<Long>> entityIdsByType = plan.entityIds().entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> entry.getKey().getValue(),
                            entry -> new LinkedHashSet<>(entry.getValue()),
                            (first, second) -> {
                                Set<Long> merged = new LinkedHashSet<>(first);
                                merged.addAll(second);
                                return merged;
                            },
                            LinkedHashMap::new
                    ));
            List<ContentChunk> chunks = contentChunkMapper.searchByFullText(
                    entityIdsByType,
                    query,
                    plan.candidateLimit()
            );
            return new ChannelLoad(rankChunks(chunks), true);
        } catch (RuntimeException exception) {
            log.warn("Lexical retrieval failed", exception);
            return new ChannelLoad(Collections.emptyList(), false);
        }
    }

    private List<RankedCandidate> rankCandidates(List<VectorSearchCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Double> bestScores = new LinkedHashMap<>();
        candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.chunkId() != null)
                .forEach(candidate -> bestScores.merge(candidate.chunkId(), candidate.score(), Math::max));
        return bestScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new RankedCandidate(entry.getKey(), entry.getValue()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<RankedCandidate> rankChunks(List<ContentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }
        List<RankedCandidate> ranked = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (ContentChunk chunk : chunks) {
            if (chunk != null && chunk.getChunkId() != null && seen.add(chunk.getChunkId())) {
                ranked.add(new RankedCandidate(chunk.getChunkId(), 0D));
            }
        }
        return ranked;
    }

    private Map<Long, Integer> ranksByChunkId(List<RankedCandidate> candidates) {
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        if (candidates == null) {
            return ranks;
        }
        int rank = 1;
        for (RankedCandidate candidate : candidates) {
            if (candidate != null && candidate.chunkId() != null) {
                ranks.putIfAbsent(candidate.chunkId(), rank++);
            }
        }
        return ranks;
    }

    private List<String> successfulMethods(RetrievalPlan plan,
                                            ChannelLoad dense,
                                            ChannelLoad lexical) {
        List<String> methods = new ArrayList<>();
        if (plan.needDense() && dense.successful()) {
            methods.add(RETRIEVAL_METHOD_DENSE);
        }
        if (plan.needLexical() && lexical.successful()) {
            methods.add(RETRIEVAL_METHOD_LEXICAL);
        }
        if (plan.needDense() && plan.needLexical()
                && dense.successful() && lexical.successful()
                && (!dense.candidates().isEmpty() || !lexical.candidates().isEmpty())) {
            methods.add(RETRIEVAL_METHOD_RRF);
        }
        return methods;
    }

    private String sanitizeFullTextQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        return FULLTEXT_SPECIAL_CHARACTERS.matcher(query)
                .replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private KnowledgeChunkVO toChunk(ContentChunk chunk, double score, String retrievalMethod) {
        KnowledgeChunkVO vo = new KnowledgeChunkVO();
        vo.setCitationId("chunk:" + chunk.getChunkId());
        vo.setChunkId(chunk.getChunkId());
        vo.setTitle(cleanOrDefault(chunk.getChunkTitle(), "内容分块 " + chunk.getChunkId()));
        vo.setText(truncate(chunk.getChunkText(), MAX_TEXT_LENGTH));
        vo.setScore(score);
        vo.setRetrievalMethod(retrievalMethod);
        vo.setEntityType(enumValue(chunk.getEntityType()));
        vo.setEntityId(chunk.getEntityId());
        vo.setSourceId(chunk.getSourceId());
        return vo;
    }

    private Map<Long, DataSource> loadSources(Collection<Long> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<DataSource> loaded = dataSourceMapper.selectBatchIds(sourceIds);
        if (loaded == null) {
            return Collections.emptyMap();
        }
        return loaded.stream()
                .filter(Objects::nonNull)
                .filter(source -> source.getSourceId() != null)
                .collect(Collectors.toMap(
                        DataSource::getSourceId,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
    }

    private List<KnowledgeCitationCandidateVO> buildSourceCandidates(SourceContext sourceContext) {
        List<KnowledgeCitationCandidateVO> result = new ArrayList<>();
        for (EntitySourceRel relation : sourceContext.relations()) {
            if (relation == null || relation.getRelId() == null) {
                continue;
            }
            DataSource source = sourceContext.sources().get(relation.getSourceId());
            KnowledgeCitationCandidateVO candidate = new KnowledgeCitationCandidateVO();
            candidate.setCitationId("source-rel:" + relation.getRelId());
            candidate.setTitle(source == null ? "来源记录 " + relation.getRelId()
                    : cleanOrDefault(source.getSourceName(), "来源记录 " + relation.getRelId()));
            candidate.setSourceType("entity_source");
            candidate.setEvidenceType("source");
            candidate.setRelatedEntityType(enumValue(relation.getEntityType()));
            candidate.setRelatedEntityId(relation.getEntityId());
            candidate.setExcerpt(truncate(relation.getSourceExcerpt(), MAX_EXCERPT_LENGTH));
            candidate.setUrl(StringUtils.hasText(relation.getSourceUrl()) ? relation.getSourceUrl()
                    : source == null ? null : source.getBaseUrl());
            result.add(candidate);
        }
        return result;
    }

    private KnowledgeRetrievalTraceVO buildRetrievalTrace(RetrievalPlan plan,
                                                          RetrievalContext context,
                                                          ChunkLoad chunkLoad,
                                                          RerankLoad rerankLoad,
                                                          List<String> retrievalMethods) {
        KnowledgeRetrievalTraceVO trace = new KnowledgeRetrievalTraceVO();
        trace.setRetrievalStatus(resolveStatus(!rerankLoad.evidences().isEmpty(),
                GRAPH_STATUS_FAILED.equals(context.graphStatus()) || chunkLoad.degraded()).name().toLowerCase(Locale.ROOT));
        trace.setIntent(plan.intent().name());
        trace.setNeedGraph(plan.needGraph());
        trace.setGraphStatus(context.graphStatus());
        trace.setDenseCandidateCount(chunkLoad.denseCandidateCount());
        trace.setLexicalCandidateCount(chunkLoad.lexicalCandidateCount());
        trace.setRrfCandidateCount(chunkLoad.rrfCandidateCount());
        trace.setGraphCandidateCount(context.graphFacts().size());
        trace.setRerankedCandidateCount(rerankLoad.evidences().size());
        trace.setRetrievalMethods(new ArrayList<>(retrievalMethods));
        trace.setTopCandidates(rerankLoad.evidences().stream().limit(8).map(evidence -> {
            KnowledgeRetrievalCandidateTraceVO candidate = new KnowledgeRetrievalCandidateTraceVO();
            candidate.setCitationId(evidence.citationId());
            candidate.setEvidenceType(evidence.evidenceType());
            candidate.setScore(evidence.score());
            candidate.setRank(evidence.rank());
            candidate.setRetrievalMethod(evidence.retrievalMethod());
            candidate.setContributions(new LinkedHashMap<>(evidence.contributions()));
            return candidate;
        }).collect(Collectors.toCollection(ArrayList::new)));
        return trace;
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
                    + "RETURN s.id AS schoolId, coalesce(s.name, s.schoolName) AS schoolName, "
                    + "r.id AS resourceId, coalesce(r.name, r.resourceName) AS resourceName, "
                    + "type(rel) AS predicate, rel.educationThemeSummary AS theme, "
                    + "rel.distanceMeters AS distanceMeters, rel.sourceId AS sourceId "
                    + "ORDER BY coalesce(rel.priorityLevel, 999999), coalesce(rel.distanceMeters, 999999) "
                    + "LIMIT " + Math.max(1, ragProperties.getGraphCandidateLimit());
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
                fact.setSubjectType(EntityType.SCHOOL.getValue());
                fact.setSubjectName(stringValue(row.get("schoolName")));
                fact.setPredicate(cleanOrDefault(stringValue(row.get("predicate")), "SCHOOL_NEAR_RESOURCE"));
                fact.setObjectId(resourceId);
                fact.setObjectType(EntityType.RESOURCE.getValue());
                fact.setObjectName(stringValue(row.get("resourceName")));
                fact.setHop(1);
                fact.setDistanceMeters(doubleValue(row.get("distanceMeters")));
                fact.setSourceId(longValue(row.get("sourceId")));
                fact.setPathEdges(List.of(directPathEdge(fact)));
                facts.add(fact);
            }

            if (resolveIntent(request) == AgentIntent.RELATION_QUERY) {
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
                + "WHERE (target:Hero OR target:Event OR target:Site OR target:Memorial "
                + "OR target:Story OR target:LocalEduResource) "
                + "AND all(rel IN relationships(p) WHERE type(rel) IN "
                + GRAPH_RELATIONSHIP_WHITELIST + ") "
                + "RETURN DISTINCT " + pathProjection() + ", length(p) AS hop "
                + "ORDER BY hop ASC LIMIT " + Math.max(1, ragProperties.getGraphCandidateLimit());
        Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
                .bind(schoolId).to("schoolId")
                .fetch()
                .all();
        List<KnowledgeGraphFactVO> facts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            KnowledgeGraphFactVO fact = pathFact("school", schoolId, row);
            if (fact != null) {
                facts.add(fact);
            }
        }
        return facts;
    }

    private GraphLoad loadRegionGraphFacts(Long regionId, TownMapDetailVO detail) {
        try {
            String cypher = ""
                    + "MATCH p=(region:Region {id: $regionId})-[*1..3]-(target) "
                    + "WHERE (target:LocalEduResource OR target:Hero OR target:Event OR target:Site "
                    + "OR target:Memorial OR target:Story) "
                    + "AND all(rel IN relationships(p) WHERE type(rel) IN "
                    + GRAPH_RELATIONSHIP_WHITELIST + ") "
                    + "RETURN DISTINCT " + pathProjection() + ", length(p) AS hop "
                    + "ORDER BY hop ASC LIMIT " + Math.max(1, ragProperties.getGraphCandidateLimit());
            Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
                    .bind(regionId).to("regionId")
                    .fetch()
                    .all();
            List<KnowledgeGraphFactVO> facts = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                KnowledgeGraphFactVO fact = pathFact("region", regionId, row);
                if (fact != null) {
                    facts.add(fact);
                }
            }
            return new GraphLoad(limitList(deduplicateFacts(facts), MAX_GRAPH_FACTS), false);
        } catch (RuntimeException exception) {
            log.warn("Region graph retrieval failed for region {}", regionId, exception);
            return new GraphLoad(Collections.emptyList(), true);
        }
    }

    private GraphLoad loadResourceGraphFacts(Long resourceId) {
        try {
            String cypher = ""
                    + "MATCH (seed:LocalEduResource {id: $resourceId}) "
                    + "MATCH (anchor) "
                    + "WHERE anchor = seed OR ((anchor:Site OR anchor:Memorial) AND anchor.name = seed.name) "
                    + "MATCH p=(anchor)-[*1..2]-(target) "
                    + "WHERE (target:LocalEduResource OR target:Hero OR target:Event OR target:Site "
                    + "OR target:Memorial OR target:Story) AND target <> anchor "
                    + "AND all(rel IN relationships(p) WHERE type(rel) IN "
                    + GRAPH_RELATIONSHIP_WHITELIST + ") "
                    + "RETURN DISTINCT " + pathProjection() + ", length(p) AS hop "
                    + "ORDER BY hop ASC LIMIT " + Math.max(1, ragProperties.getGraphCandidateLimit());
            Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
                    .bind(resourceId).to("resourceId")
                    .fetch()
                    .all();
            List<KnowledgeGraphFactVO> facts = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                KnowledgeGraphFactVO fact = pathFact("resource", resourceId, row);
                if (fact != null) {
                    facts.add(fact);
                }
            }
            return new GraphLoad(deduplicateFacts(facts), false);
        } catch (RuntimeException exception) {
            return new GraphLoad(Collections.emptyList(), true);
        }
    }

    private String pathProjection() {
        return "[n IN nodes(p) | {nodeType: head(labels(n)), nodeId: n.id, "
                + "nodeName: coalesce(n.name, n.title, n.schoolName, n.resourceName)}] AS pathNodes, "
                + "[rel IN relationships(p) | {predicate: type(rel), startId: startNode(rel).id, "
                + "endId: endNode(rel).id, sourceId: rel.sourceId, "
                + "distanceMeters: rel.distanceMeters}] AS pathRelationships";
    }

    private KnowledgeGraphFactVO pathFact(String anchorType, Long anchorId, Map<String, Object> row) {
        List<Map<String, Object>> nodes = mapList(row.get("pathNodes"));
        List<Map<String, Object>> relationships = mapList(row.get("pathRelationships"));
        if (nodes.size() < 2) {
            return legacyPathFact(anchorType, anchorId, row);
        }
        Map<String, Object> first = nodes.get(0);
        Map<String, Object> last = nodes.get(nodes.size() - 1);
        Long subjectId = longValue(first.get("nodeId"));
        Long objectId = longValue(last.get("nodeId"));
        String subjectType = graphTypeValue(stringValue(first.get("nodeType")));
        String objectType = graphTypeValue(stringValue(last.get("nodeType")));
        String subjectName = stringValue(first.get("nodeName"));
        String objectName = stringValue(last.get("nodeName"));
        int hop = intValue(row.get("hop"), relationships.size());
        if (subjectId == null || objectId == null || hop <= 0) {
            return null;
        }

        List<KnowledgeGraphPathEdgeVO> pathEdges = new ArrayList<>();
        Long sourceId = null;
        Double distanceMeters = null;
        for (int index = 0; index < relationships.size() && index + 1 < nodes.size(); index++) {
            Map<String, Object> relationship = relationships.get(index);
            Map<String, Object> fromNode = nodes.get(index);
            Map<String, Object> toNode = nodes.get(index + 1);
            Long traversalFromId = longValue(fromNode.get("nodeId"));
            Long relationshipStartId = longValue(relationship.get("startId"));
            KnowledgeGraphPathEdgeVO edge = new KnowledgeGraphPathEdgeVO();
            edge.setFromType(graphTypeValue(stringValue(fromNode.get("nodeType"))));
            edge.setFromId(traversalFromId);
            edge.setFromName(stringValue(fromNode.get("nodeName")));
            edge.setPredicate(stringValue(relationship.get("predicate")));
            edge.setToType(graphTypeValue(stringValue(toNode.get("nodeType"))));
            edge.setToId(longValue(toNode.get("nodeId")));
            edge.setToName(stringValue(toNode.get("nodeName")));
            edge.setDirection(Objects.equals(traversalFromId, relationshipStartId)
                    ? "OUTGOING" : "INCOMING");
            pathEdges.add(edge);
            if (sourceId == null) {
                sourceId = longValue(relationship.get("sourceId"));
            }
            Double edgeDistance = doubleValue(relationship.get("distanceMeters"));
            if (edgeDistance != null) {
                distanceMeters = (distanceMeters == null ? 0D : distanceMeters) + edgeDistance;
            }
        }
        if (pathEdges.isEmpty()) {
            return null;
        }
        String predicate = hop == 1 && StringUtils.hasText(pathEdges.get(0).getPredicate())
                ? pathEdges.get(0).getPredicate() : "GRAPH_PATH";
        KnowledgeGraphFactVO fact = new KnowledgeGraphFactVO();
        fact.setCitationId("graph:" + anchorType + ":" + anchorId + ":path:"
                + objectType + ":" + objectId + ":" + hop);
        fact.setSubjectId(subjectId);
        fact.setSubjectType(subjectType);
        fact.setSubjectName(subjectName);
        fact.setPredicate(predicate);
        fact.setObjectId(objectId);
        fact.setObjectType(objectType);
        fact.setObjectName(objectName);
        fact.setHop(hop);
        fact.setDistanceMeters(distanceMeters);
        fact.setSourceId(sourceId);
        fact.setPathEdges(pathEdges);
        fact.setText(buildPathText(subjectName, objectName, pathEdges));
        return fact;
    }

    private KnowledgeGraphFactVO legacyPathFact(String anchorType, Long anchorId, Map<String, Object> row) {
        Long targetId = longValue(row.get("targetId"));
        String targetName = stringValue(row.get("targetName"));
        if (targetId == null || !StringUtils.hasText(targetName)) {
            return null;
        }
        String targetType = graphTypeValue(firstLabel(row.get("targetLabels")));
        KnowledgeGraphFactVO fact = new KnowledgeGraphFactVO();
        fact.setCitationId("graph:" + anchorType + ":" + anchorId + ":path:" + targetType + ":" + targetId);
        fact.setSubjectId(anchorId);
        fact.setSubjectType(anchorType);
        fact.setPredicate("GRAPH_PATH");
        fact.setObjectId(targetId);
        fact.setObjectType(targetType);
        fact.setObjectName(targetName);
        fact.setHop(intValue(row.get("hop"), 1));
        fact.setText(anchorType + "与" + targetType + "“" + targetName + "”存在图谱路径关联。");
        return fact;
    }

    private KnowledgeGraphPathEdgeVO directPathEdge(KnowledgeGraphFactVO fact) {
        KnowledgeGraphPathEdgeVO edge = new KnowledgeGraphPathEdgeVO();
        edge.setFromType(fact.getSubjectType());
        edge.setFromId(fact.getSubjectId());
        edge.setFromName(fact.getSubjectName());
        edge.setPredicate(fact.getPredicate());
        edge.setToType(fact.getObjectType());
        edge.setToId(fact.getObjectId());
        edge.setToName(fact.getObjectName());
        edge.setDirection("OUTGOING");
        return edge;
    }

    private String buildPathText(String subjectName,
                                 String objectName,
                                 List<KnowledgeGraphPathEdgeVO> pathEdges) {
        String subject = cleanOrDefault(subjectName, cleanOrDefault(pathEdges.get(0).getFromType(), "实体"));
        String object = cleanOrDefault(objectName,
                cleanOrDefault(pathEdges.get(pathEdges.size() - 1).getToType(), "实体"));
        String relations = pathEdges.stream().map(KnowledgeGraphPathEdgeVO::getPredicate)
                .filter(StringUtils::hasText).collect(Collectors.joining(" -> "));
        return "“" + subject + "”通过 " + relations + " 与“" + object + "”关联。";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return Collections.emptyList();
        }
        return collection.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private EntityType graphEntityType(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z]", "")
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "school" -> EntityType.SCHOOL;
            case "resource", "localeduresource" -> EntityType.RESOURCE;
            case "site", "redsite" -> EntityType.SITE;
            case "hero", "heroperson" -> EntityType.HERO;
            case "event", "historicalevent" -> EntityType.EVENT;
            case "memorial", "memorialhall" -> EntityType.MEMORIAL;
            case "story", "redstory" -> EntityType.STORY;
            case "activityplan", "teachingactivityplan" -> EntityType.ACTIVITY_PLAN;
            default -> entityType(value);
        };
    }

    private String graphTypeValue(String value) {
        EntityType type = graphEntityType(value);
        return type == null ? value == null ? "entity" : value.toLowerCase(Locale.ROOT) : type.getValue();
    }

    private List<KnowledgeGraphFactVO> deduplicateFacts(List<KnowledgeGraphFactVO> facts) {
        Map<String, KnowledgeGraphFactVO> unique = new LinkedHashMap<>();
        for (KnowledgeGraphFactVO fact : facts) {
            if (fact != null && StringUtils.hasText(fact.getCitationId())) {
                String semanticKey = String.join("|",
                        graphTypeValue(fact.getSubjectType()), String.valueOf(fact.getSubjectId()),
                        cleanOrDefault(fact.getPredicate(), "GRAPH_PATH"),
                        graphTypeValue(fact.getObjectType()), String.valueOf(fact.getObjectId()),
                        String.valueOf(fact.getHop()));
                unique.putIfAbsent(semanticKey, fact);
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

    private record RetrievalPlan(String originalQuery,
                                 String searchQuery,
                                 AgentIntent intent,
                                 KnowledgeScopeType scopeType,
                                 Long scopeId,
                                 String grade,
                                 String theme,
                                 Set<String> entityKeys,
                                 Map<EntityType, Set<Long>> entityIds,
                                 int topK,
                                 int candidateLimit,
                                 boolean needDense,
                                 boolean needLexical,
                                 boolean needGraph) {
    }

    private record EntityHint(EntityType entityType,
                              Long entityId,
                              String canonicalName,
                              List<String> aliases) {
    }

    private record RankedCandidate(Long chunkId, double score) {
    }

    private record ChannelLoad(List<RankedCandidate> candidates, boolean successful) {

        private static ChannelLoad notRequested() {
            return new ChannelLoad(Collections.emptyList(), true);
        }
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (!StringUtils.hasText(value == null ? null : String.valueOf(value))) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int intValue(Object value, int fallback) {
        Long parsed = longValue(value);
        return parsed == null ? fallback : parsed.intValue();
    }

    private record ScoredChunk(KnowledgeChunkVO chunk, double score) {
    }

    private record ChunkLoad(List<ScoredChunk> chunks,
                             boolean degraded,
                             List<String> retrievalMethods,
                             int denseCandidateCount,
                             int lexicalCandidateCount,
                             int rrfCandidateCount) {
    }

    private record GraphLoad(List<KnowledgeGraphFactVO> facts, boolean unavailable) {
    }

    private record RetrievalContext(Map<EntityType, Set<Long>> entityIds,
                                    List<EntityHint> entityHints,
                                    List<KnowledgeGraphFactVO> graphFacts,
                                    String graphStatus,
                                    Map<String, RagEntityMetadata> entityMetadata) {
    }

    private record SourceContext(List<EntitySourceRel> relations,
                                 Map<Long, DataSource> sources,
                                 Map<String, Integer> credibilityByEntitySource) {
    }

    private record FeatureScore(double score, Map<String, Double> contributions) {
    }

    private record RankedEvidence(String evidenceType,
                                  String citationId,
                                  KnowledgeChunkVO chunk,
                                  KnowledgeGraphFactVO graphFact,
                                  double score,
                                  double baseScore,
                                  String retrievalMethod,
                                  Map<String, Double> contributions,
                                  int rank) {

        private RankedEvidence withRank(int value) {
            return new RankedEvidence(evidenceType, citationId, chunk, graphFact, score, baseScore,
                    retrievalMethod, contributions, value);
        }
    }

    private record RerankLoad(List<RankedEvidence> evidences,
                              List<KnowledgeChunkVO> chunks,
                              List<KnowledgeGraphFactVO> graphFacts,
                              List<KnowledgeCitationCandidateVO> jointCandidates) {
    }

    private record GradeProfile(Integer exactGrade, GradeStage stage, boolean genericStage) {
    }

    private enum GradeStage {
        LOW,
        MIDDLE,
        HIGH
    }
}
