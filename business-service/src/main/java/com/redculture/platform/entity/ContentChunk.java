package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EmbeddingStatus;
import com.redculture.platform.enums.EntityType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 内容检索分块实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "content_chunk", autoResultMap = true)
public class ContentChunk extends BaseAuditEntity {

    /**
     * 内容检索分块标识。
     */
    @TableId(value = "chunk_id", type = IdType.AUTO)
    private Long chunkId;

    /**
     * 业务实体类型。
     */
    @TableField("entity_type")
    private EntityType entityType;

    /**
     * 关联的业务实体标识。
     */
    @TableField("entity_id")
    private Long entityId;

    /**
     * 内容分块标题。
     */
    @TableField("chunk_title")
    private String chunkTitle;

    /**
     * 内容分块正文。
     */
    @TableField("chunk_text")
    private String chunkText;

    /**
     * 分块在所属实体中的顺序编号。
     */
    @TableField("chunk_index")
    private Integer chunkIndex;

    /**
     * 关联的数据来源标识。
     */
    @TableField("source_id")
    private Long sourceId;

    /**
     * 估算的文本 Token 数量。
     */
    @TableField("token_count")
    private Integer tokenCount;

    /**
     * 向量嵌入状态，取值由 {@link com.redculture.platform.enums.EmbeddingStatus} 定义。
     */
    @TableField("embedding_status")
    private EmbeddingStatus embeddingStatus;

    /**
     * 参与检索和向量化的规范化文本。
     */
    @TableField("retrieval_text")
    private String retrievalText;

    /**
     * 参与向量化内容的哈希值。
     */
    @TableField("embedding_hash")
    private String embeddingHash;

    /**
     * 生成向量时使用的嵌入模型。
     */
    @TableField("embedding_model")
    private String embeddingModel;

    /**
     * 嵌入向量维度。
     */
    @TableField("embedding_dimensions")
    private Integer embeddingDimensions;

    /**
     * 写入向量库时使用的索引版本。
     */
    @TableField("embedding_index_version")
    private String embeddingIndexVersion;

    /**
     * 向量生成时间。
     */
    @TableField("embedded_at")
    private LocalDateTime embeddedAt;
}
