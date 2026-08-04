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
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import org.springframework.context.annotation.Profile;
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
    private static final int MAX_CITATIONS = 8;
    private static final int MAX_GRAPH_FACTS = 8;
    private static final int MAX_TEXT_LENGTH = 700;
    private static final int MAX_EXCERPT_LENGTH = 180;
    private static final Pattern FULLTEXT_SPECIAL_CHARACTERS =
            Pattern.compile("[+\\-~*<>()\\[\\]@\"']+");
    private static final String RETRIEVAL_METHOD_DENSE = "dense";
    private static final String RETRIEVAL_METHOD_LEXICAL = "lexical";
    private static final String RETRIEVAL_METHOD_RRF = "rrf";
    private static final String RETRIEVAL_METHOD_HYBRID_RRF = "hybrid-rrf";

    private final ContentChunkMapper contentChunkMapper;
    private final EntitySourceRelMapper entitySourceRelMapper;
    private final DataSourceMapper dataSourceMapper;
    private final SchoolMapService schoolMapService;
    private final TownMapService townMapService;
    private final Neo4jClient neo4jClient;
    private final RagProperties ragProperties;
    private final EmbeddingClient embeddingClient;
    private final ChunkVectorStore vectorStore;

    public DatabaseKnowledgeRetriever(ContentChunkMapper contentChunkMapper,
                                     EntitySourceRelMapper entitySourceRelMapper,
                                     DataSourceMapper dataSourceMapper,
                                     SchoolMapService schoolMapService,
                                     TownMapService townMapService,
                                     Neo4jClient neo4jClient,
                                     RagProperties ragProperties,
                                     EmbeddingClient embeddingClient,
                                     ChunkVectorStore vectorStore) {
        this.contentChunkMapper = contentChunkMapper;
        this.entitySourceRelMapper = entitySourceRelMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.schoolMapService = schoolMapService;
        this.townMapService = townMapService;
        this.neo4jClient = neo4jClient;
        this.ragProperties = ragProperties;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
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

            RetrievalPlan plan = buildPlan(request, context);
            ChunkLoad chunkLoad = loadChunks(plan);
            List<ScoredChunk> scoredChunks = chunkLoad.chunks();
            List<KnowledgeChunkVO> chunks = scoredChunks.stream()
                    .limit(plan.topK())
                    .map(ScoredChunk::chunk)
                    .collect(Collectors.toList());

            Map<Long, DataSource> sources = loadSources(
                    scoredChunks.stream()
                            .map(item -> item.chunk().getSourceId())
                            .filter(Objects::nonNull)
                            .collect(Collectors.toCollection(LinkedHashSet::new))
            );
            List<KnowledgeCitationCandidateVO> candidates = buildChunkCandidates(chunks, sources);
            candidates.addAll(buildSourceCandidates(plan.entityIds(), sources));
            candidates.addAll(buildGraphCandidates(context.graphFacts()));
            candidates = deduplicateCandidates(candidates);

            KnowledgeRetrieveResult result = new KnowledgeRetrieveResult();
            result.setChunks(chunks);
            result.setGraphFacts(limitList(context.graphFacts(), MAX_GRAPH_FACTS));
            result.setCitationCandidates(limitList(candidates, MAX_CITATIONS));
            result.setRetrievalMethods(new ArrayList<>(chunkLoad.retrievalMethods()));
            result.setRetrievalStatus(resolveStatus(
                    !chunks.isEmpty() || !context.graphFacts().isEmpty() || !candidates.isEmpty(),
                    context.graphUnavailable() || chunkLoad.degraded()
            ));
            result.refreshRetrievalMethods();
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
        boolean graphUnavailable = false;
        boolean needGraph = needsGraph(resolveIntent(request));

        switch (request.getScopeType()) {
            case SCHOOL -> {
                SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(request.getScopeId());
                if (detail == null) {
                    return new RetrievalContext(Collections.emptyMap(), Collections.emptyList(),
                            Collections.emptyList(), false);
                }
                addEntity(entityIds, EntityType.SCHOOL, request.getScopeId());
                SchoolSummaryVO school = detail.getSchool();
                addEntityHint(entityHints, EntityType.SCHOOL, request.getScopeId(),
                        school == null ? null : school.getSchoolName(),
                        school == null ? null : school.getSchoolCode());
                if (detail.getResources() != null) {
                    for (SchoolResourceItemVO item : detail.getResources()) {
                        if (item == null || item.getResourceId() == null) {
                            continue;
                        }
                        addEntity(entityIds, EntityType.RESOURCE, item.getResourceId());
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
                    addGraphResourceEntities(entityIds, graphLoad.facts());
                    graphUnavailable = graphLoad.unavailable();
                }
            }
            case REGION -> {
                TownMapDetailVO detail = townMapService.getTownMapDetail(request.getScopeId());
                if (detail == null) {
                    return new RetrievalContext(Collections.emptyMap(), Collections.emptyList(),
                            Collections.emptyList(), false);
                }
                addEntityHint(entityHints, null, request.getScopeId(), detail.getRegionName());
                addRegionEntities(entityIds, entityHints, detail);
                if (needGraph) {
                    GraphLoad graphLoad = loadRegionGraphFacts(request.getScopeId(), detail);
                    graphFacts.addAll(graphLoad.facts());
                    graphUnavailable = graphLoad.unavailable();
                }
            }
            case RESOURCE -> {
                addEntity(entityIds, EntityType.RESOURCE, request.getScopeId());
                addEntityHint(entityHints, EntityType.RESOURCE, request.getScopeId());
                if (needGraph) {
                    GraphLoad graphLoad = loadResourceGraphFacts(request.getScopeId());
                    graphFacts.addAll(graphLoad.facts());
                    graphUnavailable = graphLoad.unavailable();
                }
            }
        }
        return new RetrievalContext(freezeEntityIds(entityIds), List.copyOf(entityHints),
                List.copyOf(graphFacts), graphUnavailable);
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
        if (entityIds == null || entityIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<EntityType, Set<Long>> copy = new LinkedHashMap<>();
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
            return new ChunkLoad(Collections.emptyList(), false, Collections.emptyList());
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
            return new ChunkLoad(Collections.emptyList(), !dense.successful() || !lexical.successful(), methods);
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
                .limit(MAX_CHUNKS)
                .collect(Collectors.toCollection(ArrayList::new));

        boolean degraded = !dense.successful() || !lexical.successful();
        return new ChunkLoad(chunks, degraded, successfulMethods(plan, dense, lexical));
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

    private record ScoredChunk(KnowledgeChunkVO chunk, double score) {
    }

    private record ChunkLoad(List<ScoredChunk> chunks,
                             boolean degraded,
                             List<String> retrievalMethods) {
    }

    private record GraphLoad(List<KnowledgeGraphFactVO> facts, boolean unavailable) {
    }

    private record RetrievalContext(Map<EntityType, Set<Long>> entityIds,
                                    List<EntityHint> entityHints,
                                    List<KnowledgeGraphFactVO> graphFacts,
                                    boolean graphUnavailable) {
    }
}
