package com.redculture.platform.vo;

import lombok.Data;

@Data
public class StudentClassSummaryVO {
    private Long classId;
    private String className;
    private String gradeName;
    private Boolean primary;
    private long teacherCount;
    private long pendingTaskCount;
    private long completedTaskCount;
    private long overdueTaskCount;
}
