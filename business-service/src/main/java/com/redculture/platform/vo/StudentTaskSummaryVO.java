package com.redculture.platform.vo;

import lombok.Data;

/** 学生任务摘要视图对象。 */
@Data
public class StudentTaskSummaryVO {
    /** 待处理数量。 */
    private long pendingCount;
    /** 提交数量。 */
    private long submittedCount;
    /** 已完成数量。 */
    private long completedCount;
    /** 逾期数量。 */
    private long overdueCount;
}
