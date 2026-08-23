package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TeachingPlanGenerateRequest {

    private String threadId;

    private String modelId;

    private Long schoolId;

    private String grade;

    private String theme;

    private String objectives;

    private List<Long> resourceIds = new ArrayList<>();

    /** Legacy single-resource field retained for older clients. */
    private Long resourceId;

    private ActivityType activityType;

    private Integer durationMinutes;

    private Boolean practiceRequired;
}
