package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EmbeddingStatus;
import com.redculture.platform.enums.EntityType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "content_chunk", autoResultMap = true)
public class ContentChunk extends BaseAuditEntity {

    @TableId(value = "chunk_id", type = IdType.AUTO)
    private Long chunkId;

    @TableField("entity_type")
    private EntityType entityType;

    @TableField("entity_id")
    private Long entityId;

    @TableField("chunk_title")
    private String chunkTitle;

    @TableField("chunk_text")
    private String chunkText;

    @TableField("chunk_index")
    private Integer chunkIndex;

    @TableField("source_id")
    private Long sourceId;

    @TableField("token_count")
    private Integer tokenCount;

    @TableField("embedding_status")
    private EmbeddingStatus embeddingStatus;
}
