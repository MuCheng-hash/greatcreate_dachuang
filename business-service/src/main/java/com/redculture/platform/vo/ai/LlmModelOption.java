package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class LlmModelOption {
    private String id;
    private String displayName;
    private String provider;
    private String model;
    private boolean isDefault;
    private boolean supportsJsonObject;
    private boolean supportsJsonSchema;
    private boolean supportsStructuredToolOutput;
}
