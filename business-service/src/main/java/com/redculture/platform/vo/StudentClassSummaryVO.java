package com.redculture.platform.vo;

import lombok.Data;

/** 学生班级摘要视图对象。 */
@Data
public class StudentClassSummaryVO {
    /** 班级标识。 */
    private Long classId;
    /** 班级名称。 */
    private String className;
    /** 年级名称。 */
    private String gradeName;
    /** 是否为主要项。 */
    private Boolean primary;
    /** 教师数量。 */
    private long teacherCount;
    /** 待处理任务数量。 */
    private long pendingTaskCount;
    /** 已完成任务数量。 */
    private long completedTaskCount;
    /** 逾期任务数量。 */
    private long overdueTaskCount;
}
