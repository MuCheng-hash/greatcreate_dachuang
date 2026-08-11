package com.redculture.platform.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ClassTaskVO {
    private Long taskId;
    private Long classId;
    private String title;
    private String description;
    private String publisherName;
    private LocalDateTime publishedAt;
    private LocalDateTime dueAt;
    private String status;
    private String taskType;
    private String submissionRule;
    private boolean allowLateSubmission;
    private long totalCount;
    private long completedCount;
    private long overdueCount;
    private String studentStatus;
}
