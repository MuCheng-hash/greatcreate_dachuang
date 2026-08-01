package com.redculture.platform.vo.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentMemoryCreateRequest {

    private String memoryType;

    private String fieldKey;

    private String content;

    private Boolean replaceConflicts;

    public AgentMemoryCreateRequest(String memoryType, String fieldKey, String content) {
        this(memoryType, fieldKey, content, null);
    }
}
