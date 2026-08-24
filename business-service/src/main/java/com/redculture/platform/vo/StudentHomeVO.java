package com.redculture.platform.vo;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class StudentHomeVO {
    private StudentProfileVO student;
    private List<StudentClassSummaryVO> classes = new ArrayList<>();
    private List<ClassTaskVO> pendingTasks = new ArrayList<>();
    private List<StudentRecentResourceVO> recentResources = new ArrayList<>();
    private Summary summary = new Summary();

    @Data
    public static class Summary {
        private int pendingTaskCount;
        private int classCount;
        private int recentResourceCount;
    }
}
