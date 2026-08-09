package com.redculture.platform.vo.admin;

import lombok.Data;

@Data
public class RagWebSourceRequest {

    private String displayName;

    private String domain;

    private Boolean enabled;

    private Integer sortOrder;
}
