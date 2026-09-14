package com.redculture.platform.vo;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** 学生班级详情视图对象。 */
@Data
public class StudentClassDetailVO extends StudentClassSummaryVO {
    /**
     * 班级类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String classType;
    /** 学校名称。 */
    private String schoolName;
    /** 学生数量。 */
    private long studentCount;
    /** 教师列表。 */
    private List<ClassTeacherVO> teachers = new ArrayList<>();
    /** 任务摘要。 */
    private TaskSummary taskSummary = new TaskSummary();
    /** 学生班级任务统计摘要。 */
    @Data public static class TaskSummary { /** 待处理数量。 */ private long pendingCount; /** 提交数量。 */ private long submittedCount; /** 已完成数量。 */ private long completedCount; /** 逾期数量。 */ private long overdueCount; }
}
