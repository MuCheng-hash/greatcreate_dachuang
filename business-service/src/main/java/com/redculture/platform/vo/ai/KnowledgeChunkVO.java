package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class KnowledgeChunkVO {

    private String citationId;

    private Long chunkId;

    private String title;

    private String text;

    private Double score;

    private String retrievalMethod;

    private String entityType;

    private Long entityId;

    private Long sourceId;
}
