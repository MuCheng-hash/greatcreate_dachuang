package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class AgentMemorySetting {

    private boolean available;

    private boolean enabled;

    private boolean effectiveEnabled;

    private String createdAt;

    private String updatedAt;
}
