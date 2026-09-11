package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库文档图片实体，对应数据库表 {@code knowledge_document_image}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_document_image")
public class KnowledgeDocumentImage extends BaseAuditEntity {
    /**
     * 知识库文档图片标识。
     */
    @TableId(type = IdType.AUTO) private Long id;
    /**
     * 关联的知识文档标识。
     */
    private Long documentId;
    /**
     * 内容的 SHA-256 摘要。
     */
    private String sha256;
    /**
     * 原始文件在对象存储中的键。
     */
    private String objectKey;
    /**
     * 图片替代文本。
     */
    private String altText;
    /**
     * 说明。
     */
    private String description;
    /**
     * 文档图片识别状态；初始为 {@code PENDING}，由图片处理流程推进。
     */
    private String status;
    /**
     * 执行处理任务的模型名称。
     */
    private String model;
    /**
     * 最近一次错误摘要。
     */
    private String errorSummary;
}
