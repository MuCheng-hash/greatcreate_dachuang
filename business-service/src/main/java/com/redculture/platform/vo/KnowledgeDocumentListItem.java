package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 管理后台文档入库列表中的文档与任务摘要。 */
@Data
public class KnowledgeDocumentListItem {
    private Long documentId;
    private Long schoolId;
    private String title;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private String documentStatus;
    private LocalDateTime createdAt;
    private LocalDateTime indexedAt;
    private String jobStatus;
    private String currentNode;
    private Integer retryCount;
    private String errorSummary;
    private LocalDateTime finishedAt;
}
