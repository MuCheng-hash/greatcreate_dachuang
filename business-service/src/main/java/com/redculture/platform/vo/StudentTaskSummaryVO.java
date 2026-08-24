package com.redculture.platform.vo;

import lombok.Data;

@Data
public class StudentTaskSummaryVO {
    private long pendingCount;
    private long submittedCount;
    private long completedCount;
    private long overdueCount;
}
