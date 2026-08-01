package com.redculture.platform.service.agent;

import com.redculture.platform.vo.ai.AgentMemoryApplied;
import com.redculture.platform.vo.ai.AgentMemoryItem;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
public class AgentRuntimeResult {

    private GeneratedAnswer answer;

    private String threadId;

    private String status;

    private List<String> toolExecutions = new ArrayList<>();

    private String degradedReason;

    private List<AgentMemoryItem> memoryCandidates = new ArrayList<>();

    private AgentMemoryApplied memoryApplied;

    private List<String> retrievalMethods = new ArrayList<>();

    private String provider;

    private String model;

    private String fallbackLevel;

    public AgentRuntimeResult(GeneratedAnswer answer,
                              String threadId,
                              String status,
                              List<String> toolExecutions) {
        this(answer, threadId, status, toolExecutions, null, new ArrayList<>(), null,
                new ArrayList<>(), null, null, null);
    }

    public AgentRuntimeResult(GeneratedAnswer answer,
                              String threadId,
                              String status,
                              List<String> toolExecutions,
                              String degradedReason) {
        this(answer, threadId, status, toolExecutions, degradedReason,
                new ArrayList<>(), null, new ArrayList<>(), null, null, null);
    }
}
