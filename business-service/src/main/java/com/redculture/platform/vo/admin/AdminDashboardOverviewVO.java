package com.redculture.platform.vo.admin;

import lombok.Data;

import java.util.Map;

@Data
public class AdminDashboardOverviewVO {
    private long resourceCount;
    private long schoolCount;
    private long teacherCount;
    private long studentCount;
    private long teachingPlanCount;
    private Long questionCount;
    private String questionStatus;
    private Map<String, Object> ragStatus;
    private long pendingProjectionCount;
    private Map<String, Object> projectionStatus;
}
