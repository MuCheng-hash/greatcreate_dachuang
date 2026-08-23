package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import lombok.Data;

import java.util.List;
import java.util.ArrayList;

@Data
public class GeneratedTeachingPlanSaveRequest {

    private Long schoolId;

    private Long resourceId;

    private List<Long> resourceIds = new ArrayList<>();

    private Long ownerAccountId;

    private String planPayload;

    private String generationSource;

    private Long aiRunId;

    private String theme;

    private ActivityType activityType;

    private String grade;

    private Integer durationMinutes;

    private List<String> objectives;

    private List<String> activityFlow;

    private List<String> preparation;

    private List<String> safetyNotes;

    private List<String> reflection;

    private List<String> evaluation;

    private List<String> resourceBasis = new ArrayList<>();

    private List<String> fieldTasks = new ArrayList<>();

    private List<String> relatedResources = new ArrayList<>();

    private List<Object> citations = new ArrayList<>();
}
