package com.redculture.platform.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class StudentClassActivityVO {
    private String activityType;
    private String title;
    private String content;
    private Long relatedTaskId;
    private LocalDateTime createdAt;
}
