package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentMemoryConflictPreview {

    private AgentMemoryItem candidate;

    private List<AgentMemoryItem> conflicts = new ArrayList<>();

    private boolean duplicate;
}
