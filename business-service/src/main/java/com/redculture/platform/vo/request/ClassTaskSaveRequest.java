package com.redculture.platform.vo.request;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ClassTaskSaveRequest {
    private String title;
    private String description;
    private LocalDateTime dueAt;
    private String taskType = "red_culture_learning";
    private String submissionRule = "text_required";
    private Boolean allowLateSubmission = true;
    private List<Long> resourceIds = new ArrayList<>();
}
