package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 知识库文档实体，对应数据库表 {@code knowledge_document}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_document")
public class KnowledgeDocument extends BaseAuditEntity {
    /**
     * 知识库文档标识。
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 关联的学校标识。
     */
    private Long schoolId;
    /**
     * 标题。
     */
    private String title;
    /**
     * 上传时的原始文件名。
     */
    private String originalFilename;
    /**
     * 内容类型。
     */
    private String contentType;
    /**
     * 文件大小，单位字节。
     */
    private Long fileSize;
    /**
     * 内容的 SHA-256 摘要。
     */
    private String sha256;
    /**
     * 原始文件在对象存储中的键。
     */
    private String objectKey;
    /**
     * 转换后 Markdown 文件在对象存储中的键。
     */
    private String markdownObjectKey;
    /**
     * 知识文档处理状态；初始为 {@code PENDING}，失败或降级后可重新发起处理。
     */
    private String status;
    /**
     * 发布时间。
     */
    private LocalDateTime publishedAt;
    /**
     * 文档完成索引的时间。
     */
    private LocalDateTime indexedAt;
    /**
     * 创建者账号标识。
     */
    private Long createdBy;
}
