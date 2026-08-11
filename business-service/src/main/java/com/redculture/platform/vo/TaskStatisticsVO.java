package com.redculture.platform.vo;
import lombok.Data;
@Data public class TaskStatisticsVO { private Long taskId; private long pendingCount; private long submittedCount; private long returnedCount; private long completedCount; private long overdueCount; private long lateSubmissionCount; }
