package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 知识库导入任务实体，对应数据库表 {@code knowledge_ingest_job}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_ingest_job")
public class KnowledgeIngestJob extends BaseAuditEntity {
    /**
     * 知识库导入任务标识。
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 关联的知识文档标识。
     */
    private Long documentId;
    /**
     * 知识导入任务状态；初始为 {@code PENDING}，需结合当前处理节点判断进度。
     */
    private String status;
    /**
     * 知识导入任务当前执行节点。
     */
    private String currentNode;
    /**
     * 已重试次数。
     */
    private Integer retryCount;
    /**
     * 最近一次错误摘要。
     */
    private String errorSummary;
    /**
     * JSON 格式的扩展元数据。
     */
    private String metadataJson;
    /**
     * 重试任务时指定的起始节点。
     */
    private String restartFrom;
    /**
     * 开始处理时间。
     */
    private LocalDateTime startedAt;
    /**
     * 任务结束时间。
     */
    private LocalDateTime finishedAt;
}
