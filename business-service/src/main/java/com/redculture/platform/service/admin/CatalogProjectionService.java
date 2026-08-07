package com.redculture.platform.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.CatalogProjectionTask;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.enums.EmbeddingStatus;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.CatalogProjectionTaskMapper;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.service.rag.RagIndexService;
import com.redculture.platform.vo.admin.CatalogEntityVO;
import com.redculture.platform.vo.admin.CatalogRelationVO;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CatalogProjectionService {
    private final CatalogProjectionTaskMapper taskMapper;
    private final ContentChunkMapper chunkMapper;
    private final RagIndexService ragIndexService;
    private final Neo4jClient neo4jClient;
    private final CatalogAdminService catalogService;

    public CatalogProjectionService(CatalogProjectionTaskMapper taskMapper, ContentChunkMapper chunkMapper,
                                    RagIndexService ragIndexService, Neo4jClient neo4jClient,
                                    CatalogAdminService catalogService) {
        this.taskMapper = taskMapper; this.chunkMapper = chunkMapper;
        this.ragIndexService = ragIndexService; this.neo4jClient = neo4jClient; this.catalogService = catalogService;
    }

    public CatalogProjectionTask projectEntity(CatalogEntityVO entity) {
        if (entity == null || entity.getEntityId() == null || entity.getEntityType() == null) return null;
        CatalogProjectionTask task = newTask(entity.getEntityType(), entity.getEntityId(), "ENTITY");
        try {
            EntityType type = entityType(entity.getEntityType());
            String label = label(type);
            if (!Boolean.TRUE.equals(entity.getActive()) || !"approved".equals(entity.getReviewStatus())) {
                neo4jClient.query("MATCH (node:" + label + " {id:$id}) DETACH DELETE node")
                        .bind(entity.getEntityId()).to("id").run();
                deleteChunk(type, entity.getEntityId());
            } else {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("id", entity.getEntityId());
                properties.put("code", text(entity.getCode()));
                properties.put("name", text(entity.getName()));
                properties.put("alias", text(entity.getAlias()));
                properties.put("summary", text(entity.getSummary()));
                properties.put("address", text(entity.getAddress()));
                properties.put("longitude", entity.getLongitude());
                properties.put("latitude", entity.getLatitude());
                neo4jClient.query("MERGE (node:" + label + " {id:$id}) SET node.code=$code, node.name=$name, "
                                + "node.alias=$alias, node.summary=$summary, node.address=$address, node.longitude=$longitude, "
                                + "node.latitude=$latitude, node.active=true, node.published=true")
                        .bindAll(properties).run();
                upsertChunk(type, entity);
                ragIndexService.synchronizeIncrementally();
            }
            complete(task, "SUCCESS", null);
        } catch (RuntimeException exception) {
            complete(task, "FAILED", exception.getMessage());
        }
        return taskMapper.selectById(task.getTaskId());
    }

    public CatalogProjectionTask projectRelation(CatalogRelationVO relation) {
        if (relation == null || relation.getRelationId() == null) return null;
        CatalogProjectionTask task = newTask(relation.getRelationKind(), relation.getRelationId(), "RELATION");
        try {
            String sourceLabel = label(entityType(relation.getSourceType()));
            String targetLabel = label(entityType(relation.getTargetType()));
            String relationship = relationship(relation.getRelationType());
            neo4jClient.query("MATCH (source:" + sourceLabel + " {id:$sourceId, published:true}), "
                            + "(target:" + targetLabel + " {id:$targetId, published:true}) "
                            + "MERGE (source)-[edge:" + relationship + " {catalogRelationKind:$kind, catalogRelationId:$relationId}]->(target) "
                            + "SET edge.published=true")
                    .bind(relation.getSourceId()).to("sourceId").bind(relation.getTargetId()).to("targetId")
                    .bind(relation.getRelationKind()).to("kind").bind(relation.getRelationId()).to("relationId").run();
            complete(task, "SUCCESS", null);
        } catch (RuntimeException exception) {
            complete(task, "FAILED", exception.getMessage());
        }
        return taskMapper.selectById(task.getTaskId());
    }

    public void removeRelation(CatalogRelationVO relation) {
        if (relation == null || relation.getRelationId() == null) {
            throw new IllegalArgumentException("catalog relation not found");
        }
        String sourceLabel = label(entityType(relation.getSourceType()));
        String targetLabel = label(entityType(relation.getTargetType()));
        String relationship = relationship(relation.getRelationType());
        neo4jClient.query("MATCH (source:" + sourceLabel + " {id:$sourceId})-[edge:" + relationship
                        + " {catalogRelationKind:$kind, catalogRelationId:$relationId}]->(target:" + targetLabel + " {id:$targetId}) DELETE edge")
                .bind(relation.getSourceId()).to("sourceId").bind(relation.getTargetId()).to("targetId")
                .bind(relation.getRelationKind()).to("kind").bind(relation.getRelationId()).to("relationId").run();
    }

    public CatalogProjectionTask retry(Long taskId) {
        CatalogProjectionTask task = taskMapper.selectById(taskId);
        if (task == null) throw new IllegalArgumentException("projection task not found");
        task.setStatus("RETRIED"); task.setLastError(null); task.setAttemptCount((task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1);
        taskMapper.updateById(task);
        if ("ENTITY".equals(task.getTaskType())) {
            CatalogEntityVO entity = catalogService.detail(entityType(task.getEntityType()), task.getEntityId());
            if (entity == null) throw new IllegalArgumentException("projection entity no longer exists");
            return projectEntity(entity);
        }
        if ("RELATION".equals(task.getTaskType())) {
            CatalogRelationVO relation = catalogService.relation(task.getEntityType(), task.getEntityId());
            if (relation == null) throw new IllegalArgumentException("projection relation no longer exists");
            return projectRelation(relation);
        }
        throw new IllegalArgumentException("unsupported projection task type");
    }

    public java.util.List<CatalogProjectionTask> tasks() {
        return taskMapper.selectList(new LambdaQueryWrapper<CatalogProjectionTask>()
                .orderByDesc(CatalogProjectionTask::getUpdatedAt).last("LIMIT 100"));
    }

    private CatalogProjectionTask newTask(String type, Long id, String taskType) {
        CatalogProjectionTask task = new CatalogProjectionTask(); task.setEntityType(type); task.setEntityId(id); task.setTaskType(taskType); task.setStatus("PENDING"); task.setAttemptCount(1); taskMapper.insert(task); return task;
    }
    private void complete(CatalogProjectionTask task, String status, String error) { task.setStatus(status); task.setLastError(error == null ? null : error.substring(0, Math.min(error.length(), 500))); taskMapper.updateById(task); }
    private void upsertChunk(EntityType type, CatalogEntityVO entity) { ContentChunk current=chunkMapper.selectOne(new LambdaQueryWrapper<ContentChunk>().eq(ContentChunk::getEntityType,type).eq(ContentChunk::getEntityId,entity.getEntityId()).eq(ContentChunk::getChunkIndex,1).last("LIMIT 1")); if(current==null){current=new ContentChunk();current.setEntityType(type);current.setEntityId(entity.getEntityId());current.setChunkIndex(1);} current.setChunkTitle(entity.getName());current.setChunkText(String.join("\n", text(entity.getSummary()), text(entity.getDetail()), text(entity.getTargetGrade())));current.setTokenCount(current.getChunkText().length());current.setEmbeddingStatus(EmbeddingStatus.PENDING);if(current.getChunkId()==null)chunkMapper.insert(current);else chunkMapper.updateById(current); }
    private void deleteChunk(EntityType type, Long id) { chunkMapper.delete(new LambdaQueryWrapper<ContentChunk>().eq(ContentChunk::getEntityType,type).eq(ContentChunk::getEntityId,id)); }
    private EntityType entityType(String value) { for(EntityType type:EntityType.values())if(type.getValue().equals(value))return type; throw new IllegalArgumentException("unsupported entity type"); }
    private String label(EntityType type) { return switch(type){case RESOURCE -> "Resource";case SITE -> "Site";case MEMORIAL -> "Memorial";case HERO -> "Hero";case EVENT -> "Event";case STORY -> "Story";default -> throw new IllegalArgumentException("unsupported projection type");}; }
    private String relationship(String value) {
        String normalized=value==null?"":value.trim().toUpperCase().replaceAll("[^A-Z_]", "");
        if(normalized.isBlank())throw new IllegalArgumentException("unsupported relationship type");
        return switch (normalized) {
            case "PARTICIPANT" -> "PARTICIPATED_IN";
            case "WITNESS" -> "WITNESSED";
            case "MARTYR" -> "MARTYR_IN";
            case "MEMORIALIZED" -> "MEMORIALIZED_AT";
            default -> normalized;
        };
    }
    private String text(String value) { return value == null ? "" : value; }
}
