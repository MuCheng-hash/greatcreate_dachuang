package com.redculture.platform.vo;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** 学生首页视图对象。 */
@Data
public class StudentHomeVO {
    /** 学生。 */
    private StudentProfileVO student;
    /** 班级列表。 */
    private List<StudentClassSummaryVO> classes = new ArrayList<>();
    /** 待处理任务列表。 */
    private List<ClassTaskVO> pendingTasks = new ArrayList<>();
    /** 最近资源列表。 */
    private List<StudentRecentResourceVO> recentResources = new ArrayList<>();
    /** 摘要信息。 */
    private Summary summary = new Summary();

    /** 学生首页统计摘要。 */
    @Data
    public static class Summary {
        /** 待处理任务数量。 */
        private int pendingTaskCount;
        /** 班级数量。 */
        private int classCount;
        /** 最近资源数量。 */
        private int recentResourceCount;
    }
}
