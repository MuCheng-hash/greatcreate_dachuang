package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

@Data
public class TeachingPlanFeedbackRequest {

    private Boolean adopted;

    private Integer rating;

    private List<String> reasonCodes;

    private String teacherNote;
}
