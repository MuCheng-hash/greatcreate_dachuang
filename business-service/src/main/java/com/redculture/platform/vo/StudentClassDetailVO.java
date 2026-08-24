package com.redculture.platform.vo;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class StudentClassDetailVO extends StudentClassSummaryVO {
    private String classType;
    private String schoolName;
    private long studentCount;
    private List<ClassTeacherVO> teachers = new ArrayList<>();
    private TaskSummary taskSummary = new TaskSummary();
    @Data public static class TaskSummary { private long pendingCount; private long submittedCount; private long completedCount; private long overdueCount; }
}
