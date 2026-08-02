package com.redculture.platform.service.agent;

import com.redculture.platform.vo.ai.AgentMemoryConflictPreview;
import lombok.Getter;

@Getter
public class AgentMemoryConflictException extends RuntimeException {

    private final AgentMemoryConflictPreview preview;

    public AgentMemoryConflictException(String message, AgentMemoryConflictPreview preview) {
        super(message);
        this.preview = preview;
    }
}
