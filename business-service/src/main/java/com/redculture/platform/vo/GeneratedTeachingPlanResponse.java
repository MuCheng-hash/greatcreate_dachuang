package com.redculture.platform.vo;

import com.redculture.platform.vo.ai.AgentMemoryApplied;
import com.redculture.platform.vo.ai.AgentMemoryItem;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GeneratedTeachingPlanResponse {

    private Long generationId;

    private String threadId;

    private String generationStatus;

    private String retrievalStatus;

    private List<String> retrievalMethods = new ArrayList<>();

    private String promptVersion;

    private String promptRunId;

    private String promptExperiment;

    private String promptVariant;

    private String llmProvider;

    private String llmModel;

    private Integer fallbackLevel;

    private List<AgentMemoryItem> memoryCandidates;

    private AgentMemoryApplied memoryApplied;

    private String message;

    private String theme;

    private String grade;

    private String activityType;

    private Integer durationMinutes;

    private Boolean practiceRequired;

    private List<String> objectives = new ArrayList<>();

    private List<String> resourceBasis = new ArrayList<>();

    private List<String> activityFlow = new ArrayList<>();

    private List<String> preparation = new ArrayList<>();

    private List<String> fieldTasks = new ArrayList<>();

    private List<String> safetyNotes = new ArrayList<>();

    private List<String> reflection = new ArrayList<>();

    private List<String> evaluation = new ArrayList<>();

    private List<GeneratedTeachingPlanCitationVO> citations = new ArrayList<>();

    private List<String> relatedResources = new ArrayList<>();

    private List<String> followUpSuggestions = new ArrayList<>();
}
