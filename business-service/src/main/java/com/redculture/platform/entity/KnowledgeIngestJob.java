package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_ingest_job")
public class KnowledgeIngestJob extends BaseAuditEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private String status;
    private String currentNode;
    private Integer retryCount;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
