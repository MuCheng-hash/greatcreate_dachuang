package com.redculture.platform.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.config.RagProperties;
import com.redculture.platform.entity.*;
import com.redculture.platform.enums.EmbeddingStatus;
import com.redculture.platform.mapper.*;
import com.redculture.platform.service.agent.AgentAdminClient;
import com.redculture.platform.service.agent.AgentBusyException;
import com.redculture.platform.service.rag.ChunkVectorStore;
import com.redculture.platform.vo.admin.AdminDashboardOverviewVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

@Service
public class AdminDashboardService {
    private final LocalEduResourceMapper resourceMapper;
    private final SchoolMapper schoolMapper;
    private final TeacherProfileMapper teacherMapper;
    private final StudentProfileMapper studentMapper;
    private final TeachingActivityPlanMapper planMapper;
    private final ContentChunkMapper chunkMapper;
    private final CatalogProjectionTaskMapper projectionTaskMapper;
    private final AgentAdminClient agentAdminClient;
    private final RagProperties ragProperties;
    private final ChunkVectorStore vectorStore;
    private final Scheduler agentBlockingScheduler;

    @Autowired
    public AdminDashboardService(LocalEduResourceMapper resourceMapper, SchoolMapper schoolMapper,
                                 TeacherProfileMapper teacherMapper, StudentProfileMapper studentMapper,
                                 TeachingActivityPlanMapper planMapper, ContentChunkMapper chunkMapper,
                                 CatalogProjectionTaskMapper projectionTaskMapper, AgentAdminClient agentAdminClient,
                                 RagProperties ragProperties, ChunkVectorStore vectorStore,
                                 @Qualifier("agentBlockingScheduler") Scheduler agentBlockingScheduler) {
        this.resourceMapper = resourceMapper; this.schoolMapper = schoolMapper; this.teacherMapper = teacherMapper;
        this.studentMapper = studentMapper; this.planMapper = planMapper; this.chunkMapper = chunkMapper;
        this.projectionTaskMapper = projectionTaskMapper; this.agentAdminClient = agentAdminClient;
        this.ragProperties = ragProperties; this.vectorStore = vectorStore;
        this.agentBlockingScheduler = agentBlockingScheduler;
    }

    public AdminDashboardService(LocalEduResourceMapper resourceMapper, SchoolMapper schoolMapper,
                                 TeacherProfileMapper teacherMapper, StudentProfileMapper studentMapper,
                                 TeachingActivityPlanMapper planMapper, ContentChunkMapper chunkMapper,
                                 CatalogProjectionTaskMapper projectionTaskMapper, AgentAdminClient agentAdminClient,
                                 RagProperties ragProperties, ChunkVectorStore vectorStore) {
        this(resourceMapper, schoolMapper, teacherMapper, studentMapper, planMapper, chunkMapper,
                projectionTaskMapper, agentAdminClient, ragProperties, vectorStore,
                Schedulers.immediate());
    }

    public Mono<AdminDashboardOverviewVO> overview() {
        return Mono.fromCallable(this::localOverview)
                .subscribeOn(agentBlockingScheduler)
                .onErrorMap(error -> Exceptions.unwrap(error) instanceof RejectedExecutionException,
                        AgentBusyException::new)
                .flatMap(result -> agentAdminClient
                        .observabilitySummary(Map.of("includeQuestionMetrics", "true"))
                        .map(summary -> {
                            result.setQuestionCount(readCount(summary));
                            result.setQuestionStatus("ok");
                            return result;
                        })
                        .onErrorResume(error -> {
                            result.setQuestionCount(null);
                            result.setQuestionStatus("unavailable");
                            return Mono.just(result);
                        }));
    }

    private AdminDashboardOverviewVO localOverview() {
        AdminDashboardOverviewVO result = new AdminDashboardOverviewVO();
        result.setResourceCount(resourceMapper.selectCount(null));
        result.setSchoolCount(schoolMapper.selectCount(null));
        result.setTeacherCount(teacherMapper.selectCount(null));
        result.setStudentCount(studentMapper.selectCount(null));
        result.setTeachingPlanCount(planMapper.selectCount(null));
        long pendingProjectionCount = projectionTaskMapper.selectCount(new LambdaQueryWrapper<CatalogProjectionTask>()
                .eq(CatalogProjectionTask::getStatus, "PENDING"));
        long failedProjectionCount = projectionTaskMapper.selectCount(new LambdaQueryWrapper<CatalogProjectionTask>()
                .eq(CatalogProjectionTask::getStatus, "FAILED"));
        result.setPendingProjectionCount(pendingProjectionCount + failedProjectionCount);
        result.setProjectionStatus(new LinkedHashMap<>(Map.of(
                "pending", pendingProjectionCount,
                "failed", failedProjectionCount,
                "total", pendingProjectionCount + failedProjectionCount)));
        result.setRagStatus(ragStatus());
        return result;
    }

    private Long readCount(Map<String, Object> summary) {
        if (summary == null) return 0L;
        for (String key : new String[]{"completedQuestionCount", "completedCalls", "totalCalls", "calls"}) {
            Object value = summary.get(key);
            if (value instanceof Number number) return number.longValue();
            if (value != null) try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException ignored) { }
        }
        return 0L;
    }

    private Map<String, Object> ragStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        long done = chunkMapper.selectCount(new LambdaQueryWrapper<ContentChunk>().eq(ContentChunk::getEmbeddingStatus, EmbeddingStatus.DONE));
        long pending = chunkMapper.selectCount(new LambdaQueryWrapper<ContentChunk>().eq(ContentChunk::getEmbeddingStatus, EmbeddingStatus.PENDING));
        long failed = chunkMapper.selectCount(new LambdaQueryWrapper<ContentChunk>().eq(ContentChunk::getEmbeddingStatus, EmbeddingStatus.FAILED));
        status.put("enabled", ragProperties.isEnabled());
        status.put("done", done);
        status.put("pending", pending);
        status.put("failed", failed);
        status.put("status", ragProperties.isEnabled() ? (failed > 0 ? "degraded" : "ok") : "disabled");
        status.put("collection", ragProperties.getQdrantCollection());
        try {
            String target = vectorStore.resolveAlias(ragProperties.getQdrantAlias());
            String collection = target == null || target.isBlank() ? ragProperties.getQdrantCollection() : target;
            status.put("reachable", true);
            status.put("aliasTarget", target);
            status.put("collection", collection);
            status.put("pointCount", vectorStore.listPointIds(collection).size());
        } catch (RuntimeException exception) {
            status.put("reachable", false);
            status.put("status", "unavailable");
            status.put("message", exception.getMessage());
        }
        return status;
    }
}
