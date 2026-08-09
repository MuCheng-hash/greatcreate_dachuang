package com.redculture.platform.vo.admin;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RagWebSourceVO {

    private Long sourceId;
    private String displayName;
    private String domain;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
