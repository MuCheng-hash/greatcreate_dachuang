package com.redculture.platform.vo.ai;

import com.redculture.platform.vo.AgentCitationVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.discovery.DiscoveryClassificationResponse;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StatefulAgentResponse {

    private String threadId;

    private String taskType;

    private String answer;

    private String status;

    private String generationStatus;

    private String retrievalStatus;

    private String provider;

    private String model;

    private String fallbackLevel;

    private List<AgentCitationVO> citations = new ArrayList<>();

    private List<String> relatedResources = new ArrayList<>();

    private List<String> followUpQuestions = new ArrayList<>();

    private List<ToolExecutionResponse> toolExecutions = new ArrayList<>();

    private boolean contextCompacted;

    private GeneratedTeachingPlanResponse teachingPlan;

    private DiscoveryClassificationResponse resourceDiscovery;

    @Data
    public static class ToolExecutionResponse {
        private String name;
        private String status;
        private Integer durationMs;
    }
}
