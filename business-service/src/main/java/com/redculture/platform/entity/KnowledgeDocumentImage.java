package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_document_image")
public class KnowledgeDocumentImage extends BaseAuditEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long documentId;
    private String sha256;
    private String objectKey;
    private String altText;
    private String description;
    private String status;
    private String model;
    private String errorSummary;
}
