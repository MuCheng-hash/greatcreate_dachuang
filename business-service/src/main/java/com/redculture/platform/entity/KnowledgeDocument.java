package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_document")
public class KnowledgeDocument extends BaseAuditEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long schoolId;
    private String title;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private String sha256;
    private String objectKey;
    private String markdownObjectKey;
    private String status;
    private LocalDateTime publishedAt;
    private LocalDateTime indexedAt;
    private Long createdBy;
}
