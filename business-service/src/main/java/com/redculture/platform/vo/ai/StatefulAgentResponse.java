package com.redculture.platform.vo.ai;

import com.redculture.platform.vo.AgentCitationVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StatefulAgentResponse {

    private String threadId;

    private String clientTurnId;

    private String taskType;

    private String answer;

    private String status;

    private String generationStatus;

    private String retrievalStatus;

    private List<String> retrievalMethods = new ArrayList<>();

    private String provider;

    private String model;

    private String fallbackLevel;

    private String degradedReason;

    private List<AgentCitationVO> citations = new ArrayList<>();

    private List<String> relatedResources = new ArrayList<>();

    private List<String> followUpQuestions = new ArrayList<>();

    private List<ToolExecutionResponse> toolExecutions = new ArrayList<>();

    private boolean contextCompacted;

    private GeneratedTeachingPlanResponse teachingPlan;

    private List<AgentMemoryItem> memoryCandidates = new ArrayList<>();

    private AgentMemoryApplied memoryApplied;

    @Data
    public static class ToolExecutionResponse {
        private String name;
        private String status;
        private Integer durationMs;
    }
}
