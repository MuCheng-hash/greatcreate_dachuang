package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import lombok.Data;

import java.util.List;

@Data
public class GeneratedTeachingPlanSaveRequest {

    private Long schoolId;

    private Long resourceId;

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
}
